package com.jainz.memc.export

import com.jainz.memc.memc.PerfStats
import com.jainz.memc.core.DebugRecorder
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
    private var speedAt = 0L
    private var speedFrames = 0L
    private var speedText: String? = null
    /** Output frames counter at the start of this export, and how many the whole video gives. */
    private var framesBase = 0L
    private var framesTotal = 0L
    private var lastFps = 0.0
    private var lastStatsAt = 0L
    private var lastFrames = 0L
    private var lastFramesAt = 0L
    private var heatListener: Any? = null
    private var exportControl: MemcControl? = null

    // ---- 3D export: 1 = depth pass (AI on the NPU), 2 = 3D render; 0 = 60 fps export ----
    private var phase = 0
    private var prepJob: kotlinx.coroutines.Job? = null
    private var vda: com.jainz.memc.stereo.VdaDepth? = null
    private var depthFile: File? = null
    private var depthProgress: com.jainz.memc.stereo.DepthPassEffect.Progress? = null
    private var stereoControl: com.jainz.memc.stereo.StereoControl? = null
    /** Frames counter for the speed readout of a 3D export, and its value when the phase began. */
    private fun frameCount(): Long = when (phase) {
        1 -> depthProgress?.framesIn?.get() ?: 0L
        2 -> stereoControl?.framesDone?.get() ?: 0L
        else -> PerfStats.framesOut.get()
    }
    private val busy: Boolean get() = transformer != null || prepJob?.isActive == true

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
            } else if (!busy) {
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
        cleanUp3d()
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
            speedAt = 0L
            speedText = null
            lastFps = 0.0
            lastStatsAt = 0L
            phase = 0
            framesBase = PerfStats.framesOut.get()
            framesTotal = 0L
            PerfStats.reset()
            PerfStats.sample(this)
            DebugRecorder.event("export", "Export started: ${it.title}")
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

    /** Same colour fix as playback, so HDR files missing container colour info export as HDR10. */
    private fun assetLoader(): DefaultAssetLoaderFactory {
        val extractors = ColorFixExtractorsFactory(
            DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
        )
        val mediaSources = DefaultMediaSourceFactory(RoutingDataSource.Factory(this, MemcApp.graph.smb), extractors)
        return DefaultAssetLoaderFactory(
            this,
            DefaultDecoderFactory.Builder(this).build(),
            Clock.DEFAULT,
            mediaSources,
            DataSourceBitmapLoader(this),
        )
    }

    // ---- 3D export ------------------------------------------------------------------------------
    //
    // Two passes: first Video Depth Anything on the NPU turns the video into a small depth
    // video (it needs 32 frames at a time, so it can't run alongside the picture), then the
    // 3D renderer reads that depth frame by frame and writes the side-by-side file.

    private fun begin3d(job: ExportJob) {
        phase = 1
        val app = applicationContext
        ExportManager.update(job.id) { it.copy(message = "Getting the AI 3D model ready…") }
        prepJob = MemcApp.graph.scope.launch {
            try {
                if (!com.jainz.memc.stereo.VdaModel.ready(app)) {
                    var shown = -1
                    com.jainz.memc.stereo.VdaModel.download(app) { f ->
                        val pct = (f * 100).toInt()
                        if (pct != shown) {
                            shown = pct
                            handler.post {
                                ExportManager.update(job.id) { it.copy(message = "Downloading the AI 3D model (one time, about 115 MB)… $pct%") }
                            }
                        }
                    }
                }
                val model = withContext(Dispatchers.Default) {
                    com.jainz.memc.stereo.VdaDepth.create(app) { step -> handler.post { ExportManager.update(job.id) { it.copy(message = step) } } }
                }
                withContext(Dispatchers.Main) {
                    if (currentId != job.id || ExportManager.get(job.id)?.status != ExportStatus.RUNNING) {
                        model.close()
                    } else {
                        vda = model
                        prepJob = null
                        startDepthPass(job, model)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    prepJob = null
                    if (currentId == job.id) {
                        DebugRecorder.event("export", "3D export couldn't start: $e")
                        failCurrent("3D export: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    private fun startDepthPass(job: ExportJob, model: com.jainz.memc.stereo.VdaDepth) {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: cacheDir
        val depth = File(dir, "export-${job.id}-depth.mp4").also { it.delete() }
        depthFile = depth
        val prog = com.jainz.memc.stereo.DepthPassEffect.Progress()
        depthProgress = prog
        speedAt = 0L
        lastFps = 0.0
        framesBase = 0L
        lastFrames = 0L
        lastFramesAt = 0L
        framesTotal = 0L
        lengthFrames(job)
        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(this)
                    .setEnableFallback(true)
                    .setRequestedVideoEncoderSettings(androidx.media3.transformer.VideoEncoderSettings.Builder().setBitrate(6_000_000).build())
                    .build()
            )
            .setAssetLoaderFactory(assetLoader())
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    try {
                        val cuts = prog.cuts
                        DebugRecorder.event("export", "3D depth pass done: ${exportResult.videoFrameCount} frames, $cuts scene cuts, enc ${model.encMs.toInt()} ms/frame, window ${model.windowMs.toInt()} ms")
                        stats = "depth: ${prog.framesIn.get()} frames, $cuts scene cuts"
                        vda?.close()
                        vda = null
                        transformer = null
                        startRenderPass(job)
                    } catch (e: Exception) {
                        failCurrent("3D export: ${e.message ?: e.javaClass.simpleName}")
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    failCurrent("3D depth pass failed - ${exportException.errorCodeName}: ${exportException.message}")
                }
            })
            .build()
        val item = EditedMediaItem.Builder(MediaItem.fromUri(job.sourceUri))
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), listOf(com.jainz.memc.stereo.DepthPassEffect(model, prog))))
            .build()
        // The model was trained on ordinary (SDR) video: depth is worked out from a tone-mapped copy.
        val composition = Composition.Builder(androidx.media3.transformer.EditedMediaItemSequence.withVideoFrom(listOf(item)))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        transformer = t
        try {
            t.start(composition, depth.absolutePath)
        } catch (e: Exception) {
            transformer = null
            failCurrent("3D export: ${e.message ?: "could not start"}")
            return
        }
        pollProgress()
    }

    private fun startRenderPass(job: ExportJob) {
        val td = job.threeD ?: return failCurrent("3D export: no settings")
        phase = 2
        handler.removeCallbacksAndMessages(null)
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: cacheDir
        val out = File(dir, "export-${job.id}.mp4").also { it.delete() }
        tempFile = out
        val control = com.jainz.memc.stereo.StereoControl(com.jainz.memc.stereo.StereoControl.CONVERT).apply {
            strength = td.strength
            convergence = td.convergence
            swapEyes = td.swapEyes
            depthVideo = depthFile
            halfSbsOutput = td.halfSbs
        }
        stereoControl = control
        speedAt = 0L
        lastFps = 0.0
        framesBase = 0L
        lastFrames = 0L
        lastFramesAt = 0L
        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setEncoderFactory(DefaultEncoderFactory.Builder(this).setEnableFallback(true).build())
            .setAssetLoaderFactory(assetLoader())
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    try {
                        val started = ExportManager.get(job.id)?.startedAt ?: 0L
                        val seconds = ((System.currentTimeMillis() - started) / 1000.0).coerceAtLeast(1.0)
                        stats = String.format(
                            java.util.Locale.US, "%d frames in %.0f min · %s · encoder %s",
                            exportResult.videoFrameCount, seconds / 60, stats ?: "", exportResult.videoEncoderName ?: "unknown",
                        )
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
            .setEffects(Effects(emptyList(), listOf(com.jainz.memc.stereo.StereoEffect(control))))
            .build()
        transformer = t
        try {
            t.start(item, out.absolutePath)
        } catch (e: Exception) {
            transformer = null
            failCurrent("3D export: ${e.message ?: "could not start"}")
            return
        }
        pollProgress()
    }

    /** Frames in the video, for the time-left readout (frame rate from the file itself). */
    private fun lengthFrames(job: ExportJob) {
        val app = applicationContext
        MemcApp.graph.scope.launch {
            val total = runCatching {
                val r = android.media.MediaMetadataRetriever()
                try {
                    val uri = android.net.Uri.parse(job.sourceUri)
                    if (uri.scheme == "content" || uri.scheme == "file" || uri.scheme == null) r.setDataSource(app, uri) else return@runCatching 0L
                    val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val count = if (Build.VERSION.SDK_INT >= 28) {
                        r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
                    } else null
                    count ?: (ms * 24 / 1000)
                } finally {
                    runCatching { r.release() }
                }
            }.getOrNull() ?: 0L
            if (total > 0) handler.post { if (currentId == job.id) framesTotal = total }
        }
    }

    private fun cleanUp3d() {
        prepJob?.cancel()
        prepJob = null
        vda?.let { runCatching { it.close() } }
        vda = null
        depthFile?.delete()
        depthFile = null
        depthProgress = null
        stereoControl = null
    }

    private fun beginUnsafe(job: ExportJob) {
        if (job.threeD != null) return begin3d(job)
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
        com.jainz.memc.core.Compat.unwatchHeat(this, heatListener)
        control.thermalLevel = minOf(com.jainz.memc.core.Compat.thermalStatus(this), 2)
        heatListener = com.jainz.memc.core.Compat.watchHeat(this) { control.thermalLevel = minOf(it, 2) }
        // Length of the video, for frames-based progress (Transformer's % moves very slowly).
        g.scope.launch {
            val d = runCatching { g.db.videos().byUri(job.sourceUri)?.durationMs }.getOrNull() ?: 0L
            if (d > 0 && currentId == job.id) framesTotal = d * 60 / 1000
        }
        val effect = MemcEffect(control)
        val assetLoader = assetLoader()
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
                    // Live speed and time left, from the frames the engine has produced.
                    val now = System.currentTimeMillis()
                    val frames = frameCount()
                    if (speedAt == 0L) {
                        speedAt = now
                        speedFrames = frames
                    } else if (now - speedAt >= 10_000) {
                        val fps = (frames - speedFrames) * 1000.0 / (now - speedAt)
                        speedAt = now
                        speedFrames = frames
                        val started = ExportManager.get(currentId)?.startedAt ?: now
                        val left = if (p > 0) (now - started) / 60_000.0 * (100 - p) / p else -1.0
                        speedText = String.format(
                            java.util.Locale.US, "%.1f frames a second%s", fps,
                            if (left >= 0) String.format(java.util.Locale.US, " · about %.0f min left", left) else "",
                        )
                        DebugRecorder.event(
                            "export",
                            "$p% · $speedText · heat=${exportControl?.thermalLevel} · ${exportControl?.npuStatus}",
                        )
                    }
                    if (now - lastStatsAt >= 2000) {
                        lastStatsAt = now
                        val sample = PerfStats.sample(this@ExportService)
                        val done = frames - framesBase
                        val started = ExportManager.get(currentId)?.startedAt ?: now
                        val elapsed = now - started
                        val fps = if (phase != 0) {
                            // 3D: frames per second from this phase's own counter.
                            val prevFrames = lastFrames
                            val prevAt = lastFramesAt
                            lastFrames = frames
                            lastFramesAt = now
                            val f = if (prevAt > 0 && now > prevAt) (frames - prevFrames) * 1000.0 / (now - prevAt) else 0.0
                            if (lastFps <= 0) f else lastFps * 0.7 + f * 0.3
                        } else if (sample != null && sample.fps > 0) {
                            // Smoothed over the last samples so the figure doesn't jump around.
                            if (lastFps <= 0) sample.fps.toDouble() else lastFps * 0.7 + sample.fps * 0.3
                        } else lastFps
                        lastFps = fps
                        val left = when {
                            framesTotal > 0 && fps > 0.5 -> ((framesTotal - done).coerceAtLeast(0) / fps * 1000).toLong()
                            p in 1..99 -> elapsed * (100 - p) / p
                            else -> -1L
                        }
                        val temps = com.jainz.memc.core.SystemTemps.read(this@ExportService)
                        val npuStatus = exportControl?.npuStatus.orEmpty()
                        val stats = ExportStats(
                            fps = fps, framesDone = done, framesTotal = framesTotal, elapsedMs = elapsed, leftMs = left,
                            cpuPercent = sample?.cpuPercent ?: 0, gpuPercent = sample?.gpuPercent ?: -1,
                            npuPercent = sample?.npuPercent ?: 0, npuMsPerPass = sample?.npuMsPerPass ?: 0,
                            engine = when (phase) {
                                1 -> "3D step 1 of 2: AI depth on the NPU (Video Depth Anything" +
                                    (vda?.let { v -> if (v.encMs > 0) ", ${v.encMs.toInt()} ms a frame + ${v.windowMs.toInt()} ms per 22, temporal part on the ${com.jainz.memc.stereo.VdaDepth.tmpWhere}" else "" } ?: "") + ")"
                                2 -> "3D step 2 of 2: rendering ${ExportManager.get(currentId)?.kindLabel ?: "3D"} on the GPU"
                                else -> if (npuStatus.startsWith("AI on")) "AI on the NPU" else if (npuStatus.isNotBlank()) "GPU engine ($npuStatus)" else "GPU engine"
                            },
                            temps = temps,
                        )
                        ExportManager.update(currentId) { it.copy(stats = stats) }
                        if (now - speedAt < 2500) {
                            DebugRecorder.event("export", "temps: ${temps.summary()} · CPU ${stats.cpuPercent}% GPU ${stats.gpuPercent}% NPU ${stats.npuPercent}%")
                        }
                    }
                    val frac = ExportManager.get(currentId)?.stats?.let { s ->
                        if (s.framesTotal > 0) (s.framesDone * 1000 / s.framesTotal).toInt().coerceAtMost(1000) else -1
                    } ?: -1
                    val phaseP = if (frac >= 0) maxOf(p, frac / 10) else p
                    // A 3D export is two passes: the AI depth pass takes most of the time.
                    val overall = when (phase) {
                        1 -> phaseP * 80 / 100
                        2 -> 80 + phaseP * 20 / 100
                        else -> phaseP
                    }
                    ExportManager.update(currentId) { it.copy(progress = overall, message = speedText) }
                    val title = ExportManager.get(currentId)?.title ?: "video"
                    val st = ExportManager.get(currentId)?.stats
                    runCatching {
                        getSystemService(NotificationManager::class.java)
                            .notify(
                                NOTIFICATION_ID,
                                progressNotification(
                                    (if (phase != 0) "Exporting $title in 3D (step $phase of 2)" else "Exporting $title at 60 fps") +
                                        (st?.let { " · %.1f fps".format(java.util.Locale.US, it.fps) } ?: ""),
                                    overall,
                                    st?.temps?.summary(),
                                ),
                            )
                    }
                }
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun onFinished(job: ExportJob, out: File) {
        transformer = null
        handler.removeCallbacksAndMessages(null)
        cleanUp3d()
        ExportManager.update(job.id) { it.copy(progress = 100, message = "Saving to Movies…") }
        MemcApp.graph.scope.launch {
            val result = runCatching { publish(out, job.title, job.threeD) }
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
            // Try once more with the video only (a 3D export keeps its finished depth pass).
            withoutAudio = true
            ExportManager.update(job.id) { it.copy(message = "Retrying without sound…") }
            if (job.threeD != null && phase == 2 && depthFile?.exists() == true) startRenderPass(job) else begin(job)
            return
        }
        cleanUp3d()
        ExportManager.update(job.id) {
            it.copy(status = ExportStatus.FAILED, message = "${e.errorCodeName}: ${e.message ?: "export failed"}")
        }
        notifyDone(job.title, ok = false)
        startNext()
    }

    private fun cancelCurrent() {
        val t = transformer
        if (t == null && prepJob?.isActive != true) return
        transformer = null
        handler.removeCallbacksAndMessages(null)
        t?.let { runCatching { it.cancel() } }
        tempFile?.delete()
        cleanUp3d()
        ExportManager.update(currentId) { it.copy(status = ExportStatus.CANCELLED, message = null) }
        startNext()
    }

    // ---- Saving -------------------------------------------------------------------------------

    /** Copies the finished file into Movies/MEMC Player and returns its content:// address. */
    private suspend fun publish(file: File, title: String, threeD: ThreeDExport?): String = withContext(Dispatchers.IO) {
        val safe = title.replace(Regex("""[\\/:*?"<>|]"""), " ").trim().ifBlank { "Video" }
        // "Full-SBS" / "Half-SBS" in the name: players (and this app) switch to 3D from it.
        val suffix = threeD?.let { if (it.halfSbs) " 3D Half-SBS" else " 3D Full-SBS" } ?: " 60fps"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$safe$suffix.mp4")
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

    private fun channel(): String = com.jainz.memc.core.Compat.channel(this, CHANNEL, "Exports")

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progressNotification(text: String, percent: Int, detail: String? = null) = NotificationCompat.Builder(this, channel())
        .setSmallIcon(R.drawable.ic_stat_memc)
        .setContentTitle("MEMC Player")
        .setContentText(text)
        .apply { if (!detail.isNullOrBlank()) setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$detail")) }
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
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, type) else startForeground(NOTIFICATION_ID, n)
    }

    private fun notifyDone(title: String, ok: Boolean) {
        val n = NotificationCompat.Builder(this, channel())
            .setSmallIcon(R.drawable.ic_stat_memc)
            .setContentTitle(if (ok) "Export finished" else "Export failed")
            .setContentText(if (ok) "$title - new copy saved to Movies/MEMC Player" else "$title - open the app for details")
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
        cleanUp3d()
        releaseWakeLock()
        com.jainz.memc.core.Compat.unwatchHeat(this, heatListener)
        heatListener = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "com.jainz.memc.export.CANCEL"
        private const val CHANNEL = "exports"
        private const val NOTIFICATION_ID = 4242
    }
}
