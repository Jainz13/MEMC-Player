package com.jainz.memc.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.text.font.FontFamily
import com.jainz.memc.BuildConfig
import com.jainz.memc.core.CrashLog
import com.jainz.memc.memc.NpuFlow
import androidx.compose.material3.TextButton
import com.jainz.memc.MemcApp
import com.jainz.memc.data.scan.ScanWorker
import com.jainz.memc.data.tmdb.Tmdb
import com.jainz.memc.player.DeviceCaps
import com.jainz.memc.ui.components.SectionTitle
import com.jainz.memc.ui.components.StorageAccessCard
import com.jainz.memc.ui.theme.Palette
import kotlinx.coroutines.launch

private val LANGUAGES = listOf(
    "en-US" to "English", "ar-SA" to "العربية", "hi-IN" to "हिन्दी", "ml-IN" to "മലയാളം",
    "ta-IN" to "தமிழ்", "fr-FR" to "Français", "es-ES" to "Español", "de-DE" to "Deutsch",
)

@Composable
fun SettingsScreen(nav: NavController) {
    val g = MemcApp.graph
    val st = g.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val savedKey by st.tmdbKey.flow.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf(savedKey) }
    var showKey by remember { mutableStateOf(false) }
    var keyStatus by remember { mutableStateOf<String?>(null) }
    val language by st.language.flow.collectAsStateWithLifecycle()
    val indexDevice by st.indexDevice.flow.collectAsStateWithLifecycle()
    val scanOnStart by st.scanOnStart.flow.collectAsStateWithLifecycle()
    val autoNext by st.autoPlayNext.flow.collectAsStateWithLifecycle()
    val seekStep by st.seekStepSec.flow.collectAsStateWithLifecycle()
    val subScale by st.subtitleScale.flow.collectAsStateWithLifecycle()
    val zoom by st.defaultZoom.flow.collectAsStateWithLifecycle()
    val matchFps by st.matchFrameRate.flow.collectAsStateWithLifecycle()
    val audioLang by st.audioLanguage.flow.collectAsStateWithLifecycle()
    val ffmpegAudio by st.ffmpegAudio.flow.collectAsStateWithLifecycle()
    val subsOn by st.subtitlesOn.flow.collectAsStateWithLifecycle()
    val subLang by st.subtitleLanguage.flow.collectAsStateWithLifecycle()
    val memcFps by st.memcTargetFps.flow.collectAsStateWithLifecycle()
    val memcOn by st.memcEnabled.flow.collectAsStateWithLifecycle()
    val npuExport by st.npuExport.flow.collectAsStateWithLifecycle()
    var npuState by remember { mutableStateOf(NpuFlow.state(context)) }
    val hdrBoost by st.hdrBoost.flow.collectAsStateWithLifecycle()
    val autoHdr by st.autoHdr.flow.collectAsStateWithLifecycle()
    val memcQuality by st.memcQuality.flow.collectAsStateWithLifecycle()
    val caps = remember { DeviceCaps.read(context) }
    var crash by remember { mutableStateOf(CrashLog.read(context)) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
            }
        }

        if (crash != null) {
            item { SectionTitle("Last crash report") }
            item {
                Card {
                    Text(
                        "The app closed unexpectedly. Copy or share this report so it can be fixed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextDim,
                    )
                    Text(
                        crash!!.lines().take(25).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = Palette.Text,
                        maxLines = 25,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm.setPrimaryClip(ClipData.newPlainText("MEMC Player crash", crash))
                            Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
                        }) { Text("Copy") }
                        OutlinedButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "MEMC Player crash report")
                                .putExtra(Intent.EXTRA_TEXT, crash)
                            context.startActivity(Intent.createChooser(send, "Share crash report"))
                        }) { Text("Share") }
                        OutlinedButton(onClick = { CrashLog.clear(context); crash = null }) { Text("Clear") }
                    }
                }
            }
        }

        // ---- Metadata ----
        item { SectionTitle("Posters & details (TMDB)") }
        item {
            Card {
                Text(
                    "Get a free key at themoviedb.org → Settings → API. Either the API key or the longer Read Access Token works.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim(); keyStatus = null },
                    label = { Text("TMDB API key") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show key")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = key != savedKey || keyStatus == null,
                        onClick = {
                            scope.launch {
                                keyStatus = "Checking…"
                                st.tmdbKey.set(key)
                                val err = if (key.isBlank()) null else g.tmdb.verifyKey()
                                keyStatus = err ?: if (key.isBlank()) "Key removed" else "Key works - fetching artwork"
                                if (err == null && key.isNotBlank()) {
                                    g.db.videos().retryNotFound()
                                    ScanWorker.enqueue(context, scrapeOnly = true)
                                }
                            }
                        },
                    ) { Text("Save & verify") }
                    keyStatus?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Key works")) Palette.Accent else Palette.TextDim)
                    }
                }
                Text("Metadata language", style = MaterialTheme.typography.bodyMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LANGUAGES) { (code, label) ->
                        FilterChip(
                            selected = language == code,
                            onClick = {
                                if (language != code) {
                                    st.language.set(code)
                                    scope.launch {
                                        g.db.meta().markMoviesStale()
                                        g.db.meta().markShowsStale()
                                        g.db.meta().clearEpisodes()
                                        ScanWorker.enqueue(context, scrapeOnly = true)
                                    }
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        // ---- Library ----
        item { SectionTitle("Library", Modifier.padding(top = 12.dp)) }
        item { StorageAccessCard() }
        item {
            Card {
                ToggleRow("Include videos on this phone", "Camera, WhatsApp and screen recordings are never matched", indexDevice) {
                    st.indexDevice.set(it)
                }
                ToggleRow("Scan when the app opens", "Picks up new files on your shares automatically", scanOnStart) {
                    st.scanOnStart.set(it)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { ScanWorker.enqueue(context) }) { Text("Rescan now") }
                    OutlinedButton(onClick = {
                        // Keeps every match (including ones fixed by hand) and re-downloads details.
                        scope.launch {
                            g.db.meta().markMoviesStale()
                            g.db.meta().markShowsStale()
                            g.db.meta().clearEpisodes()
                            g.db.videos().retryNotFound()
                            ScanWorker.enqueue(context, scrapeOnly = true)
                        }
                    }) { Text("Refresh all details") }
                }
            }
        }

        // ---- Playback ----
        item { SectionTitle("Playback", Modifier.padding(top = 12.dp)) }
        item {
            Card {
                ToggleRow("Play next episode automatically", null, autoNext) { st.autoPlayNext.set(it) }
                Text("Double-tap skip", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30).forEach { s ->
                        FilterChip(selected = seekStep == s, onClick = { st.seekStepSec.set(s) }, label = { Text("${s}s") })
                    }
                }
            }
        }

        // ---- Video ----
        item { SectionTitle("Video", Modifier.padding(top = 12.dp)) }
        item {
            Card {
                Text("Default zoom", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Fit", 1 to "Crop to fill", 2 to "Stretch").forEach { (v, label) ->
                        FilterChip(selected = zoom == v, onClick = { st.defaultZoom.set(v) }, label = { Text(label) })
                    }
                }
                ToggleRow(
                    "Match display to video frame rate",
                    "Switches the 120 Hz screen to a multiple of 24/25/30 fps so motion doesn't judder",
                    matchFps,
                ) { st.matchFrameRate.set(it) }
                Text(
                    "Video is always decoded by the phone's hardware (HEVC, AV1, VP9, H.264) and HDR10/HDR10+ goes straight to the display.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextFaint,
                )
            }
        }

        // ---- Picture ----
        item { SectionTitle("Picture", Modifier.padding(top = 12.dp)) }
        item {
            Card {
                Text("HDR boost: $hdrBoost%", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = hdrBoost.toFloat(),
                    onValueChange = { st.hdrBoost.set((it / 5).toInt() * 5) },
                    valueRange = 0f..100f,
                )
                Text(
                    "Raises brightness by scaling each pixel's light while keeping its hue and saturation, with a soft roll-off so highlights don't clip, and turns the backlight up with it. Works on SDR and HDR videos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                )
                ToggleRow(
                    "Auto HDR (experimental)",
                    "Converts SDR videos to HDR10 so the screen runs in HDR mode. Colours were wrong on some videos in testing; leave it off unless you're trying it. Real HDR videos are untouched.",
                    autoHdr,
                ) { st.autoHdr.set(it) }
                Text(
                    "Boost, Auto HDR and smooth motion use the GPU video pipeline: HDR10+ videos then play as HDR10.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextFaint,
                )
            }
        }

        // ---- Smooth motion (MEMC) ----
        item { SectionTitle("Smooth motion (MEMC)", Modifier.padding(top = 12.dp)) }
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Frame interpolation", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Raises 24/25/30 fps videos to 60 or 120 fps on the GPU. Can also be switched in the player (Video tab).",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.TextDim,
                        )
                    }
                    Switch(checked = memcOn, onCheckedChange = { st.memcEnabled.set(it) })
                }
                Text("Target frame rate", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(60, 120).forEach { fps ->
                        FilterChip(selected = memcFps == fps, onClick = { st.memcTargetFps.set(fps) }, label = { Text("$fps fps") })
                    }
                }
                Text("Quality", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Battery saver", 1 to "Balanced", 2 to "Best", 3 to "AI").forEach { (v, label) ->
                        FilterChip(selected = memcQuality == v, onClick = { st.memcQuality.set(v) }, label = { Text(label) })
                    }
                }
                Text(
                    "AI uses the RIFE model on the phone's NPU during playback (one pass per source frame), with Best as its fallback when the NPU isn't ready or can't keep up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextFaint,
                )
                ToggleRow(
                    "AI interpolation for exports (NPU)",
                    "Uses the RIFE model on the phone's NPU for 60 fps exports: cleaner on complex motion. Falls back to the GPU engine if the NPU can't be used. First use takes up to a minute to prepare.",
                    npuExport,
                ) { st.npuExport.set(it) }
                if (npuState.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "NPU status: " + when {
                                npuState == "ok" -> "working"
                                npuState == "trying" -> "was starting when the app closed"
                                else -> npuState
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (npuState == "ok") Palette.Accent else Palette.TextDim,
                            modifier = Modifier.weight(1f),
                        )
                        if (npuState != "ok") {
                            TextButton(onClick = { NpuFlow.setState(context, ""); npuState = "" }) { Text("Try again") }
                        }
                    }
                }
                Text(
                    "With smooth motion on, HDR10+ videos play as HDR10 (the dynamic brightness data can't pass through the effect pipeline).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextFaint,
                )
            }
        }

        // ---- Audio ----
        item { SectionTitle("Audio", Modifier.padding(top = 12.dp)) }
        item {
            Card {
                Text("Preferred audio language", style = MaterialTheme.typography.bodyMedium)
                LanguageChips(audioLang, "Original") { st.audioLanguage.set(it) }
                ToggleRow(
                    "Always use FFmpeg for audio",
                    "Normally used only for DTS/TrueHD. Turn on if a track plays silent or out of sync.",
                    ffmpegAudio,
                ) { st.ffmpegAudio.set(it) }
            }
        }

        // ---- Subtitles ----
        item { SectionTitle("Subtitles", Modifier.padding(top = 12.dp)) }
        item {
            Card {
                ToggleRow(
                    "Show subtitles by default",
                    "When off, only forced subtitles (translated signs and foreign lines) appear",
                    subsOn,
                ) { st.subtitlesOn.set(it) }
                Text("Preferred subtitle language", style = MaterialTheme.typography.bodyMedium)
                LanguageChips(subLang, "Phone language") { st.subtitleLanguage.set(it) }
                Text("Subtitle size: $subScale%", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = subScale.toFloat(),
                    onValueChange = { st.subtitleScale.set((it / 10).toInt() * 10) },
                    valueRange = 60f..200f,
                )
            }
        }

        // ---- Device ----
        item { SectionTitle("This device", Modifier.padding(top = 12.dp)) }
        item {
            Card {
                InfoLine("Chip", caps.socModel)
                InfoLine("Display HDR", caps.hdrTypes.joinToString(", ").ifBlank { "None reported" })
                InfoLine("Refresh rates", caps.refreshRates.joinToString(" / ") { "$it Hz" })
                InfoLine("Hardware video", caps.hardwareDecoders.joinToString("\n"))
                InfoLine(
                    "Extra audio",
                    if (caps.extendedAudio) "DTS, DTS-HD, TrueHD, AC-3, E-AC-3 via FFmpeg" else "FFmpeg decoder not loaded",
                )
            }
        }

        item { SectionTitle("About", Modifier.padding(top = 12.dp)) }
        item {
            Card {
                InfoLine("Version", BuildConfig.VERSION_NAME)
                Text(Tmdb.ATTRIBUTION, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
            }
        }
    }
}

private val TRACK_LANGUAGES = listOf(
    "en" to "English", "ar" to "Arabic", "hi" to "Hindi", "ml" to "Malayalam",
    "ta" to "Tamil", "te" to "Telugu", "ja" to "Japanese", "ko" to "Korean",
    "es" to "Spanish", "fr" to "French",
)

@Composable
private fun LanguageChips(selected: String, defaultLabel: String, onPick: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(selected = selected.isBlank(), onClick = { onPick("") }, label = { Text(defaultLabel) }) }
        items(TRACK_LANGUAGES) { (code, label) ->
            FilterChip(selected = selected == code, onClick = { onPick(code) }, label = { Text(label) })
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        color = Palette.Surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, modifier = Modifier.weight(0.38f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = Palette.Text, modifier = Modifier.weight(0.62f))
    }
}
