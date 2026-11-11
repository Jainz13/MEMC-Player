package com.jainz.memc.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import android.provider.Settings
import android.view.Surface
import android.view.SurfaceView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import com.jainz.memc.export.ExportManager
import com.jainz.memc.memc.MemcControl
import com.jainz.memc.memc.MemcEffect
import com.jainz.memc.memc.NpuFlow
import com.jainz.memc.memc.PictureControl
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.jainz.memc.MemcApp
import com.jainz.memc.data.db.VideoEntity
import com.jainz.memc.data.scan.NameParser
import com.jainz.memc.ui.Format
import com.jainz.memc.ui.theme.MemcTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class PlayerActivity : ComponentActivity() {

    private val g get() = MemcApp.graph
    private lateinit var player: ExoPlayer
    private lateinit var state: PlayerState
    private var saver: Job? = null
    private var inPip by mutableStateOf(false)
    private var resumeNotice by mutableStateOf<Long?>(null)
    /** Video ids for each playlist entry (null for files outside the library). */
    private var playlistIds: List<Long?> = emptyList()
    private val techSaved = HashSet<Long>()

    // ---- MEMC ----
    private lateinit var memc: MemcControl
    private lateinit var picture: PictureControl
    private var memcInstalled = false
    private var npuLive: NpuFlow? = null
    private var npuStarting = false
    private var playerView: PlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 30) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        hideSystemBars()

        // A previous attempt showed this video can't go through the effect pipeline: play it plain.
        val memcAllowed = !intent.getBooleanExtra(EXTRA_NO_MEMC, false)
        picture = PictureControl(
            // Without the effect pipeline nothing may be flagged as HDR, or SDR colours would be wrong.
            boost = if (memcAllowed) g.settings.hdrBoost.value / 100f else 0f,
            autoHdr = g.settings.autoHdr.value && memcAllowed && !intent.getBooleanExtra(EXTRA_NO_AUTO_HDR, false),
        )
        player = PlayerFactory.create(this, g.smb, g.settings, picture)
        state = PlayerState(player)
        memc = MemcControl(
            enabled = g.settings.memcEnabled.value && memcAllowed,
            targetFps = g.settings.memcTargetFps.value,
            quality = g.settings.memcQuality.value,
        )
        // Frames are processed no larger than the screen: a 4K film on this panel is shown at
        // the panel's resolution anyway, and it cuts the GPU work by more than half.
        memc.maxLongSide = screenLongSide()
        startNpuIfWanted()
        // Only build the effect pipeline when something needs it, so plain playback keeps the
        // direct HDR10+ path to the display.
        if (memcAllowed && (memc.enabled || picture.boost > 0f || picture.autoHdr)) installMemc()
        applyBoostBacklight()
        player.addAnalyticsListener(object : AnalyticsListener {
            // The player reports every 50 late frames it had to drop. Faster than ~4 a second
            // means the phone isn't keeping up: ask the engine to ease its workload.
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                com.jainz.memc.memc.PerfStats.dropped.addAndGet(droppedFrames.toLong())
                if (memcInstalled && droppedFrames * 1000L / elapsedMs.coerceAtLeast(1) >= 4) memc.requestEase()
            }
        })
        player.addListener(object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                memc.playbackSpeed = playbackParameters.speed
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    saveProgress()
                }
                updatePipParams()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    // The previous item played to the end.
                    val prev = player.previousMediaItemIndex
                    playlistIds.getOrNull(prev)?.let { id -> lifecycleScope.launch { g.db.videos().setWatched(id, true) } }
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                recordTech()
            }

            // The video format is often still unknown when tracks change; the first frame is certain.
            override fun onRenderedFirstFrame() {
                recordTech()
                checkAutoHdrPipeline()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // "Next episode" or a playlist jump: remember where the previous episode was left.
                if (reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
                    val id = playlistIds.getOrNull(oldPosition.mediaItemIndex) ?: return
                    saveProgressFor(id, oldPosition.positionMs)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val pipelineFailed = error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
                if (memcInstalled && pipelineFailed) {
                    // Fall back to normal playback at the same spot instead of showing an error.
                    saveProgress()
                    intent.putExtra(EXTRA_NO_MEMC, true)
                    intent.putExtra(EXTRA_START_MS, player.currentPosition)
                    Toast.makeText(this@PlayerActivity, "Smooth motion isn't available for this video - playing normally", Toast.LENGTH_LONG).show()
                    recreate()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    saveProgress(finished = true)
                    if (!player.hasNextMediaItem()) finish()
                }
            }
        })

        setContent {
            MemcTheme {
                PlayerScreen(
                    state = state,
                    inPip = inPip,
                    resumedAt = resumeNotice,
                    seekStepMs = g.settings.seekStepSec.value * 1000L,
                    subtitleScale = g.settings.subtitleScale.value / 100f,
                    defaultZoom = g.settings.defaultZoom.value,
                    onSubtitleScaleChange = { g.settings.subtitleScale.set((it * 100).toInt()) },
                    onStartOver = {
                        player.seekTo(0)
                        resumeNotice = null
                    },
                    onDismissResume = { resumeNotice = null },
                    onBack = { finish() },
                    onPip = { enterPip() },
                    memc = memc,
                    memcAvailable = memcAllowed,
                    onMemcChange = { enabled, fps, quality -> updateMemc(enabled, fps, quality) },
                    picture = picture,
                    onPictureChange = { boost, autoHdr -> updatePicture(boost, autoHdr) },
                    onExport = { exportCurrent() },
                    onPlayerView = { view ->
                        playerView = view
                        applyDisplayRate()
                    },
                )
            }
        }

        load(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveProgress()
        load(intent)
    }

    private fun load(intent: Intent) {
        lifecycleScope.launch {
            val fromStart = intent.getBooleanExtra(EXTRA_FROM_START, false)
            val videoId = intent.getLongExtra(EXTRA_VIDEO_ID, -1L)
            val explicitUri = intent.getStringExtra(EXTRA_URI) ?: intent.data?.toString()
            val explicitTitle = intent.getStringExtra(EXTRA_TITLE)

            val start: VideoEntity? = when {
                videoId > 0 -> g.db.videos().get(videoId)
                explicitUri != null -> g.db.videos().byUri(explicitUri)
                else -> null
            }

            val entries: List<Pair<Long?, MediaItem>> = withContext(Dispatchers.IO) {
                if (start != null) {
                    val list = mutableListOf(start)
                    if (start.showId != null && g.settings.autoPlayNext.value) {
                        val all = g.db.videos().allEpisodes(start.showId).map { it.video }
                        val idx = all.indexOfFirst { it.id == start.id }
                        if (idx >= 0) list.addAll(all.drop(idx + 1))
                    }
                    list.map { v -> v.id to mediaItemFor(v) }
                } else if (explicitUri != null) {
                    listOf(null to buildItem(explicitUri, explicitTitle ?: titleFromUri(explicitUri), null, null))
                } else {
                    emptyList()
                }
            }
            if (entries.isEmpty()) {
                finish()
                return@launch
            }

            playlistIds = entries.map { it.first }
            val explicitStart = intent.getLongExtra(EXTRA_START_MS, -1L)
            intent.removeExtra(EXTRA_START_MS)
            val resumeAt = if (explicitStart >= 0) explicitStart
            else start?.takeIf { !fromStart && !it.watched && it.positionMs > 30_000 }?.positionMs ?: 0L
            resumeNotice = if (resumeAt > 0) resumeAt else null

            player.setMediaItems(entries.map { it.second }, 0, resumeAt)
            player.prepare()
            player.play()
            startSaver()
        }
    }

    private suspend fun mediaItemFor(v: VideoEntity): MediaItem {
        val card = g.db.videos().card(v.id)
        val title = card?.title ?: v.parsedTitle
        val sub = when {
            v.season != null -> listOfNotNull(Format.episodeCode(v.season, v.episode), card?.epName).joinToString(" · ")
            v.movieId == null -> v.fileName
            else -> null
        }
        return buildItem(v.uri, title, sub, v.id)
    }

    private fun buildItem(uri: String, title: String, subtitle: String?, id: Long?): MediaItem {
        val subs = when {
            uri.startsWith("smb://") -> sideSubtitlesSmb(uri)
            uri.startsWith("file://") -> sideSubtitlesLocal(uri)
            else -> emptyList()
        }
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(id?.toString() ?: uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).build())
            .setSubtitleConfigurations(subs)
            .build()
    }

    /** Finds Movie.srt / Movie.en.srt / Movie.ass next to the video on the share. */
    private fun sideSubtitlesSmb(uri: String): List<MediaItem.SubtitleConfiguration> = try {
        val loc = g.smb.resolve(Uri.parse(uri))
        val dir = loc.path.substringBeforeLast('/', "")
        val base = loc.path.substringAfterLast('/').substringBeforeLast('.')
        g.smb.list(loc.copy(path = dir))
            .filter { !it.isDir && NameParser.isSubtitle(it.name) && it.name.startsWith(base, ignoreCase = true) }
            .map { e ->
                val subUri = Uri.parse(uri).buildUpon().path(null).apply {
                    appendPath(loc.share)
                    e.path.split('/').filter { it.isNotEmpty() }.forEach { appendPath(it) }
                }.build()
                subtitleConfig(subUri, e.name, base)
            }
    } catch (e: Exception) {
        emptyList()
    }

    /** Same for videos on the phone (needs "All files" access to see non-media files). */
    private fun sideSubtitlesLocal(uri: String): List<MediaItem.SubtitleConfiguration> = try {
        val file = java.io.File(Uri.parse(uri).path ?: "")
        val base = file.nameWithoutExtension
        (file.parentFile?.listFiles() ?: emptyArray())
            .filter { it.isFile && NameParser.isSubtitle(it.name) && it.name.startsWith(base, ignoreCase = true) }
            .map { subtitleConfig(Uri.fromFile(it), it.name, base) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun subtitleConfig(subUri: Uri, name: String, base: String): MediaItem.SubtitleConfiguration {
        val ext = NameParser.extension(name)
        val middle = name.substring(base.length).removeSuffix(".$ext").trim('.', ' ', '_', '-')
        return MediaItem.SubtitleConfiguration.Builder(subUri)
            .setMimeType(
                when (ext) {
                    "ass", "ssa" -> MimeTypes.TEXT_SSA
                    "vtt" -> MimeTypes.TEXT_VTT
                    "ttml" -> MimeTypes.APPLICATION_TTML
                    else -> MimeTypes.APPLICATION_SUBRIP
                }
            )
            .setLanguage(middle.substringBefore('.').lowercase().takeIf { it.length in 2..3 })
            .setLabel(middle.ifBlank { "External" })
            .build()
    }

    private fun titleFromUri(uri: String): String =
        Uri.parse(uri).lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Video"

    // ---- Progress ---------------------------------------------------------------

    private fun currentId(): Long? = playlistIds.getOrNull(player.currentMediaItemIndex)

    private fun startSaver() {
        saver?.cancel()
        saver = lifecycleScope.launch {
            while (isActive) {
                delay(5_000)
                if (player.isPlaying) saveProgress()
            }
        }
    }

    private fun saveProgress(finished: Boolean = false) {
        val id = currentId() ?: return
        val dur = player.duration.takeIf { it > 0 } ?: return
        val pos = player.currentPosition
        val watched = finished || pos >= dur * 0.93
        // App-wide scope so the final save still runs while the activity is being destroyed.
        g.scope.launch {
            g.db.videos().savePlayback(id, if (watched) 0 else pos, dur, System.currentTimeMillis(), watched)
        }
    }

    /** Saves a position for an item that is no longer current, using the duration stored for it. */
    private fun saveProgressFor(id: Long, pos: Long) {
        g.scope.launch {
            val v = g.db.videos().get(id) ?: return@launch
            val dur = v.durationMs
            if (dur <= 0) return@launch
            // Skipping on during the credits counts as watched.
            val watched = pos >= dur * 0.90
            g.db.videos().savePlayback(id, if (watched) 0 else pos, dur, System.currentTimeMillis(), watched)
        }
    }

    private fun recordTech() {
        val id = currentId() ?: return
        val video = player.videoFormat ?: return
        if (!techSaved.add(id)) return
        // The file's full track list (from MediaProbe) is more complete than the one playing track.
        if (MediaProbe.cached(this, id) != null) return
        lifecycleScope.launch {
            val v = g.db.videos().get(id) ?: return@launch
            val tags = PlayerState.techTags(video, player.audioFormat, v.tech)
            if (tags.isNotBlank() && tags != v.tech) g.db.videos().setTech(id, tags)
        }
    }

    // ---- MEMC -----------------------------------------------------------------------------

    /**
     * One effect does picture adjustments and frame interpolation. Media3 can only create the
     * effect pipeline when playback starts, so this must run before the video is prepared.
     */
    private fun installMemc() {
        if (memcInstalled) return
        player.setVideoEffects(listOf(MemcEffect(memc, picture)))
        memcInstalled = true
        applyDisplayRate()
    }

    /**
     * "AI" quality: load RIFE on the NPU in the background (the first time takes up to a minute
     * while the model is prepared for this chip). The engine switches to it when it is ready
     * and keeps using the GPU engine until then, or if it fails.
     */
    private fun startNpuIfWanted() {
        if (memc.quality != 3 || !memc.enabled || npuLive != null || npuStarting) return
        if (!NpuFlow.hasModel(this, NpuFlow.LIVE)) {
            memc.npuStatus = "AI model isn't in this build - using the GPU engine"
            return
        }
        npuStarting = true
        memc.npuStatus = "AI model starting (first time can take a minute)…"
        val appContext = applicationContext
        g.scope.launch {
            val result = runCatching { NpuFlow.create(appContext, NpuFlow.LIVE) }
            withContext(Dispatchers.Main) {
                npuStarting = false
                result.onSuccess { n ->
                    if (isDestroyed) {
                        n.close()
                    } else {
                        npuLive = n
                        memc.npu = n
                        memc.npuStatus = "AI on the NPU"
                    }
                }.onFailure { e ->
                    memc.npuStatus = "AI unavailable (${(e.message ?: e.javaClass.simpleName).take(120)}) - using the GPU engine"
                }
            }
        }
    }

    /** Restarts playback at the same position, for changes that need the pipeline rebuilt. */
    private fun restartAtPosition() {
        saveProgress()
        intent.putExtra(EXTRA_START_MS, player.currentPosition)
        recreate()
    }

    private fun updatePicture(boost: Float, autoHdr: Boolean) {
        g.settings.hdrBoost.set((boost * 100).toInt())
        g.settings.autoHdr.set(autoHdr)
        picture.boost = boost
        picture.autoHdr = autoHdr
        applyBoostBacklight()
        if (autoHdr) intent.removeExtra(EXTRA_NO_AUTO_HDR)
        val transfer = player.videoFormat?.colorInfo?.colorTransfer
        val videoIsSdr = picture.fakedHdr ||
            (transfer != C.COLOR_TRANSFER_ST2084 && transfer != C.COLOR_TRANSFER_HLG)
        val needsPipeline = boost > 0f || autoHdr
        when {
            // Auto HDR re-reads the video with different colour flags.
            videoIsSdr && autoHdr != picture.convertSdr -> restartAtPosition()
            needsPipeline && !memcInstalled -> restartAtPosition()
        }
    }

    private fun updateMemc(enabled: Boolean, fps: Int, quality: Int) {
        g.settings.memcEnabled.set(enabled)
        g.settings.memcTargetFps.set(fps)
        g.settings.memcQuality.set(quality)
        memc.targetFps = fps
        memc.quality = quality
        memc.enabled = enabled
        startNpuIfWanted()
        if (enabled && !memcInstalled) {
            restartAtPosition()
            return
        }
        applyDisplayRate()
    }

    /**
     * Boost also raises the screen backlight towards maximum, the one brightness gain that
     * costs no picture quality. It never dims below the phone's current brightness.
     */
    private fun applyBoostBacklight() {
        val boost = picture.boost
        if (boost <= 0f) return
        val lp = window.attributes
        val current = if (lp.screenBrightness in 0f..1f) lp.screenBrightness else runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f).coerceIn(0f, 1f)
        lp.screenBrightness = (current + (1f - current) * boost).coerceIn(0.01f, 1f)
        window.attributes = lp
    }

    private fun screenLongSide(): Int {
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
            windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }
        return maxOf(w, h).coerceAtLeast(1280)
    }

    /**
     * Auto HDR needs the pipeline to run in HDR. If this phone gave it an SDR pipeline instead,
     * the picture would be wrong: turn Auto HDR off for this video and restart.
     */
    fun checkAutoHdrPipeline() {
        if (picture.fakedHdr && picture.pipelineHdr == false && !isFinishing) {
            intent.putExtra(EXTRA_NO_AUTO_HDR, true)
            Toast.makeText(this, "Auto HDR isn't supported for this video - playing normally", Toast.LENGTH_LONG).show()
            restartAtPosition()
        }
    }

    /**
     * With MEMC on, the screen must run at the output rate (60/120 Hz); otherwise the player
     * would pick a refresh rate for the original 24 fps and drop the new frames.
     */
    private fun applyDisplayRate() {
        val memcOn = memcInstalled && memc.enabled
        player.setVideoChangeFrameRateStrategy(
            when {
                memcOn -> C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
                g.settings.matchFrameRate.value -> C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
                else -> C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            }
        )
        if (Build.VERSION.SDK_INT >= 30) {
            val surface = (playerView?.videoSurfaceView as? SurfaceView)?.holder?.surface
            if (surface != null && surface.isValid) {
                runCatching {
                    surface.setFrameRate(
                        if (memcOn) memc.targetFps.toFloat() else 0f,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    )
                }
            }
        }
    }

    private fun exportCurrent() {
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri?.toString() ?: return
        val title = item.mediaMetadata.title?.toString() ?: titleFromUri(uri)
        val sub = item.mediaMetadata.subtitle?.toString()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        ExportManager.enqueue(this, uri, if (sub != null && sub.contains("S") && sub.contains("E")) "$title $sub" else title)
        Toast.makeText(this, "Added to exports - you can keep watching", Toast.LENGTH_SHORT).show()
    }

    // ---- Window / PiP ---------------------------------------------------------------

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun pipParams(): PictureInPictureParams {
        val size = player.videoSize
        val ratio = if (size.width > 0 && size.height > 0) {
            val r = size.width.toFloat() / size.height
            when {
                r > 2.39f -> Rational(239, 100)
                r < 0.42f -> Rational(42, 100)
                else -> Rational(size.width, size.height)
            }
        } else Rational(16, 9)
        val b = PictureInPictureParams.Builder().setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(player.isPlaying)
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    private fun updatePipParams() {
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun enterPip() {
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        // Closing the PiP window stops the activity; without this the audio kept playing in the background.
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            saveProgress()
            player.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        if (!isInPictureInPictureMode) player.pause()
    }

    override fun onDestroy() {
        saveProgress()
        saver?.cancel()
        state.release()
        player.release()
        // Close the AI model a moment later, once the render thread has certainly stopped using it.
        memc.npu = null
        npuLive?.let { n ->
            npuLive = null
            g.scope.launch {
                delay(2500)
                n.close()
            }
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FROM_START = "from_start"
        const val EXTRA_NO_MEMC = "no_memc"
        const val EXTRA_START_MS = "start_ms"
        const val EXTRA_NO_AUTO_HDR = "no_auto_hdr"
    }
}
