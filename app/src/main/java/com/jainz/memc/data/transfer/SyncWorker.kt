package com.jainz.memc.data.transfer

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jainz.memc.MemcApp
import com.jainz.memc.core.DebugRecorder
import com.jainz.memc.data.scan.NameParser
import com.jainz.memc.data.scan.ScanWorker
import com.jainz.memc.data.smb.SmbPool
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Automatic backups: for each backup rule, uploads what is in the phone folder and not yet on
 * the share (same name and size counts as there). Runs every hour in the background when its
 * conditions are met, and right away from "Sync now".
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private data class Item(val doc: DocumentFile, val relPath: String)

    override suspend fun doWork(): Result {
        val g = MemcApp.graph
        val ctx = applicationContext
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val only = inputData.getString(KEY_RULE)
        val rules = BackupRules.all().filter { it.enabled && (only == null || it.id == only) }
        if (rules.isEmpty()) return Result.success()
        var anyUploaded = false
        var noted = false

        for (rule in rules) {
            if (isStopped) break
            // Background runs respect the rule's conditions; "Sync now" doesn't wait for them.
            if (!manual && rule.wifiOnly && metered(ctx)) continue
            if (!manual && rule.chargingOnly && !charging(ctx)) continue
            val source = g.db.sources().get(rule.sourceId)
            if (source == null) {
                BackupRules.report(rule.id, "The network share for this backup was removed")
                continue
            }
            val root = runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(rule.treeUri)) }.getOrNull()
            if (root == null || !root.canRead()) {
                BackupRules.report(rule.id, "Android no longer lets the app read \"${rule.label}\" - remove this backup and add it again")
                continue
            }
            val items = ArrayList<Item>()
            runCatching { walk(root, "", rule.videosOnly, items, 0) }
            var uploaded = 0
            var uploadedBytes = 0L
            var already = 0
            var removed = 0
            val failed = mutableListOf<String>()
            var lastNote = 0L
            // Started from "Sync now" (app open): run as a foreground job so big files finish.
            if (manual && items.isNotEmpty()) {
                runInForeground(NOTE_ID, Transfers.notification(ctx, "Backing up \"${rule.label}\"", "Checking ${items.size} files…", -1, true))
            }
            for ((i, item) in items.withIndex()) {
                if (isStopped) break
                val size = item.doc.length()
                try {
                    val sent = Transfers.send(
                        g.smb, source, Transfers.join(rule.folder, item.relPath), size,
                        { Transfers.open(ctx, item.doc.uri.toString()) },
                    ) { bytes ->
                        noted = true
                        val now = System.currentTimeMillis()
                        if (now - lastNote > 700) {
                            lastNote = now
                            val pct = if (size > 0) (bytes * 100 / size).toInt() else -1
                            Transfers.notify(
                                ctx, NOTE_ID,
                                Transfers.notification(
                                    ctx, "Backing up \"${rule.label}\" to ${source.name}",
                                    "${i + 1} of ${items.size}: ${item.relPath}", pct, true,
                                ),
                            )
                        }
                        if (isStopped) throw InterruptedException()
                    }
                    if (sent) {
                        uploaded++
                        uploadedBytes += size
                    } else {
                        already++
                    }
                    if (rule.deleteAfter && runCatching { item.doc.delete() }.getOrDefault(false)) removed++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    failed += "${item.relPath}: ${SmbPool.friendlyError(e)}"
                    // The share went away: no point trying every other file.
                    if (failed.size >= 3 && uploaded == 0) break
                }
            }
            if (uploaded > 0) anyUploaded = true
            val result = buildList {
                add(if (uploaded > 0) "Uploaded " + Transfers.amount(uploaded, uploadedBytes) else "Nothing new")
                if (already > 0) add("$already already backed up")
                if (removed > 0) add("$removed removed from the phone")
                if (failed.isNotEmpty()) add("${failed.size} failed (${failed.first()})")
            }.joinToString(" · ")
            BackupRules.report(rule.id, result)
            DebugRecorder.event("backup", "${rule.label} -> ${source.name}/${rule.folder}: $result")
            if (uploaded > 0 || failed.isNotEmpty()) {
                Transfers.notify(ctx, NOTE_DONE_ID, Transfers.notification(ctx, "Backup \"${rule.label}\"", result, 100, false))
            }
        }
        if (noted) runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).cancel(NOTE_ID) }
        if (anyUploaded) ScanWorker.enqueue(ctx)
        return Result.success()
    }

    /** Files under [dir] (sub-folders included), skipping hidden and half-written ones. */
    private fun walk(dir: DocumentFile, prefix: String, videosOnly: Boolean, out: MutableList<Item>, depth: Int) {
        if (depth > 12) return
        for (f in dir.listFiles()) {
            val name = f.name ?: continue
            if (name.startsWith(".") || name.endsWith(".part") || name.endsWith(".tmp")) continue
            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
            if (f.isDirectory) {
                walk(f, rel, videosOnly, out, depth + 1)
            } else if (f.isFile && (!videosOnly || NameParser.isVideo(name))) {
                // Still being written (a download in progress): leave it for the next run.
                if (System.currentTimeMillis() - f.lastModified() < SETTLE_MS) continue
                out += Item(f, rel)
            }
        }
    }

    private fun metered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        return cm.isActiveNetworkMetered
    }

    private fun charging(ctx: Context): Boolean {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        if (bm != null) return bm.isCharging
        val status = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    companion object {
        private const val KEY_MANUAL = "manual"
        private const val KEY_RULE = "rule"
        private const val NOTE_ID = 7303
        private const val NOTE_DONE_ID = 7304
        private const val PERIODIC = "backup-sync"
        private const val NOW = "backup-sync-now"
        /** Files changed this recently are skipped (still downloading or being copied). */
        private const val SETTLE_MS = 2 * 60_000L

        /** Keeps the hourly background run scheduled while any backup is switched on. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (BackupRules.all().none { it.enabled }) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Runs one backup (or all) right away. */
        fun syncNow(context: Context, ruleId: String? = null) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true, KEY_RULE to ruleId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        fun runningInfo(context: Context) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NOW)
    }
}
