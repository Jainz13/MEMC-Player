package com.jainz.memc.player

import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.jainz.memc.BuildConfig
import com.jainz.memc.stereo.StereoControl
import com.jainz.memc.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** Everything the player screen needs to show and change 3D, glasses output and screen-off. */
class ThreeDUi(
    /** 3D layout in use for this video (StereoControl.OFF when off). */
    val mode: Int,
    val stereo: StereoControl?,
    val onMode: (Int) -> Unit,
    val onStrength: (Float) -> Unit,
    val onSwap: (Boolean) -> Unit,
    val onScreenOff: (Boolean) -> Unit,
    val onDownloadModel: () -> Unit,
    val autoHdr: Boolean = true,
    val onAutoHdr: (Boolean) -> Unit = {},
    /** Save a 3D copy (true = half side-by-side); null where the export can't run (no Snapdragon NPU, TV). */
    val onExport3d: ((Boolean) -> Unit)? = null,
) {
    /** The video is on an external display (glasses); the phone shows the black touch pad. */
    var glassesActive by mutableStateOf(false)
    var glassesInfo by mutableStateOf<String?>(null)
    /** The phone screen is blacked out (touch pad only). */
    var screenOff by mutableStateOf(false)
    var modelState by mutableStateOf("")
    /** 0..1 while the AI 3D model downloads, -1 otherwise. */
    var modelProgress by mutableFloatStateOf(-1f)
    var canUseAi by mutableStateOf(false)
    /** Points the video at the glasses again (after the phone's view let go of it). */
    var reattachVideo: (() -> Unit)? = null

    val blackout: Boolean get() = !BuildConfig.TV && (glassesActive || screenOff)
}

/** The "3D" part of the player's Video panel. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ThreeDSection(ui: ThreeDUi) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("3D", style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.padding(top = 12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StereoControl.labels.forEach { (value, name) ->
                FilterChip(selected = ui.mode == value, onClick = { ui.onMode(value) }, label = { Text(name) })
            }
        }
        Text(
            if (ui.mode == StereoControl.OFF) {
                "Side-by-side 3D for AR glasses (RayNeo, XREAL, VITURE). Pick 2D → 3D for normal videos; the SBS options are only for files that are already 3D (\"SBS\" / \"Half-SBS\" in the name). Smooth motion is off in 3D. Switching restarts the video."
            } else {
                "Smooth motion is off and Auto HDR is on while 3D plays." +
                    if (!BuildConfig.TV) " With glasses connected the video goes to them and this screen becomes a black touch pad." else ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextFaint,
        )
        val stereo = ui.stereo
        if (stereo != null && ui.mode == StereoControl.CONVERT) {
            var strength by remember { mutableFloatStateOf(stereo.strength) }
            Text("Depth  ${(strength * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, color = Color.White)
            Slider(
                value = strength,
                onValueChange = { strength = it; stereo.strength = it },
                onValueChangeFinished = { ui.onStrength(strength) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent),
            )
            var status by remember { mutableStateOf(stereo.status) }
            LaunchedEffect(Unit) {
                while (true) {
                    status = listOf(stereo.status, ui.modelState).filter { it.isNotBlank() }.joinToString(" · ")
                    delay(1000)
                }
            }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = Palette.Accent)
            if (ui.canUseAi) {
                when {
                    ui.modelProgress >= 0f -> {
                        Text("Downloading the AI 3D model… ${(ui.modelProgress * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                        LinearProgressIndicator(progress = { ui.modelProgress }, modifier = Modifier.fillMaxWidth(), color = Palette.Accent)
                    }
                    ui.modelState == "missing" -> OutlinedButton(onClick = ui.onDownloadModel) {
                        Text("Get AI 3D (one-time download, about 100 MB)")
                    }
                }
            }
        }
        if (stereo != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto HDR in 3D", color = Color.White)
                    Text(
                        "Brighter, HDR picture on the glasses. Turn off if it looks washed out or dim in their 3D mode (restarts the video).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextFaint,
                    )
                }
                Switch(checked = ui.autoHdr, onCheckedChange = { ui.onAutoHdr(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Swap left and right eye", color = Color.White, modifier = Modifier.weight(1f))
                var swap by remember { mutableStateOf(stereo.swapEyes) }
                Switch(checked = swap, onCheckedChange = { swap = it; stereo.swapEyes = it; ui.onSwap(it) })
            }
        }
        val export = ui.onExport3d
        if (export != null && ui.mode != StereoControl.SBS && ui.mode != StereoControl.HALF_SBS) {
            Text("Save as a 3D file", style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Best-quality 3D: the AI looks at 32 frames at a time (Video Depth Anything), so depth stays steady. " +
                    "Runs in the background; expect it to take longer than the video. Uses the depth and eye settings above.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextFaint,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { export(false) }) { Text("Full SBS (glasses)") }
                OutlinedButton(onClick = { export(true) }) { Text("Half SBS (3D TV)") }
            }
        }
        if (!BuildConfig.TV) {
            ui.glassesInfo?.let { Text("Glasses: $it", style = MaterialTheme.typography.bodySmall, color = Palette.Accent) }
            if (ui.mode != StereoControl.OFF && ui.glassesInfo == null) {
                Text(
                    "Connect the glasses by USB-C (screen mirroring, not DeX), then switch them to 3D mode with their buttons (RayNeo Air: volume and brightness together).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                )
            }
            Button(onClick = { ui.onScreenOff(true) }, modifier = Modifier.padding(top = 4.dp)) {
                Text("Turn the phone screen black")
            }
        }
    }
}

/**
 * Black touch pad shown instead of the video when it plays on glasses or with the screen off.
 * Double-tap: play / pause. Swipe left or right: seek. Swipe up or down on the right: volume;
 * on the left: 3D depth (in 2D-to-3D). Press and hold: wake the screen.
 */
