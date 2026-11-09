package com.jainz.memc.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 30) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        hideSystemBars()

        player = PlayerFactory.create(this, g.smb)
        state = PlayerState(player)
        player.addListener(object : Player.Listener {
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
                    onStartOver = {
                        player.seekTo(0)
                        resumeNotice = null
                    },
                    onDismissResume = { resumeNotice = null },
                    onBack = { finish() },
                    onPip = { enterPip() },
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
            val resumeAt = start?.takeIf { !fromStart && !it.watched && it.positionMs > 30_000 }?.positionMs ?: 0L
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
        val subs = if (uri.startsWith("smb://")) sideSubtitlesSmb(uri) else emptyList()
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
                val ext = NameParser.extension(e.name)
                val middle = e.name.substring(base.length).removeSuffix(".$ext").trim('.', ' ', '_', '-')
                val subUri = Uri.parse(uri).buildUpon().path(null).apply {
                    appendPath(loc.share)
                    e.path.split('/').filter { it.isNotEmpty() }.forEach { appendPath(it) }
                }.build()
                MediaItem.SubtitleConfiguration.Builder(subUri)
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
    } catch (e: Exception) {
        emptyList()
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
        lifecycleScope.launch {
            val v = g.db.videos().get(id) ?: return@launch
            val tags = PlayerState.techTags(video, player.audioFormat, v.tech)
            if (tags.isNotBlank() && tags != v.tech) g.db.videos().setTech(id, tags)
        }
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
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FROM_START = "from_start"
    }
}
