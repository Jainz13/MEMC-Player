package com.jainz.memc.data.transfer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jainz.memc.MemcApp
import com.jainz.memc.core.DebugRecorder
import com.jainz.memc.data.VideoFiles
import com.jainz.memc.data.scan.ScanWorker
import com.jainz.memc.data.smb.SmbPool
import kotlinx.coroutines.CancellationException

/**
 * Copies or moves videos from the phone to a folder on a network share, one after another,
 * with a progress notification. "Move" removes the phone copy only after the share's copy has
 * been checked to be complete. Afterwards the share is rescanned so the videos show up there.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val g = MemcApp.graph
        val ctx = applicationContext
        val ids = inputData.getLongArray(KEY_IDS) ?: return Result.failure()
        val source = g.db.sources().get(inputData.getLong(KEY_SOURCE, -1)) ?: return Result.failure()
        val folder = inputData.getString(KEY_FOLDER).orEmpty()
        val move = inputData.getBoolean(KEY_MOVE, false)
        val videos = ids.toList().mapNotNull { g.db.videos().get(it) }.filter { !it.uri.startsWith("smb://") }
        if (videos.isEmpty()) return Result.success()

        val verb = if (move) "Moving" else "Copying"
        val total = videos.sumOf { it.size }.coerceAtLeast(1)
        var before = 0L
        var lastNote = 0L
        var uploaded = 0
        var uploadedBytes = 0L
        var already = 0
        var notRemoved = 0
        val failed = mutableListOf<String>()
        runInForeground(NOTE_ID, Transfers.notification(ctx, "$verb to ${source.name}", "Starting…", -1, true))
        DebugRecorder.event("upload", "$verb ${videos.size} file(s) to ${source.name}/$folder")

        for ((i, v) in videos.withIndex()) {
            if (isStopped) break
            val done = before
            try {
                val sent = Transfers.send(g.smb, source, Transfers.join(folder, v.fileName), v.size, { Transfers.open(ctx, v.uri) }) { bytes ->
                    val now = System.currentTimeMillis()
                    if (now - lastNote > 700) {
                        lastNote = now
                        val pct = ((done + bytes) * 100 / total).toInt()
                        Transfers.notify(
                            ctx, NOTE_ID,
                            Transfers.notification(ctx, "$verb to ${source.name} · $pct%", "${i + 1} of ${videos.size}: ${v.fileName}", pct, true),
                        )
                    }
                    if (isStopped) throw InterruptedException()
                }
                if (sent) {
                    uploaded++
                    uploadedBytes += v.size
                } else {
                    already++
                }
                if (move && VideoFiles.delete(ctx, v) != null) notRemoved++
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                failed += "${v.fileName}: ${SmbPool.friendlyError(e)}"
            }
            before += v.size
        }

        if (uploaded > 0) ScanWorker.enqueue(ctx)
        val summary = buildList {
            if (uploaded > 0) add((if (move) "Moved " else "Copied ") + Transfers.amount(uploaded, uploadedBytes))
            if (already > 0) add("$already already on the share" + if (move) " (removed from the phone)" else "")
            if (notRemoved > 0) add("$notRemoved couldn't be removed from the phone - allow \"All files\" access under Library → Phone storage")
            if (failed.isNotEmpty()) add("${failed.size} failed:\n" + failed.take(5).joinToString("\n"))
            if (isStopped) add("Stopped")
        }.joinToString("\n").ifBlank { "Nothing to do" }
        DebugRecorder.event("upload", summary.replace('\n', ' '))
        Transfers.notify(ctx, NOTE_DONE_ID, Transfers.notification(ctx, "${source.name}: ${if (failed.isEmpty()) "done" else "finished with problems"}", summary, 100, false))
        return Result.success()
    }

    companion object {
        private const val KEY_IDS = "ids"
        private const val KEY_SOURCE = "source"
        private const val KEY_FOLDER = "folder"
        private const val KEY_MOVE = "move"
        private const val NOTE_ID = 7301
        private const val NOTE_DONE_ID = 7302
        const val NAME = "upload"

        /** Queues the copy (runs after any copy already queued). */
        fun enqueue(context: Context, videoIds: List<Long>, sourceId: Long, folder: String, move: Boolean) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_IDS to videoIds.toLongArray(),
                        KEY_SOURCE to sourceId,
                        KEY_FOLDER to folder,
                        KEY_MOVE to move,
                    ),
                )
                .addTag(NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