/** Swipe distance to a seek: nothing for the first 8% of the width, then 90 s per full width. */
private fun seekMs(dx: Float, width: Int): Long {
    val dead = width * 0.08f
    val past = (abs(dx) - dead).coerceAtLeast(0f)
    val ms = (past / width * 90_000).toLong() / 5000 * 5000
    return if (dx < 0) -ms else ms
}

@Composable
fun TouchPad(ui: ThreeDUi, player: Player, audio: AudioManager, onWake: () -> Unit) {
    var hint by remember { mutableStateOf<String?>("Double-tap: play/pause · swipe ←→: seek · swipe ↕ right: volume" +
        (if (ui.stereo?.mode == StereoControl.CONVERT) " · left: 3D depth" else "") + " · hold: wake") }
    var hintToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(hintToken) {
        delay(if (hintToken == 0) 4000 else 1200)
        hint = null
    }
    fun say(text: String) {
        hint = text
        hintToken++
    }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (player.isPlaying) player.pause() else player.play()
                        say(if (player.isPlaying) "Playing" else "Paused")
                    },
                    onLongPress = { onWake() },
                )
            }
            .pointerInput(Unit) {
                var dx = 0f
                var dy = 0f
                var startX = 0f
                var startPos = 0L
                var startVol = 0
                var startDepth = 0f
                var axis = 0 // 1 = horizontal, 2 = vertical
                detectDragGestures(
                    onDragStart = { o ->
                        dx = 0f; dy = 0f; axis = 0
                        startX = o.x
                        startPos = player.currentPosition
                        startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        startDepth = ui.stereo?.strength ?: 0f
                    },
                    onDragEnd = {
                        val ms = seekMs(dx, size.width)
                        if (axis == 1 && ms != 0L) player.seekTo((startPos + ms).coerceIn(0, player.duration.coerceAtLeast(0)))
                    },
                ) { change, amount ->
                    change.consume()
                    dx += amount.x
                    dy += amount.y
                    // A deliberate swipe only: small slips while holding the phone did seek before.
                    val slop = size.width * 0.06f
                    if (axis == 0 && (abs(dx) > slop || abs(dy) > slop)) axis = if (abs(dx) > abs(dy) * 1.5f) 1 else if (abs(dy) > abs(dx) * 1.5f) 2 else 0
                    when (axis) {
                        1 -> {
                            val ms = seekMs(dx, size.width)
                            val target = (startPos + ms).coerceAtLeast(0)
                            say(com.jainz.memc.ui.Format.clock(target) + "  (" + (if (ms >= 0) "+" else "−") + "${abs(ms) / 1000} s)")
                        }
                        2 -> {
                            val frac = -dy / size.height
                            val stereo = ui.stereo
                            if (startX < size.width / 2 && stereo != null && stereo.mode == StereoControl.CONVERT) {
                                val v = (startDepth + frac).coerceIn(0f, 1f)
                                stereo.strength = v
                                say("3D depth ${(v * 100).roundToInt()}%")
                            } else {
                                val v = (startVol + frac * maxVol * 1.5f).roundToInt().coerceIn(0, maxVol)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                say("Volume ${v * 100 / maxVol}%")
                            }
                        }
                    }
                }
            },
    ) {
        // Very dim, so the screen stays effectively dark.
        AnimatedVisibility(hint != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            Text(
                hint ?: "",
                color = Color(0xFF3A3A3A),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(32.dp),
            )
        }
    }
}
