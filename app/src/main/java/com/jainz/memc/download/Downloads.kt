package com.jainz.memc.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class DownloadJob(
    val id: Long,
    /** smb:// address of the file on the share */
    val sourceUri: String,
    val fileName: String,
    val title: String,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val bytesPerSecond: Long = 0,
    val message: String? = null,
    /** Playable address of the finished copy on the phone */
    val outputUri: String? = null,
)

/** Queue of files being copied from network shares to the phone. */
object Downloads {
    private val ids = AtomicLong(System.currentTimeMillis())
    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    fun enqueue(context: Context, sourceUri: String, fileName: String, title: String) {
        val already = _jobs.value.any {
            it.sourceUri == sourceUri && (it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING)
        }
        if (!already) _jobs.update { it + DownloadJob(ids.incrementAndGet(), sourceUri, fileName, title) }
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }

    fun cancel(context: Context, id: Long) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        when (job.status) {
            DownloadStatus.QUEUED -> update(id) { it.copy(status = DownloadStatus.CANCELLED) }
            DownloadStatus.RUNNING -> context.startService(
                Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL)
            )
            else -> Unit
        }
    }

    fun retry(context: Context, id: Long) {
        update(id) { it.copy(status = DownloadStatus.QUEUED, bytesDone = 0, message = null) }
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }

    fun clearFinished() {
        _jobs.update { list -> list.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING } }
    }

    internal fun update(id: Long, change: (DownloadJob) -> DownloadJob) {
        _jobs.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    internal fun nextQueued(): DownloadJob? = _jobs.value.firstOrNull { it.status == DownloadStatus.QUEUED }
}
