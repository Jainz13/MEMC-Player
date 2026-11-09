package com.jainz.memc.ui.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.jainz.memc.MemcApp
import com.jainz.memc.Routes
import com.jainz.memc.data.db.CastMember
import com.jainz.memc.data.db.VideoCard
import com.jainz.memc.data.scan.Scraper
import com.jainz.memc.data.tmdb.Tmdb
import com.jainz.memc.ui.Format
import com.jainz.memc.ui.Play
import com.jainz.memc.ui.components.Artwork
import com.jainz.memc.ui.components.BackdropHeader
import com.jainz.memc.ui.components.CardShape
import com.jainz.memc.ui.components.CastShelf
import com.jainz.memc.ui.components.FixMatchDialog
import com.jainz.memc.ui.components.rememberExportAction
import com.jainz.memc.ui.components.ProgressStrip
import com.jainz.memc.ui.components.SectionTitle
import com.jainz.memc.ui.components.TechBadges
import com.jainz.memc.ui.theme.Palette
import kotlinx.coroutines.launch

@Composable
fun MovieDetailScreen(nav: NavController, tmdbId: Long) {
    val g = MemcApp.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val movie by remember(tmdbId) { g.db.meta().observeMovie(tmdbId) }.collectAsStateWithLifecycle(null)
    val files by remember(tmdbId) { g.db.videos().forMovie(tmdbId) }.collectAsStateWithLifecycle(emptyList())
    var menu by remember { mutableStateOf(false) }
    var fixing by remember { mutableStateOf(false) }
    val export = rememberExportAction()

    val m = movie
    val primary = files.firstOrNull()?.video

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                BackdropHeader(Tmdb.backdrop(m?.backdropPath), m?.title ?: "", height = 440.dp) {
                    Row(
                        Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Artwork(
                            Tmdb.posterLarge(m?.posterPath), m?.title ?: "",
                            Modifier.width(104.dp).height(156.dp).clip(CardShape),
                        )
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(m?.title ?: "", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                listOfNotNull(
                                    m?.year?.toString(),
                                    Format.runtime(m?.runtime),
                                    m?.certification,
                                    m?.rating?.let { Format.rating(it) },
                                ).joinToString("  ·  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.TextDim,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            if (primary != null) TechBadges(primary.tech, Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }

            if (primary != null) {
                item {
                    val resume = primary.positionMs > 30_000 && !primary.watched
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { Play.video(context, primary.id) },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Filled.PlayArrow, null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (resume) "Resume from ${Format.clock(primary.positionMs)}" else "Play")
                            }
                            if (resume) {
                                FilledTonalButton(
                                    onClick = { Play.videoFromStart(context, primary.id) },
                                    modifier = Modifier.height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                ) { Icon(Icons.Filled.Replay, "Start over") }
                            }
                        }
                        if (resume) {
                            Box(Modifier.fillMaxWidth().padding(top = 10.dp).clip(CircleShape)) {
                                ProgressStrip(Format.progress(primary))
                            }
                            Text(
                                Format.left(primary) ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.TextDim,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    if (!m?.tagline.isNullOrBlank()) {
                        Text(
                            m?.tagline ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = Palette.TextDim,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Text(
                        m?.overview ?: "No description available.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Text.copy(alpha = 0.9f),
                    )
                    Spacer(Modifier.height(14.dp))
                    InfoRow("Genres", m?.genres)
                    InfoRow("Director", m?.director)
                    InfoRow("Released", m?.releaseDate)
                }
            }

            item { CastShelf(CastMember.parse(m?.cast ?: "")) }

            if (files.size > 1) {
                item { SectionTitle("Versions", Modifier.padding(top = 18.dp)) }
                files.forEach { f -> item(key = "v${f.video.id}") { FileRow(f, playable = true) } }
            } else if (files.size == 1) {
                item { SectionTitle("File", Modifier.padding(top = 18.dp)) }
                item { FileRow(files[0], playable = false) }
            }

            item {
                Text(
                    Tmdb.ATTRIBUTION,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = null),
                    color = Palette.TextFaint,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }

        // Floating top bar over the artwork.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp)) {
            RoundIconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Box {
                RoundIconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = Color.White) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    if (primary != null) {
                        DropdownMenuItem(
                            text = { Text(if (primary.watched) "Mark as unwatched" else "Mark as watched") },
                            onClick = {
                                menu = false
                                scope.launch { g.db.videos().setWatched(primary.id, !primary.watched) }
                            },
                        )
                    }
                    if (primary != null) {
                        DropdownMenuItem(
                            text = { Text("Export at 60 fps") },
                            onClick = {
                                menu = false
                                val name = listOfNotNull(m?.title ?: primary.parsedTitle, m?.year?.let { "($it)" }).joinToString(" ")
                                export(primary.uri, name)
                            },
                        )
                    }
                    DropdownMenuItem(text = { Text("Fix match") }, onClick = { menu = false; fixing = true })
                    DropdownMenuItem(
                        text = { Text("Refresh details") },
                        onClick = {
                            menu = false
                            scope.launch { runCatching { Scraper(g).ensureMovie(tmdbId, force = true) } }
                        },
                    )
                }
            }
        }
    }

    if (fixing && primary != null) {
        FixMatchDialog(
            initialQuery = m?.title ?: primary.parsedTitle,
            initialYear = m?.year ?: primary.parsedYear,
            allowMovie = true,
            allowTv = false,
            startWithTv = false,
            onDismiss = { fixing = false },
            onPick = { r ->
                runCatching {
                    val scraper = Scraper(g)
                    files.forEach { f -> scraper.assignMovie(f.video.id, r.id) }
                }
                if (r.id != tmdbId) {
                    nav.popBackStack()
                    nav.navigate(Routes.movie(r.id))
                }
            },
        )
    }
}

@Composable
fun RoundIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.padding(4.dp).size(40.dp).background(Color(0x55000000), CircleShape),
    ) { content() }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, modifier = Modifier.width(80.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
    }
}

@Composable
private fun FileRow(card: VideoCard, playable: Boolean) {
    val context = LocalContext.current
    val v = card.video
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (playable) Modifier.clickable { Play.video(context, v.id) } else Modifier)
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text(v.fileName, style = MaterialTheme.typography.bodySmall)
        Text(
            listOf(Format.size(v.size), if (v.uri.startsWith("smb://")) "Network share" else "This phone", v.folder)
                .joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextFaint,
        )
        TechBadges(v.tech, Modifier.padding(top = 6.dp))
    }
}
