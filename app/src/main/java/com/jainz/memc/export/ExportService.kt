package com.jainz.memc.export

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.jainz.memc.MainActivity
import com.jainz.memc.MemcApp
import com.jainz.memc.R
import com.jainz.memc.memc.MemcControl
import com.jainz.memc.memc.MemcEffect
import com.jainz.memc.memc.NpuFlow
import com.jainz.memc.player.ColorFixExtractorsFactory
import com.jainz.memc.player.RoutingDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders videos to new 60 fps files in the background:
 * decode (phone hardware) -> MEMC effect on the GPU -> HEVC encode (hardware) -> MP4.
 * HDR10 sources stay HDR10 (10-bit HEVC). Finished files go to Movies/MEMC Player, where
 * the library scan picks them up as another version of the same movie.
 */
@UnstableApi
class ExportService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var currentId: Long = -1
    private var withoutAudio = false
    private var tempFile: File? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val progress = ProgressHolder()
    private var isForeground = false
    private var stats: String? = null
    private var heatListener: android.os.PowerManager.OnThermalStatusChangedListener? = null
    private var exportControl: MemcControl? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isForeground) {
            try {
                goForeground("Preparing export…", -1)
                isForeground = true
            } catch (e: Exception) {
                // Android refused to run the export as a foreground job: report it instead of crashing.
                ExportManager.nextQueued()?.let { job ->
                    ExportManager.update(job.id) {
                        it.copy(status = ExportStatus.FAILED, message = "Android blocked the export: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                stopSelf()
                return START_NOT_STICKY
            }
        }
        try {
            if (intent?.action == ACTION_CANCEL) {
                cancelCurrent()
            } else if (transformer == null) {
                startNext()
            }
        } catch (e: Exception) {
            failCurrent("Export error: ${e.message ?: e.javaClass.simpleName}")
        }
        return START_NOT_STICKY
    }

    private fun failCurrent(message: String) {
        transformer?.let { runCatching { it.cancel() } }
        transformer = null
        handler.removeCallbacksAndMessages(null)
        tempFile?.delete()
        if (currentId >= 0) ExportManager.update(currentId) { it.copy(status = ExportStatus.FAILED, message = message) }
        handler.post { runCatching { startNext() } }
    }

    // ---- Queue ----------------------------------------------------------------------------

    private fun startNext() {
        val job = ExportManager.nextQueued()
        if (job == null) {
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
            stopSelf()
            return
        }
        currentId = job.id
        withoutAudio = false
        ExportManager.update(job.id) {
            it.copy(status = ExportStatus.RUNNING, progress = 0, message = null, startedAt = System.currentTimeMillis())
        }
        acquireWakeLock()
        begin(job)
    }

    private fun begin(job: ExportJob) {
        try {
            beginUnsafe(job)
        } catch (e: Throwable) {
            failCurrent("Could not start the export: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun beginUnsafe(job: ExportJob) {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: cacheDir
        val out = File(dir, "export-${job.id}.mp4").also { it.delete() }
        tempFile = out

        val g = MemcApp.graph
        // Export always uses the best quality setting: there is no real-time deadline here.
        // Full resolution and no load governor: export has no real-time deadline.
        val control = MemcControl(enabled = true, targetFps = 60, quality = 2).apply {
            adaptive = false
            // AI interpolation on the NPU when enabled; the engine falls back to block matching
            // by itself if the NPU can't be used.
            if (g.settings.npuExport.value && NpuFlow.hasModel(this@ExportService, NpuFlow.EXPORT)) {
                val appContext = applicationContext
                npuFactory = { NpuFlow.create(appContext, NpuFlow.EXPORT) }
            }
        }
        exportControl = control
        // On a hot phone the export leaves the NPU alone and carries on with the GPU engine.
        runCatching {
            val pm = getSystemService(android.os.PowerManager::class.java)
            heatListener?.let { pm.removeThermalStatusListener(it) }
            control.thermalLevel = minOf(pm.currentThermalStatus, 2)
            val listener = android.os.PowerManager.OnThermalStatusChangedListener { control.thermalLevel = minOf(it, 2) }
            heatListener = listener
            pm.addThermalStatusListener(mainExecutor, listener)
        }
        val effect = MemcEffect(control)
        // Same colour fix as playback, so HDR files missing container colour info export as HDR10.
        val extractors = ColorFixExtractorsFactory(
            DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
        )
        val mediaSources = DefaultMediaSourceFactory(RoutingDataSource.Factory(this, g.smb), extractors)
        val assetLoader = DefaultAssetLoaderFactory(
            this,
            DefaultDecoderFactory.Builder(this).build(),
            Clock.DEFAULT,
            mediaSources,
            DataSourceBitmapLoader(this),
        )
        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setEncoderFactory(DefaultEncoderFactory.Builder(this).setEnableFallback(true).build())
            .setAssetLoaderFactory(assetLoader)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    try {
                        // Speed figures for troubleshooting: which encoder ran and how fast.
                        val started = ExportManager.get(job.id)?.startedAt ?: 0L
                        val seconds = ((System.currentTimeMillis() - started) / 1000.0).coerceAtLeast(1.0)
                        stats = String.format(
                            java.util.Locale.US, "%d frames in %.0f min (%.1f fps) · encoder %s",
                            exportResult.videoFrameCount, seconds / 60, exportResult.videoFrameCount / seconds,
                            exportResult.videoEncoderName ?: "unknown",
                        ) + (exportControl?.npuStatus?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                        onFinished(job, out)
                    } catch (e: Exception) {
                        failCurrent("Saving failed: ${e.message}")
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    try {
                        onFailed(job, exportException)
                    } catch (e: Exception) {
                        failCurrent("${exportException.errorCodeName}: ${exportException.message}")
                    }
                }
            })
            .build()

        val item = EditedMediaItem.Builder(MediaItem.fromUri(job.sourceUri))
            .setRemoveAudio(withoutAudio)
            .setEffects(Effects(emptyList(), listOf(effect)))
            .build()
        transformer = t
        try {
            t.start(item, out.absolutePath)
        } catch (e: Exception) {
            transformer = null
            ExportManager.update(job.id) { it.copy(status = ExportStatus.FAILED, message = e.message ?: "Could not start") }
            handler.post { startNext() }
            return
        }
        pollProgress()
    }

    private fun pollProgress() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val t = transformer ?: return
                if (t.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val p = progress.progress
                    ExportManager.update(currentId) { it.copy(progress = p) }
                    val title = ExportManager.get(currentId)?.title ?: "video"
                    runCatching {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, progressNotification("Exporting $title at 60 fps", p))
                    }
                }
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun onFinished(job: ExportJob, out: File) {
        transformer = null
        handler.removeCallbacksAndMessages(null)
        ExportManager.update(job.id) { it.copy(progress = 100, message = "Saving to Movies…") }
        MemcApp.graph.scope.launch {
            val result = runCatching { publish(out, job.title) }
            withContext(Dispatchers.Main) {
                result.onSuccess { uri ->
                    ExportManager.update(job.id) {
                        it.copy(
                            status = ExportStatus.DONE,
                            outputUri = uri,
                            message = if (withoutAudio) {
                                "Saved without sound - this file's audio (e.g. DTS/TrueHD) can't be re-encoded on the phone"
                            } else {
                                "Saved to Movies/MEMC Player"
                            } + (stats?.let { s -> " · $s" } ?: ""),
                        )
                    }
                    notifyDone(job.title, ok = true)
                }.onFailure { e ->
                    ExportManager.update(job.id) { it.copy(status = ExportStatus.FAILED, message = "Saving failed: ${e.message}") }
                    notifyDone(job.title, ok = false)
                }
                startNext()
            }
        }
    }

    private fun onFailed(job: ExportJob, e: ExportException) {
        transformer = null
        handler.removeCallbacksAndMessages(null)
        tempFile?.delete()
        if (!withoutAudio && ExportManager.get(job.id)?.status == ExportStatus.RUNNING) {
            // Most failures on phones come from audio the hardware can't re-encode (DTS, TrueHD).
            // Try once more with the video only.
            withoutAudio = true
            ExportManager.update(job.id) { it.copy(progress = 0, message = "Retrying without sound…") }
            begin(job)
            return
        }
        ExportManager.update(job.id) {
            it.copy(status = ExportStatus.FAILED, message = "${e.errorCodeName}: ${e.message ?: "export failed"}")
        }
        notifyDone(job.title, ok = false)
        startNext()
    }

    private fun cancelCurrent() {
        val t = transformer ?: return
        transformer = null
        handler.removeCallbacksAndMessages(null)
        runCatching { t.cancel() }
        tempFile?.delete()
        ExportManager.update(currentId) { it.copy(status = ExportStatus.CANCELLED, message = null) }
        startNext()
    }

    // ---- Saving -------------------------------------------------------------------------------

    /** Copies the finished file into Movies/MEMC Player and returns its content:// address. */
    private suspend fun publish(file: File, title: String): String = withContext(Dispatchers.IO) {
        val safe = title.replace(Regex("""[\\/:*?"<>|]"""), " ").trim().ifBlank { "Video" }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$safe 60fps.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/MEMC Player")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("Android refused to create the file")
        try {
            resolver.openOutputStream(uri)!!.use { output -> file.inputStream().use { it.copyTo(output, 1 shl 20) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        } finally {
            file.delete()
        }
        uri.toString()
    }

    // ---- Notifications ----------------------------------------------------------------------------

    private fun channel(): String {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Exports", NotificationManager.IMPORTANCE_LOW))
        }
        return CHANNEL
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progressNotification(text: String, percent: Int) = NotificationCompat.Builder(this, channel())
        .setSmallIcon(R.drawable.ic_stat_memc)
        .setContentTitle("MEMC Player")
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setContentIntent(openAppIntent())
        .setProgress(100, percent.coerceAtLeast(0), percent < 0)
        .build()

    private fun goForeground(text: String, percent: Int) {
        val n = progressNotification(text, percent)
        val type = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        // Not ServiceCompat: it filters the type through Android 14's list, which has no
        // "mediaProcessing" (added in Android 15), and Android then rejects "type none".
        startForeground(NOTIFICATION_ID, n, type)
    }

    private fun notifyDone(title: String, ok: Boolean) {
        val n = NotificationCompat.Builder(this, channel())
            .setSmallIcon(R.drawable.ic_stat_memc)
            .setContentTitle(if (ok) "Export finished" else "Export failed")
            .setContentText(if (ok) "$title - 60 fps copy saved to Movies/MEMC Player" else "$title - open the app for details")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1 + (title.hashCode() and 0xffff), n) }
    }

    // ---- Keep the CPU awake while the screen is off ------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "memc:export")
            .apply { acquire(6 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        transformer?.let { runCatching { it.cancel() } }
        transformer = null
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "com.jainz.memc.export.CANCEL"
        private const val CHANNEL = "exports"
        private const val NOTIFICATION_ID = 4242
    }
}
