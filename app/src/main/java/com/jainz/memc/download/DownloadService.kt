package com.jainz.memc.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.jainz.memc.MainActivity
import com.jainz.memc.MemcApp
import com.jainz.memc.R
import com.jainz.memc.data.scan.NameParser
import com.jainz.memc.data.scan.ScanWorker
import com.jainz.memc.data.smb.SmbDataSource
import com.jainz.memc.ui.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Copies videos from network shares to the phone (Movies/MEMC Downloads) so they can be
 * watched offline. Uses the same read-ahead SMB reader as playback. With "All files" access
 * the file keeps its exact name and subtitle files next to it are copied too.
 */
@UnstableApi
class DownloadService : Service() {

    private class Cancelled : IOException("cancelled")

    private class Target(val stream: OutputStream, val finish: () -> String, val abort: () -> Unit)

    private var worker: Job? = null
    private var isForeground = false
    @Volatile private var cancelRequested = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isForeground) {
            try {
                startForeground(NOTIFICATION_ID, notification("Preparing download…", -1), foregroundType())
                isForeground = true
            } catch (e: Exception) {
                Downloads.nextQueued()?.let { job ->
                    Downloads.update(job.id) {
                        it.copy(status = DownloadStatus.FAILED, message = "Android blocked the download: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (intent?.action == ACTION_CANCEL) {
            cancelRequested = true
        } else if (worker?.isActive != true) {
            runQueue()
        }
        return START_NOT_STICKY
    }

    private fun foregroundType(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    private fun runQueue() {
        acquireWakeLock()
        worker = MemcApp.graph.scope.launch {
            while (true) {
                val job = Downloads.nextQueued() ?: break
                cancelRequested = false
                Downloads.update(job.id) { it.copy(status = DownloadStatus.RUNNING, bytesDone = 0, message = null) }
                try {
                    val output = copy(job)
                    Downloads.update(job.id) {
                        it.copy(status = DownloadStatus.DONE, outputUri = output, message = "Saved to Movies/MEMC Downloads", bytesPerSecond = 0)
                    }
                    notifyDone(job.title, ok = true)
                    ScanWorker.enqueue(applicationContext)
                } catch (e: Cancelled) {
                    Downloads.update(job.id) { it.copy(status = DownloadStatus.CANCELLED, message = null, bytesPerSecond = 0) }
                } catch (e: Exception) {
                    Downloads.update(job.id) {
                        it.copy(status = DownloadStatus.FAILED, message = e.message ?: e.javaClass.simpleName, bytesPerSecond = 0)
                    }
                    notifyDone(job.title, ok = false)
                }
            }
            withContext(Dispatchers.Main) {
                worker = null
                if (Downloads.nextQueued() != null) {
                    runQueue()
                } else {
                    releaseWakeLock()
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    isForeground = false
                    stopSelf()
                }
            }
        }
    }

    // ---- Copying ----------------------------------------------------------------------------------

    private fun copy(job: DownloadJob): String {
        val g = MemcApp.graph
        val uri = Uri.parse(job.sourceUri)
        val source = SmbDataSource(g.smb)
        var target: Target? = null
        try {
            val total = source.open(DataSpec(uri))
            Downloads.update(job.id) { it.copy(bytesTotal = total) }

            val free = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
            if (total + RESERVE_BYTES > free) {
                throw IOException("Not enough space on the phone: the file is ${Format.size(total)}, ${Format.size(free)} free")
            }
            existingCopy(job.fileName, total)?.let { return it }

            val out = openTarget(job.fileName)
            target = out
            val buffer = ByteArray(1 shl 20)
            var done = 0L
            var lastReport = System.currentTimeMillis()
            var lastBytes = 0L
            while (true) {
                if (cancelRequested) throw Cancelled()
                val n = source.read(buffer, 0, buffer.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.stream.write(buffer, 0, n)
                done += n
                val now = System.currentTimeMillis()
                if (now - lastReport >= 700) {
                    val speed = (done - lastBytes) * 1000 / (now - lastReport)
                    lastReport = now
                    lastBytes = done
                    Downloads.update(job.id) { it.copy(bytesDone = done, bytesPerSecond = speed) }
                    val percent = if (total > 0) (done * 100 / total).toInt() else -1
                    runCatching {
                        getSystemService(NotificationManager::class.java).notify(
                            NOTIFICATION_ID,
                            notification("${job.title} · ${Format.size(done)} of ${Format.size(total)} · ${Format.size(speed)}/s", percent),
                        )
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("The connection dropped at ${Format.size(done)} of ${Format.size(total)}")
            out.stream.flush()
            out.stream.close()
            val result = out.finish()
            target = null
            Downloads.update(job.id) { it.copy(bytesDone = done) }
            runCatching { copySubtitles(uri) }
            return result
        } finally {
            runCatching { source.close() }
            target?.let {
                runCatching { it.stream.close() }
                runCatching { it.abort() }
            }
        }
    }

    private fun fullAccess() = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    private fun downloadDir() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER)

    private fun existingCopy(fileName: String, size: Long): String? {
        if (!fullAccess()) return null
        val f = File(downloadDir(), fileName)
        return if (f.exists() && f.length() == size) Uri.fromFile(f).toString() else null
    }

    private fun openTarget(fileName: String): Target {
        if (fullAccess()) {
            val dir = downloadDir().apply { mkdirs() }
            val file = File(dir, fileName)
            val part = File(dir, "$fileName.part")
            return Target(
                stream = FileOutputStream(part),
                finish = {
                    file.delete()
                    if (!part.renameTo(file)) throw IOException("Could not finish the file on the phone")
                    MediaScannerConnection.scanFile(applicationContext, arrayOf(file.absolutePath), null, null)
                    Uri.fromFile(file).toString()
                },
                abort = { part.delete() },
            )
        }
        // Without "All files" access: go through the system media store.
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(NameParser.extension(fileName)) ?: "video/x-matroska"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val item = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Android refused to create the file")
        val stream = resolver.openOutputStream(item) ?: throw IOException("Android refused to open the file")
        return Target(
            stream = stream,
            finish = {
                resolver.update(item, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                item.toString()
            },
            abort = { resolver.delete(item, null, null) },
        )
    }

    /** Copies Movie.srt / Movie.en.ass etc. sitting next to the video (needs "All files" access). */
    private fun copySubtitles(videoUri: Uri) {
        if (!fullAccess()) return
        val g = MemcApp.graph
        val loc = g.smb.resolve(videoUri)
        val dir = loc.path.substringBeforeLast('/', "")
        val base = loc.path.substringAfterLast('/').substringBeforeLast('.')
        val subs = g.smb.list(loc.copy(path = dir))
            .filter { !it.isDir && NameParser.isSubtitle(it.name) && it.name.startsWith(base, ignoreCase = true) && it.size < 20L * 1024 * 1024 }
        for (entry in subs) {
            val subUri = videoUri.buildUpon().path(null).apply {
                appendPath(loc.share)
                entry.path.split('/').filter { it.isNotEmpty() }.forEach { appendPath(it) }
            }.build()
            val source = SmbDataSource(g.smb)
            try {
                source.open(DataSpec(subUri))
                FileOutputStream(File(downloadDir(), entry.name)).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = source.read(buffer, 0, buffer.size)
                        if (n == C.RESULT_END_OF_INPUT) break
                        out.write(buffer, 0, n)
                    }
                }
            } finally {
                runCatching { source.close() }
            }
        }
    }

    // ---- Notifications ----------------------------------------------------------------------------

    private fun channel(): String {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        }
        return CHANNEL
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notification(text: String, percent: Int) = NotificationCompat.Builder(this, channel())
        .setSmallIcon(R.drawable.ic_stat_memc)
        .setContentTitle("Downloading")
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setContentIntent(openAppIntent())
        .setProgress(100, percent.coerceAtLeast(0), percent < 0)
        .build()

    private fun notifyDone(title: String, ok: Boolean) {
        val n = NotificationCompat.Builder(this, channel())
            .setSmallIcon(R.drawable.ic_stat_memc)
            .setContentTitle(if (ok) "Download finished" else "Download failed")
            .setContentText(if (ok) "$title is saved on this phone" else "$title - open the app for details")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1 + (title.hashCode() and 0xffff), n) }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "memc:download")
            .apply { acquire(6 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        cancelRequested = true
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "com.jainz.memc.download.CANCEL"
        const val FOLDER = "MEMC Downloads"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 4343
        private const val RESERVE_BYTES = 300L * 1024 * 1024
    }
}
