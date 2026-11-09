package com.jainz.memc.player

import android.app.Activity
import android.content.Context
import android.graphics.Color as AColor
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.jainz.memc.memc.MemcControl
import com.jainz.memc.ui.Format
import com.jainz.memc.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class PanelTab(val label: String) { AUDIO("Audio"), SUBTITLES("Subtitles"), VIDEO("Video") }

private fun zoomToMode(zoom: Int) = when (zoom) {
    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

@UnstableApi
@Composable
fun PlayerScreen(
    state: PlayerState,
    inPip: Boolean,
    resumedAt: Long?,
    seekStepMs: Long,
    subtitleScale: Float,
    defaultZoom: Int,
    onSubtitleScaleChange: (Float) -> Unit,
    onStartOver: () -> Unit,
    onDismissResume: () -> Unit,
    onBack: () -> Unit,
    onPip: () -> Unit,
    memc: MemcControl,
    memcAvailable: Boolean,
    onMemcChange: (enabled: Boolean, fps: Int, quality: Int) -> Unit,
    onExport: () -> Unit,
    onPlayerView: (PlayerView) -> Unit,
) {
    val player = state.player
    val activity = LocalContext.current as Activity
    val audio = remember { activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }

    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var locked by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(zoomToMode(defaultZoom)) }
    var panel by remember { mutableStateOf<PanelTab?>(null) }
    var subScale by remember { mutableFloatStateOf(subtitleScale) }
    var label by remember { mutableStateOf<String?>(null) }
    var labelToken by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }

    fun flash(text: String) {
        label = text
        labelToken++
    }

    fun poke() {
        interaction++
    }

    var memcActive by remember { mutableStateOf(false) }

    // Position ticker for the seek bar (and the MEMC badge).
    LaunchedEffect(Unit) {
        while (true) {
            state.tick()
            memcActive = memc.active && memc.enabled
            delay(250)
        }
    }
    // Auto-hide controls while playing.
    LaunchedEffect(controls, state.isPlaying, interaction, panel) {
        if (controls && state.isPlaying && panel == null) {
            delay(4_000)
            controls = false
        }
    }
    LaunchedEffect(labelToken) {
        delay(900)
        if (!dragging) label = null
    }
    LaunchedEffect(resumedAt) {
        if (resumedAt != null) {
            delay(7_000)
            onDismissResume()
        }
    }

    fun seekBy(ms: Long) {
        val target = (player.currentPosition + ms).coerceIn(0, state.duration.coerceAtLeast(0))
        player.seekTo(target)
        flash((if (ms < 0) "« " else "» ") + "${abs(ms) / 1000}s")
    }

    fun brightness(): Float {
        val b = activity.window.attributes.screenBrightness
        return if (b in 0f..1f) b else 0.5f
    }

    fun setBrightness(b: Float) {
        val lp = activity.window.attributes
        lp.screenBrightness = b
        activity.window.attributes = lp
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // SurfaceView-backed view: HDR10/HDR10+ frames go straight to the display.
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                    setShutterBackgroundColor(AColor.BLACK)
                    setKeepContentOnPlayerReset(true)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                }.also(onPlayerView)
            },
            update = { view ->
                view.resizeMode = resizeMode
                view.subtitleView?.apply {
                    setApplyEmbeddedFontSizes(false)
                    setStyle(
                        CaptionStyleCompat(
                            AColor.WHITE, AColor.TRANSPARENT, AColor.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE, AColor.BLACK, null,
                        )
                    )
                    setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subScale)
                    setBottomPaddingFraction(if (controls && !inPip) 0.16f else 0.06f)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (!inPip) {
            // ---- Gestures ----
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(locked, seekStepMs) {
                        detectTapGestures(
                            onTap = {
                                controls = !controls
                                poke()
                            },
                            onDoubleTap = { o ->
                                if (locked) return@detectTapGestures
                                when {
                                    o.x < size.width / 3f -> seekBy(-seekStepMs)
                                    o.x > size.width * 2f / 3f -> seekBy(seekStepMs)
                                    else -> if (player.isPlaying) player.pause() else player.play()
                                }
                            },
                        )
                    }
                    .pointerInput(locked) {
                        if (locked) return@pointerInput
                        var mode = 0 // 0 undecided, 1 seek, 2 brightness, 3 volume
                        var startX = 0f
                        var accX = 0f
                        var accY = 0f
                        var startPos = 0L
                        var target = 0L
                        var startBright = 0.5f
                        var startVol = 0
                        detectDragGestures(
                            onDragStart = { o ->
                                mode = 0; startX = o.x; accX = 0f; accY = 0f
                                startPos = player.currentPosition
                                startBright = brightness()
                                startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                                dragging = true
                            },
                            onDragEnd = {
                                if (mode == 1) player.seekTo(target)
                                dragging = false
                                label = null
                            },
                            onDragCancel = {
                                dragging = false
                                label = null
                            },
                        ) { change, amount ->
                            change.consume()
                            accX += amount.x
                            accY += amount.y
                            if (mode == 0) {
                                if (abs(accX) > 28f && abs(accX) > abs(accY)) mode = 1
                                else if (abs(accY) > 28f) mode = if (startX < size.width / 2f) 2 else 3
                            }
                            when (mode) {
                                1 -> {
                                    // Full screen width = 3 minutes.
                                    val delta = (accX / size.width * 180_000).toLong()
                                    target = (startPos + delta).coerceIn(0, state.duration.coerceAtLeast(0))
                                    val diff = target - startPos
                                    label = (if (diff < 0) "−" else "+") + Format.clock(abs(diff)) +
                                        "   " + Format.clock(target) + " / " + Format.clock(state.duration)
                                }
                                2 -> {
                                    val b = (startBright - accY / size.height * 1.2f).coerceIn(0.01f, 1f)
                                    setBrightness(b)
                                    label = "Brightness ${(b * 100).roundToInt()}%"
                                }
                                3 -> {
                                    val v = (startVol - accY / size.height * maxVolume * 1.2f).roundToInt().coerceIn(0, maxVolume)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                    label = "Volume ${v * 100 / maxVolume}%"
                                }
                            }
                        }
                    }
                    .pointerInput(locked) {
                        // Pinch out = crop to fill the screen, pinch in = fit.
                        if (locked) return@pointerInput
                        awaitEachGesture {
                            var zoom = 1f
                            var multi = false
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    multi = true
                                    zoom *= event.calculateZoom()
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            if (multi && zoom > 1.12f) {
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                flash("Crop to fill")
                            } else if (multi && zoom < 0.9f) {
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                flash("Fit to screen")
                            }
                        }
                    }
            )
        }

        if (state.buffering && !inPip) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Palette.Accent)
        }

        // Gesture feedback pill.
        label?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = controls && !inPip && !locked,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Top bar
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .displayCutoutPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BarIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(state.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        state.subtitle?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xCCFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    BarIcon(Icons.Filled.ClosedCaption, "Subtitles") { panel = PanelTab.SUBTITLES }
                    BarIcon(Icons.Filled.Audiotrack, "Audio") { panel = PanelTab.AUDIO }
                    BarIcon(Icons.Filled.Tune, "Video settings") { panel = PanelTab.VIDEO }
                    BarIcon(Icons.Filled.PictureInPictureAlt, "Picture in picture", onPip)
                }

                // Centre transport
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton(Icons.Filled.Replay10, "Back ${seekStepMs / 1000}s", 52) { seekBy(-seekStepMs); poke() }
                    RoundButton(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (state.isPlaying) "Pause" else "Play",
                        72,
                    ) {
                        if (state.ended) player.seekTo(0)
                        if (player.isPlaying) player.pause() else player.play()
                        poke()
                    }
                    RoundButton(Icons.Filled.Forward10, "Forward ${seekStepMs / 1000}s", 52) { seekBy(seekStepMs); poke() }
                }

                // Bottom bar
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .displayCutoutPadding()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    val dur = state.duration.coerceAtLeast(1L).toFloat()
                    val shown = if (scrubbing) scrubValue else state.position.toFloat()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Format.clock(shown.toLong()), color = Color.White, style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = shown.coerceIn(0f, dur),
                            onValueChange = { scrubbing = true; scrubValue = it; poke() },
                            onValueChangeFinished = {
                                player.seekTo(scrubValue.toLong())
                                scrubbing = false
                            },
                            valueRange = 0f..dur,
                            colors = SliderDefaults.colors(
                                thumbColor = Palette.Accent,
                                activeTrackColor = Palette.Accent,
                                inactiveTrackColor = Color(0x55FFFFFF),
                            ),
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        Text(
                            "-" + Format.clock((state.duration - shown.toLong()).coerceAtLeast(0)),
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BarIcon(Icons.Filled.Lock, "Lock controls") { locked = true; controls = true; poke() }
                        Spacer(Modifier.weight(1f))
                        if (memcActive) {
                            Text(
                                "MEMC ${memc.targetFps}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Black,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .background(Palette.Accent, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        val video = state.videoFormat
                        val hdr = PlayerState.hdrLabel(video)
                        if (video != null) {
                            Text(
                                listOfNotNull(
                                    PlayerState.codecName(video.sampleMimeType),
                                    if (video.height > 0) "${video.height}p" else null,
                                    hdr,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (hdr != null) Palette.Accent else Color(0xCCFFFFFF),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (state.hasNext) {
                            TextButton(onClick = { player.seekToNextMediaItem(); poke() }) {
                                Icon(Icons.Filled.SkipNext, null, tint = Color.White)
                                Spacer(Modifier.width(4.dp))
                                Text("Next episode", color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // Locked: only an unlock button.
        if (locked && controls && !inPip) {
            RoundButton(
                Icons.Filled.LockOpen, "Unlock", 48,
                Modifier.align(Alignment.CenterStart).padding(start = 24.dp),
            ) { locked = false; poke() }
        }

        // "Resumed at 42:10 · Start over"
        if (resumedAt != null && !inPip) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 24.dp, bottom = if (controls) 110.dp else 28.dp)
                    .background(Color(0xCC1E222A), RoundedCornerShape(24.dp))
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Resumed at ${Format.clock(resumedAt)}", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onStartOver) { Text("Start over", color = Palette.Accent) }
            }
        }

        // ---- Settings panel (Audio / Subtitles / Video) ----
        val openTab = panel
        if (openTab != null && !inPip) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { panel = null }
            )
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(400.dp)
                    .background(Color(0xF2121418), RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .displayCutoutPadding()
                    .padding(top = 12.dp, start = 16.dp, end = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PanelTab.entries.forEach { t ->
                        FilterChip(
                            selected = t == openTab,
                            onClick = { panel = t },
                            label = { Text(t.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Palette.AccentDim,
                                selectedLabelColor = Palette.Accent,
                                labelColor = Color.White,
                            ),
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    BarIcon(Icons.Filled.Close, "Close") { panel = null }
                }
                LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                    when (openTab) {
                        PanelTab.AUDIO -> item { AudioTab(state) }
                        PanelTab.SUBTITLES -> item {
                            SubtitlesTab(state, subScale) {
                                subScale = it
                                onSubtitleScaleChange(it)
                            }
                        }
                        PanelTab.VIDEO -> item {
                            VideoTab(
                                state,
                                resizeMode = resizeMode,
                                onResize = { resizeMode = it },
                                memc = memc,
                                memcAvailable = memcAvailable,
                                onMemcChange = onMemcChange,
                                onExport = onExport,
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }

        state.error?.let { err ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
                    .background(Color(0xEE15181D), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Playback problem", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(err, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = 10.dp)) {
                    TextButton(onClick = { state.error = null; player.prepare(); player.play() }) { Text("Retry") }
                    TextButton(onClick = onBack) { Text("Close") }
                }
            }
        }
    }

}

@Composable
private fun BarIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, description, tint = Color.White) }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    description: String,
    sizeDp: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(sizeDp.dp)
            .background(Color(0x55000000), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size((sizeDp * 0.55f).dp))
    }
}

// ---- Panel tabs -------------------------------------------------------------------

@Composable
private fun PanelHeading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextFaint,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@UnstableApi
@Composable
private fun TrackList(state: PlayerState, type: Int) {
    val player = state.player
    val groups = state.tracks.groups.filter { it.type == type }
    val textDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    val anySelected = groups.any { it.isSelected }

    Column {
        if (type == C.TRACK_TYPE_TEXT) {
            TrackRow("Off", null, selected = textDisabled || !anySelected, supported = true) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
        }
        groups.forEach { group ->
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                val forced = (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
                val detail = listOfNotNull(
                    PlayerState.codecName(f.sampleMimeType),
                    PlayerState.channels(f.channelCount),
                    if (forced) "Forced" else null,
                ).joinToString(" · ")
                TrackRow(
                    PlayerState.language(f),
                    detail,
                    selected = group.isTrackSelected(i) && !(type == C.TRACK_TYPE_TEXT && textDisabled),
                    supported = group.isTrackSupported(i, /* allowExceedsCapabilities = */ true),
                ) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .build()
                }
            }
        }
        if (groups.isEmpty()) {
            Text(
                if (type == C.TRACK_TYPE_TEXT) "This video has no subtitle tracks." else "No audio tracks found.",
                color = Palette.TextDim,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@UnstableApi
@Composable
private fun AudioTab(state: PlayerState) {
    Column {
        PanelHeading("Audio track")
        TrackList(state, C.TRACK_TYPE_AUDIO)
        state.audioDecoder?.let { ad ->
            PanelHeading("Decoder")
            Text(
                ad + if (ad.contains("ffmpeg", true)) "  (FFmpeg)" else "  (phone)",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextDim,
            )
        }
        Text(
            "Default audio language and the FFmpeg option are in Settings → Audio.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextFaint,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@UnstableApi
@Composable
private fun SubtitlesTab(state: PlayerState, scale: Float, onScale: (Float) -> Unit) {
    Column {
        PanelHeading("Subtitle track")
        TrackList(state, C.TRACK_TYPE_TEXT)
        PanelHeading("Size  ${(scale * 100).roundToInt()}%")
        Slider(
            value = scale,
            onValueChange = { onScale((it * 10).roundToInt() / 10f) },
            valueRange = 0.6f..2f,
            colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent),
        )
        Text(
            "Subtitle files named like the video (Movie.srt, Movie.en.srt, Movie.ass) in the same folder load automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextFaint,
        )
    }
}

@UnstableApi
@Composable
private fun VideoTab(
    state: PlayerState,
    resizeMode: Int,
    onResize: (Int) -> Unit,
    memc: MemcControl,
    memcAvailable: Boolean,
    onMemcChange: (Boolean, Int, Int) -> Unit,
    onExport: () -> Unit,
) {
    val player = state.player
    Column {
        PanelHeading("Zoom")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Crop",
                AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
            ).forEach { (mode, name) ->
                FilterChip(selected = resizeMode == mode, onClick = { onResize(mode) }, label = { Text(name) })
            }
        }

        PanelHeading("Speed")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { sp ->
                FilterChip(
                    selected = state.speed == sp,
                    onClick = { player.setPlaybackSpeed(sp) },
                    label = { Text(if (sp == 1f) "1×" else "$sp×") },
                )
            }
        }

        PanelHeading("Smooth motion (MEMC)")
        var on by remember { mutableStateOf(memc.enabled) }
        var fps by remember { mutableIntStateOf(memc.targetFps) }
        var quality by remember { mutableIntStateOf(memc.quality) }
        var status by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            while (true) {
                status = memcStatus(memc, memcAvailable)
                delay(500)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Frame interpolation", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (memc.active && on) Palette.Accent else Palette.TextDim,
                )
            }
            Switch(
                checked = on && memcAvailable,
                enabled = memcAvailable,
                onCheckedChange = {
                    on = it
                    onMemcChange(it, fps, quality)
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            listOf(60, 120).forEach { f ->
                FilterChip(
                    selected = fps == f,
                    onClick = { fps = f; onMemcChange(on, f, quality) },
                    label = { Text("$f fps") },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Battery saver", 1 to "Balanced", 2 to "Best").forEach { (q, label) ->
                FilterChip(
                    selected = quality == q,
                    onClick = { quality = q; onMemcChange(on, fps, q) },
                    label = { Text(label) },
                )
            }
        }

        PanelHeading("Export")
        TextButton(onClick = onExport) {
            Text("Export this video at 60 fps", color = Palette.Accent)
        }
        Text(
            "Renders a new 60 fps copy in the background (Movies/MEMC Player). Watching it later needs no interpolation, so it plays cool.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextFaint,
        )

        PanelHeading("Now playing")
        playbackInfo(state).forEach { (k, value) ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(k, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, modifier = Modifier.width(110.dp))
                Text(value, style = MaterialTheme.typography.bodySmall, color = Color.White)
            }
        }
    }
}

private fun memcStatus(memc: MemcControl, available: Boolean): String {
    val src = memc.sourceFps
    return when {
        !available -> "Not available for this video (the video pipeline refused it)"
        !memc.enabled -> "Off"
        memc.active -> String.format(java.util.Locale.US, "Interpolating %.3f → %d fps", src, memc.targetFps)
        src > 0f && src >= memc.targetFps * 0.8f ->
            String.format(java.util.Locale.US, "Video is already %.0f fps - nothing to add", src)
        else -> "Starting… (measuring the video's frame rate)"
    }
}

@UnstableApi
private fun playbackInfo(state: PlayerState): List<Pair<String, String>> {
    val v = state.videoFormat
    val a = state.audioFormat
    val vd = state.videoDecoder
    val ad = state.audioDecoder
    return buildList {
        if (v != null) {
            add("Video" to listOfNotNull(
                PlayerState.codecName(v.sampleMimeType),
                if (v.width > 0) "${v.width}×${v.height}" else null,
                if (v.frameRate > 0) String.format(java.util.Locale.US, "%.3f fps", v.frameRate) else null,
            ).joinToString(" · "))
            add("HDR" to (PlayerState.hdrLabel(v) ?: "SDR"))
        }
        if (vd != null) {
            val hw = vd.startsWith("c2.qti") || vd.startsWith("OMX.qcom") || vd.startsWith("c2.exynos") || vd.contains("hw", true)
            add("Video decoder" to vd + if (hw) "  (hardware)" else "")
        }
        if (a != null) {
            add("Audio" to listOfNotNull(
                PlayerState.codecName(a.sampleMimeType),
                PlayerState.channels(a.channelCount),
                if (a.sampleRate > 0) "${a.sampleRate / 1000.0} kHz" else null,
            ).joinToString(" · "))
        }
        if (ad != null) add("Audio decoder" to ad)
        add("Buffered" to "${((state.buffered - state.position).coerceAtLeast(0) / 1000)} s ahead")
    }
}

@Composable
private fun TrackRow(title: String, detail: String?, selected: Boolean, supported: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = supported, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp)) {
            if (selected) Icon(Icons.Filled.Check, null, tint = Palette.Accent)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (supported) Color.White else Palette.TextFaint,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    detail + if (!supported) " · not supported" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                )
            }
        }
    }
}
