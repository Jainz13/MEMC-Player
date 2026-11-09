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
import com.jainz.memc.BuildConfig
import com.jainz.memc.MemcApp
import com.jainz.memc.data.scan.ScanWorker
import com.jainz.memc.data.tmdb.Tmdb
import com.jainz.memc.player.DeviceCaps
import com.jainz.memc.ui.components.SectionTitle
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
    val caps = remember { DeviceCaps.read(context) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
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
                                        g.db.videos().resetScrape()
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
                        scope.launch {
                            g.db.videos().resetScrape()
                            g.db.meta().clearMovies(); g.db.meta().clearShows()
                            g.db.meta().clearSeasons(); g.db.meta().clearEpisodes()
                            ScanWorker.enqueue(context, scrapeOnly = true)
                        }
                    }) { Text("Re-download all artwork") }
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
