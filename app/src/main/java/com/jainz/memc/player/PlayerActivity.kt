package com.jainz.memc.player

import com.jainz.memc.memc.PerfStats
import java.lang.ref.WeakReference
import com.jainz.memc.core.DebugRecorder
import com.jainz.memc.BuildConfig
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.PowerManager
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
import com.jainz.memc.stereo.DepthModel
import com.jainz.memc.stereo.NpuDepth
import com.jainz.memc.stereo.StereoControl
import com.jainz.memc.stereo.StereoEffect
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
        // The TV variant has no effect pipeline at all (no 60 fps, no picture processing).
        val memcAllowed = !BuildConfig.TV && !intent.getBooleanExtra(EXTRA_NO_MEMC, false)
        // HDR output chosen in the player for this session, else the Settings default.
        hdrMode = intent.getIntExtra(EXTRA_HDR_MODE, g.settings.hdrMode.value)
        if (hdrMode == HdrMode.SDR && !HdrMode.sdrSupported) hdrMode = HdrMode.HDR10
        // 3D for this session (picked in the player): smooth motion off, Auto HDR on.
        stereoMode = intent.getIntExtra(EXTRA_STEREO, StereoControl.OFF)
        val threeDOn = stereoMode != StereoControl.OFF
        picture = PictureControl(
            // Without the effect pipeline nothing may be flagged as HDR, or SDR colours would be wrong.
            boost = if (memcAllowed) g.settings.hdrBoost.value / 100f else 0f,
            autoHdr = (if (threeDOn) g.settings.stereoAutoHdr.value else g.settings.autoHdr.value && memcAllowed) && hdrMode != HdrMode.SDR &&
                !intent.getBooleanExtra(EXTRA_NO_AUTO_HDR, false),
        )
        player = PlayerFactory.create(this, g.smb, g.settings, picture, hdrMode)
        state = PlayerState(player)
        memc = MemcControl(
            enabled = g.settings.memcEnabled.value && memcAllowed,
            targetFps = g.settings.memcTargetFps.value,
            quality = g.settings.memcQuality.value,
        )
        if (BuildConfig.TV) memc.enabled = false
        if (threeDOn) {
            memc.enabled = false
            stereo = StereoControl(stereoMode).apply {
                strength = g.settings.stereoStrength.value / 100f
                swapEyes = g.settings.stereoSwap.value
            }
        }
        threeD = ThreeDUi(
            mode = stereoMode,
            stereo = stereo,
            onMode = { changeStereo(it) },
            onStrength = { g.settings.stereoStrength.set((it * 100).toInt()) },
            onSwap = { g.settings.stereoSwap.set(it) },
            onScreenOff = { setScreenOff(it) },
            onDownloadModel = { downloadDepthModel() },
            autoHdr = g.settings.stereoAutoHdr.value,
            onAutoHdr = {
                g.settings.stereoAutoHdr.set(it)
                restartAtPosition()
            },
        ).apply {
            canUseAi = !BuildConfig.TV && NpuFlow.isSnapdragon
            modelState = if (!canUseAi || DepthModel.ready(this@PlayerActivity)) "" else "missing"
        }
        // Frames are processed no larger than the screen: a 4K film on this panel is shown at
        // the panel's resolution anyway, and it cuts the GPU work by more than half.
        memc.maxLongSide = screenLongSide()
        stereo?.let { s ->
            // 3D: fixed size, never eased. Each eye is at most 1920 wide, so a normal video only
            // needs 1920; a full side-by-side file keeps its whole width. (The load governor used
            // to shrink the picture to 1280x720 after a few dropped frames.)
            memc.adaptive = false
            memc.quality = 2
            memc.maxLongSide = if (s.mode == StereoControl.SBS) 3840 else 1920
        }
        watchHeat()
        startDebugRecording()
        startNpuIfWanted()
        // Only build the effect pipeline when something needs it, so plain playback keeps the
        // direct HDR10+ path to the display.
        if (stereo != null) {
            installStereo()
            startDepthIfWanted()
            // The AI depth model makes far better 3D than picture cues: fetch it on Wi-Fi.
            if (stereo?.mode == StereoControl.CONVERT && threeD.modelState == "missing" &&
                getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered == false
            ) {
                Toast.makeText(this, "Downloading AI 3D (about 100 MB, once) - picture-cue 3D until it's ready", Toast.LENGTH_LONG).show()
                downloadDepthModel()
            }
            if (!BuildConfig.TV) {
                glasses = GlassesOutput(this, onSurface = { moveVideoTo(it) }, onInfo = { threeD.glassesInfo = it }).also {
                    it.subtitleScale = g.settings.subtitleScale.value / 100f
                    it.start()
                }
                // Subtitles go to the glasses too (the phone's subtitle layer isn't shown there).
                player.addListener(object : Player.Listener {
                    override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                        glasses?.setCues(cueGroup.cues)
                    }
                })
            }
        } else if (memcAllowed && (memc.enabled || picture.boost > 0f || picture.autoHdr)) installMemc()
        applyBoostBacklight()
        player.addAnalyticsListener(object : AnalyticsListener {
            // The player reports every 50 late frames it had to drop. Faster than ~4 a second
            // means the phone isn't keeping up: ask the engine to ease its workload.
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                com.jainz.memc.memc.PerfStats.dropped.addAndGet(droppedFrames.toLong())
                DebugRecorder.event("player", "Dropped $droppedFrames frames in $elapsedMs ms")
                if (memcInstalled && stereo == null && droppedFrames * 1000L / elapsedMs.coerceAtLeast(1) >= 4) {
                    // First let the engine catch its breath and refill its buffer; only if it
                    // stutters again soon after is the workload eased.
                    if (!prebuffer(minGapMs = 20_000)) memc.requestEase()
                }
            }
        })
        player.addListener(object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                memc.playbackSpeed = playbackParameters.speed
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else {
                    if (!state.prebuffering && !threeD.blackout) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    saveProgress()
                    fillBufferWhilePaused()
                }
                updatePipParams()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                loadFileTech()
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
                HdrMode.applyToSurface(playerView?.videoSurfaceView as? SurfaceView, hdrMode)
                if (openedAt > 0) {
                    DebugRecorder.event("player", "First picture ${SystemClock.elapsedRealtime() - openedAt} ms after opening")
                    openedAt = 0
                }
                recordTech()
                checkAutoHdrPipeline()
                // Start of the video or the first picture after a seek: render ahead before rolling.
                if (!prebuffer(minGapMs = 1500)) fillBufferWhilePaused()
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
                // A Dolby Vision file without an HDR10 layer (profile 5) can't play on the base
                // layer: go back to the Dolby Vision decoder for it.
                val decoderProblem = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
                if (decoderProblem && !HdrMode.usesDolbyVision(hdrMode) && PlayerState.hdrLabel(player.videoFormat, state.fileTech) == "Dolby Vision") {
                    Toast.makeText(this@PlayerActivity, "This Dolby Vision video has no HDR10 layer - playing it in Dolby Vision", Toast.LENGTH_LONG).show()
                    saveProgress()
                    intent.putExtra(EXTRA_HDR_MODE, HdrMode.DOLBY_VISION)
                    intent.putExtra(EXTRA_START_MS, player.currentPosition)
                    recreate()
                    return
                }
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
                if (playbackState == Player.STATE_READY && openedAt > 0) {
                    DebugRecorder.event("player", "Video loaded ${SystemClock.elapsedRealtime() - openedAt} ms after opening")
                }
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
                    memcAvailable = memcAllowed && stereo == null,
                    onMemcChange = { enabled, fps, quality -> updateMemc(enabled, fps, quality) },
                    picture = picture,
                    onPictureChange = { boost, autoHdr -> updatePicture(boost, autoHdr) },
                    onExport = { exportCurrent() },
                    hdrMode = hdrMode,
                    onHdrMode = { changeHdrMode(it) },
                    threeD = threeD,
                    onPlayerView = { view ->
                        playerView = view
                        DebugRecorder.videoSurface = WeakReference(view.videoSurfaceView as? SurfaceView)
                        HdrMode.applyToSurface(view.videoSurfaceView as? SurfaceView, hdrMode)
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
            openedAt = SystemClock.elapsedRealtime()
            loadFileTech()
            DebugRecorder.event("player", "Opening the video (resume at ${resumeAt / 1000}s, 60 fps ${if (memc.enabled) "on" else "off"})")
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

    // ---- Debug recording ---------------------------------------------------------------------------

    /** Everything worth knowing about the player right now, as one line. */
    private fun debugState(): String {
        val f = state.videoFormat
        val c = f?.colorInfo
        return buildString {
            append("pos=${player.currentPosition / 1000}s playing=${player.isPlaying} state=${player.playbackState} speed=${player.playbackParameters.speed}")
            if (f != null) {
                append(" | video ${f.sampleMimeType} ${f.codecs ?: ""} ${f.width}x${f.height} ${f.frameRate}fps")
                append(" colour(space=${c?.colorSpace} range=${c?.colorRange} transfer=${c?.colorTransfer} bits=${c?.lumaBitdepth})")
            }
            append(" decoder=${state.videoDecoder}")
            append(" | memc installed=$memcInstalled enabled=${memc.enabled} active=${memc.active} target=${memc.targetFps} quality=${memc.quality}")
            append(" ease=${memc.easeLevel} heat=${memc.thermalLevel} source=${memc.sourceFps}fps work=${memc.procWidth}x${memc.procHeight}")
            append(" buffer=${memc.bufferedFrames}/${memc.bufferFrames} npu=\"${memc.npuStatus}\"")
            stereo?.let { st ->
                append(" | 3d mode=${st.mode} out=${st.outWidth}x${st.outHeight} depth=${(st.strength * 100).toInt()}% maps/s=${"%.1f".format(java.util.Locale.US, st.depthRate)} hot=${st.hot} \"${st.status}\" glasses=${threeD.glassesActive} black=${threeD.screenOff}")
            }
            append(" | picture boost=${picture.boost} autoHdr=${picture.autoHdr} convertSdr=${picture.convertSdr} fakedHdr=${picture.fakedHdr}")
            append(" pipelineHdr=${picture.pipelineHdr} shaderMode=${picture.lastMode}")
            append(" | window brightness=${window.attributes.screenBrightness} colorMode=${if (Build.VERSION.SDK_INT >= 26) window.colorMode else 0}")
            val surface = playerView?.videoSurfaceView
            append(" | size reported=${player.videoSize.width}x${player.videoSize.height} view=${surface?.width}x${surface?.height} mode=${playerView?.resizeMode}")
            append(" | totals framesOut=${PerfStats.framesOut.get()} dropped=${PerfStats.dropped.get()}")
            append(" gpuMs=${PerfStats.gpuNs.get() / 1_000_000} npuMs=${PerfStats.npuNs.get() / 1_000_000} npuRuns=${PerfStats.npuRuns.get()}")
        }
    }

    /** While debug recording is on: a state line every 2 seconds and a video frame every 15. */
    private fun startDebugRecording() {
        lifecycleScope.launch {
            var tick = 0
            var lastError: String? = null
            while (isActive) {
                delay(2000)
                if (!DebugRecorder.isActive) continue
                runCatching {
                    DebugRecorder.event("player", debugState())
                    if (state.error != lastError) {
                        lastError = state.error
                        lastError?.let { DebugRecorder.event("player", "ERROR shown to user: $it") }
                    }
                    tick++
                    // The first frame soon after start, then one every 15 seconds while playing.
                    if (player.isPlaying && (tick == 2 || tick % 8 == 0)) DebugRecorder.captureVideo("auto")
                }
            }
        }
    }

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

    /** The library's tags for the playing file, so the HDR badge works when the stream is vague. */
    private fun loadFileTech() {
        val id = currentId() ?: return
        lifecycleScope.launch { state.fileTech = g.db.videos().get(id)?.tech.orEmpty() }
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
    // ---- 3D -------------------------------------------------------------------------------

    private var stereoMode = StereoControl.OFF
    private var stereo: StereoControl? = null
    private lateinit var threeD: ThreeDUi
    private var glasses: GlassesOutput? = null
    private var glassesSurface: SurfaceView? = null
    private var depthNpu: NpuDepth? = null
    private var depthStarting = false

    /** 3D: picture stage (Auto HDR) then the side-by-side stage; smooth motion stays off. */
    private fun installStereo() {
        val s = stereo ?: return
        player.setVideoEffects(listOf(MemcEffect(memc, picture), StereoEffect(s)))
        memcInstalled = true
        DebugRecorder.event("3d", "3D on: mode ${s.mode}, depth ${(s.strength * 100).toInt()}%, Auto HDR ${picture.autoHdr}")
    }

    private fun changeStereo(mode: Int) {
        if (mode == stereoMode) return
        DebugRecorder.event("3d", "3D mode $stereoMode -> $mode")
        intent.putExtra(EXTRA_STEREO, mode)
        restartAtPosition()
    }

    /** Video to the glasses (a surface on the external display) or back to the phone (null). */
    private fun moveVideoTo(surface: SurfaceView?) {
        glassesSurface?.let { player.clearVideoSurfaceView(it) }
        glassesSurface = surface
        if (surface != null) player.setVideoSurfaceView(surface)
        threeD.glassesActive = surface != null
        applyBlackout()
        DebugRecorder.event("glasses", if (surface != null) "Video moved to the external display" else "Video back on the phone")
    }

    private fun setScreenOff(on: Boolean) {
        threeD.screenOff = on
        applyBlackout()
    }

    /** Black touch pad: lowest backlight and the screen kept on (Android would otherwise sleep). */
    private fun applyBlackout() {
        val dark = threeD.blackout
        val lp = window.attributes
        if (dark) {
            lp.screenBrightness = 0f
            window.attributes = lp
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
            applyBoostBacklight()
            if (!player.isPlaying) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** AI depth on the NPU for 2D-to-3D (phone, Snapdragon, model downloaded). */
    private fun startDepthIfWanted() {
        val s = stereo ?: return
        if (s.mode != StereoControl.CONVERT || BuildConfig.TV || !NpuFlow.isSnapdragon) return
        if (!DepthModel.ready(this) || depthNpu != null || depthStarting) return
        if (com.jainz.memc.core.Compat.thermalStatus(this) >= 2) {
            s.status = "AI 3D waits for the phone to cool down - using picture cues"
            return
        }
        depthStarting = true
        s.status = "Preparing AI 3D (the first time can take a minute)…"
        val appContext = applicationContext
        g.scope.launch(Dispatchers.Default) {
            val result = runCatching { NpuDepth.create(appContext) }
            withContext(Dispatchers.Main) {
                depthStarting = false
                result.onSuccess { n ->
                    if (isDestroyed) {
                        n.close()
                    } else {
                        depthNpu = n
                        s.npu = n
                        DebugRecorder.event("3d", "AI depth model running on the NPU")
                    }
                }.onFailure { e ->
                    s.status = "AI 3D unavailable (${(e.message ?: e.javaClass.simpleName).take(120)}) - using picture cues"
                    DebugRecorder.event("3d", s.status)
                }
            }
        }
    }

    private fun downloadDepthModel() {
        if (threeD.modelProgress >= 0f) return
        threeD.modelProgress = 0f
        lifecycleScope.launch {
            val r = runCatching {
                DepthModel.download(applicationContext) { done, total ->
                    threeD.modelProgress = if (total > 0) done.toFloat() / total else 0f
                }
            }
            threeD.modelProgress = -1f
            r.onSuccess {
                threeD.modelState = ""
                startDepthIfWanted()
            }.onFailure { e ->
                threeD.modelState = "missing"
                Toast.makeText(this@PlayerActivity, "AI 3D download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

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
    // ---- Rendering ahead ---------------------------------------------------------------------------

    private var prebufferJob: Job? = null
    /** When the current video was opened, until its first picture (for the debug recording). */
    private var openedAt = 0L
    private var fillJob: Job? = null
    private var lastPrebufferAt = 0L

    /**
     * A paused player only does work once a second, which would leave the 60 fps engine idle.
     * This nudges it so the engine keeps rendering frames ahead until its buffer is full.
     */
    private fun fillBufferWhilePaused() {
        if (!memcInstalled || fillJob?.isActive == true) return
        fillJob = lifecycleScope.launch {
            var best = -1
            var progressAt = SystemClock.elapsedRealtime()
            while (isActive && memcInstalled && !player.isPlaying) {
                val playbackState = player.playbackState
                if (playbackState != Player.STATE_READY && playbackState != Player.STATE_BUFFERING) break
                val have = memc.bufferedFrames
                val capacity = memc.bufferFrames
                if (capacity > 0 && have >= capacity - 1) break
                val now = SystemClock.elapsedRealtime()
                if (have > best) {
                    best = have
                    progressAt = now
                } else if (now - progressAt > 2500) break
                if (playbackState == Player.STATE_READY) {
                    runCatching { player.createMessage { _, _ -> }.setLooper(player.playbackLooper).send() }
                }
                delay(12)
            }
        }
    }

    /**
     * Holds playback for a moment (at most 1.5 seconds) while the engine renders a little
     * ahead, then carries on; the rest of the buffer fills while playing. Returns false when it
     * didn't apply (not playing, 60 fps off, done too recently).
     */
    private fun prebuffer(minGapMs: Long): Boolean {
        if (!memcInstalled || !memc.enabled || memc.thermalLevel >= 4 || memc.easeLevel >= 4) return false
        if (!player.playWhenReady || prebufferJob?.isActive == true || isInPictureInPictureMode) return false
        val source = memc.sourceFps
        if (source > 0f && source >= memc.targetFps * 0.8f) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastPrebufferAt < minGapMs) return false
        lastPrebufferAt = now
        DebugRecorder.event("player", "Holding playback to fill the buffer (${memc.bufferedFrames}/${memc.bufferFrames})")
        state.prebuffering = true
        player.pause()
        prebufferJob = lifecycleScope.launch {
            // Counted from when the video is loaded: time spent opening it isn't buffering time.
            var since = SystemClock.elapsedRealtime()
            try {
                while (isActive) {
                    delay(40)
                    if (player.playWhenReady) break // play was pressed meanwhile
                    if (player.playbackState != Player.STATE_READY) {
                        since = SystemClock.elapsedRealtime()
                        if (since - now > 15_000) {
                            player.play() // never stay held behind a load that doesn't finish
                            break
                        }
                        continue
                    }
                    val capacity = memc.bufferFrames
                    // About a third of a second of frames: enough to absorb the first heavy frames
                    // without making every video slow to start.
                    val wanted = if (capacity > 0) minOf(capacity - 2, maxOf(12, capacity * 3 / 10)) else Int.MAX_VALUE
                    val waited = SystemClock.elapsedRealtime() - since
                    if (waited > 1500 || (waited > 150 && memc.bufferedFrames >= wanted)) {
                        // Not if the app was sent to the background meanwhile.
                        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) player.play()
                        break
                    }
                }
            } finally {
                state.prebuffering = false
                lastPrebufferAt = SystemClock.elapsedRealtime()
            }
        }
        fillBufferWhilePaused()
        return true
    }

    private var thermalListener: Any? = null

    /** Follows Android's heat rating so the engine backs off before the phone protects itself. */
    private fun watchHeat() {
        memc.thermalLevel = com.jainz.memc.core.Compat.thermalStatus(this)
        thermalListener = com.jainz.memc.core.Compat.watchHeat(this) { status ->
            stereo?.hot = status >= 2
            val before = memc.thermalLevel
            memc.thermalLevel = status
            DebugRecorder.event("player", "Heat level $before -> $status")
            val message = when {
                status >= 4 && before < 4 -> "Phone is very hot: 60 fps paused until it cools"
                status == 3 && before < 3 -> "Phone is hot: 60 fps switched to its lightest mode"
                status == 2 && before < 2 && memc.quality == 3 -> "Phone is warm: AI paused, using the GPU engine"
                else -> null
            }
            if (message != null && memc.enabled) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            if (status < 2) startNpuIfWanted()
        }
    }

    private fun startNpuIfWanted() {
        if (BuildConfig.TV) return
        if (memc.quality != 3 || !memc.enabled || npuLive != null || npuStarting) return
        if (!NpuFlow.isSnapdragon) {
            memc.npuStatus = "AI needs a Snapdragon chip's NPU - using the GPU engine (Best)"
            return
        }
        if (!NpuFlow.hasModel(this, NpuFlow.LIVE)) {
            memc.npuStatus = "AI model isn't in this build - using the GPU engine"
            return
        }
        if (memc.thermalLevel >= 2) {
            memc.npuStatus = "AI not started: the phone is hot - using the GPU engine until it cools"
            return
        }
        if (NpuFlow.creating > 0) {
            // Another screen of the app is already starting the model (for instance the player
            // just before it reloaded): wait for that to finish, then pick it up.
            memc.npuStatus = "AI model starting (first time can take a minute)…"
            lifecycleScope.launch {
                delay(1500)
                if (!isDestroyed) startNpuIfWanted()
            }
            return
        }
        NpuFlow.resolveInterrupted(this)
        NpuFlow.state(this).let { st ->
            if (st.startsWith("failed")) {
                memc.npuStatus = "AI is switched off (${st.removePrefix("failed: ").take(120)}). Using the GPU engine. " +
                    "To try again: Settings → Smooth motion (MEMC) → NPU status → Try again."
                DebugRecorder.event("npu", memc.npuStatus)
                return
            }
        }
        npuStarting = true
        memc.npuStatus = "AI model starting (first time can take a minute)…"
        DebugRecorder.event("npu", "Starting the AI model")
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
                        DebugRecorder.event("npu", "AI model running on the NPU")
                        // Give the AI model a full buffer to start from.
                        prebuffer(minGapMs = 0)
                    }
                }.onFailure { e ->
                    memc.npuStatus = "AI unavailable (${(e.message ?: e.javaClass.simpleName).take(120)}) - using the GPU engine"
                    DebugRecorder.event("npu", memc.npuStatus)
                }
            }
        }
    }

    private var hdrMode = HdrMode.AUTO

    /**
     * HDR output picked in the player. Switching between Dolby Vision and the base layer needs
     * another decoder (restart at the same spot); HDR10 <-> SDR only changes the screen layer.
     */
    private fun changeHdrMode(mode: Int) {
        if (mode == hdrMode) return
        val restart = HdrMode.usesDolbyVision(mode) != HdrMode.usesDolbyVision(hdrMode) ||
            (mode == HdrMode.SDR && picture.autoHdr)
        DebugRecorder.event("player", "HDR output ${HdrMode.labels.toMap()[hdrMode]} -> ${HdrMode.labels.toMap()[mode]}")
        hdrMode = mode
        intent.putExtra(EXTRA_HDR_MODE, mode)
        if (restart) {
            restartAtPosition()
        } else {
            HdrMode.applyToSurface(playerView?.videoSurfaceView as? SurfaceView, mode)
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
            // Nothing needs the processing pipeline any more: go back to plain playback, where
            // the decoder's picture goes to the screen untouched.
            !needsPipeline && !memc.enabled && memcInstalled -> restartAtPosition()
            // The engine has up to a second of frames already rendered with the old setting,
            // and renders nothing new while paused. Re-render from here so the change shows
            // at once, paused or playing.
            memcInstalled -> {
                DebugRecorder.event("player", "Picture setting changed (boost=$boost): re-rendering from the current position")
                player.seekTo(player.currentPosition)
            }
        }
    }

    private fun updateMemc(enabled: Boolean, fps: Int, quality: Int) {
        g.settings.memcEnabled.set(enabled)
        g.settings.memcTargetFps.set(fps)
        g.settings.memcQuality.set(quality)
        memc.targetFps = fps
        memc.quality = quality
        memc.enabled = enabled
        if (enabled && !memcInstalled) {
            // The player reloads here; the reloaded player starts the AI model. Starting it
            // now would hand the model to this screen just as it closes.
            restartAtPosition()
            return
        }
        startNpuIfWanted()
        // 60 fps off and no picture processing: return to plain playback (untouched picture, less power).
        if (!enabled && memcInstalled && picture.boost <= 0f && !picture.autoHdr) {
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

    @androidx.annotation.RequiresApi(26)
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

    // Picture-in-picture with a set shape needs Android 8 (older TV boxes simply don't offer it).
    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= 26) runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) runCatching { enterPictureInPictureMode(pipParams()) }
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
        com.jainz.memc.core.Compat.unwatchHeat(this, thermalListener)
        saveProgress()
        saver?.cancel()
        glasses?.stop()
        glasses = null
        state.release()
        player.release()
        // Like the motion model: closed a moment later, once the render thread is done with it.
        stereo?.npu = null
        depthNpu?.let { n ->
            depthNpu = null
            g.scope.launch {
                delay(2500)
                n.close()
            }
        }
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
        const val EXTRA_HDR_MODE = "hdr_mode"
        const val EXTRA_STEREO = "stereo"
        const val EXTRA_START_MS = "start_ms"
        const val EXTRA_NO_AUTO_HDR = "no_auto_hdr"
    }
}
