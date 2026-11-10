package com.jainz.memc.ui.shows

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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
import com.jainz.memc.download.Downloads
import com.jainz.memc.player.MediaProbe
import android.widget.Toast
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.IconButton
import com.jainz.memc.ui.components.ProgressStrip
import com.jainz.memc.ui.components.TechBadges
import com.jainz.memc.ui.components.WatchedTick
import com.jainz.memc.ui.movies.RoundIconButton
import com.jainz.memc.ui.theme.Palette
import kotlinx.coroutines.launch

@Composable
fun ShowDetailScreen(nav: NavController, tmdbId: Long) {
    val g = MemcApp.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val show by remember(tmdbId) { g.db.meta().observeShow(tmdbId) }.collectAsStateWithLifecycle(null)
    val seasons by remember(tmdbId) { g.db.videos().seasons(tmdbId) }.collectAsStateWithLifecycle(emptyList())
    val seasonMeta by remember(tmdbId) { g.db.meta().observeSeasons(tmdbId) }.collectAsStateWithLifecycle(emptyList())
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var menu by remember { mutableStateOf(false) }
    var fixing by remember { mutableStateOf(false) }

    val season = selected ?: seasons.firstOrNull()
    val episodes by remember(tmdbId, season) {
        g.db.videos().episodes(tmdbId, season ?: -1)
    }.collectAsStateWithLifecycle(emptyList())

    // Next episode to watch across all seasons: the first one in progress or not yet watched.
    val nextUp by produceState<VideoCard?>(null, tmdbId, episodes) {
        val all = g.db.videos().allEpisodes(tmdbId)
        value = all.firstOrNull { it.video.positionMs > 30_000 && !it.video.watched }
            ?: all.firstOrNull { !it.video.watched }
            ?: all.firstOrNull()
    }
    // Open on the season of the next episode.
    LaunchedEffect(nextUp?.video?.season) {
        if (selected == null) nextUp?.video?.season?.let { selected = it }
    }

    // Read each listed episode's tracks once, so its badges (HDR, Atmos, DTS…) are real.
    LaunchedEffect(episodes.map { it.video.id }) {
        for (e in episodes) {
            if (MediaProbe.cached(context, e.video.id) == null) MediaProbe.probe(context, g, e.video)
        }
    }

    val s = show
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                BackdropHeader(Tmdb.backdrop(s?.backdropPath), s?.name ?: "", height = 420.dp) {
                    Row(
                        Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Artwork(
                            Tmdb.posterLarge(s?.posterPath), s?.name ?: "",
                            Modifier.width(100.dp).height(150.dp).clip(CardShape),
                        )
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(s?.name ?: "", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                listOfNotNull(
                                    s?.year?.toString(), s?.network, s?.status,
                                    s?.rating?.let { Format.rating(it) },
                                ).joinToString("  ·  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.TextDim,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            if (!s?.genres.isNullOrBlank()) {
                                Text(s?.genres ?: "", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
                            }
                        }
                    }
                }
            }

            nextUp?.let { n ->
                item {
                    val v = n.video
                    val resume = v.positionMs > 30_000 && !v.watched
                    Button(
                        onClick = { Play.video(context, v.id) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text((if (resume) "Resume " else "Play ") + Format.episodeCode(v.season, v.episode))
                    }
                }
            }

            item {
                Text(
                    s?.overview ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Text.copy(alpha = 0.9f),
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            if (seasons.size > 1) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        items(seasons) { n ->
                            FilterChip(
                                selected = n == season,
                                onClick = { selected = n },
                                label = {
                                    Text(seasonMeta.firstOrNull { it.season == n }?.name ?: if (n == 0) "Specials" else "Season $n")
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Palette.AccentDim,
                                    selectedLabelColor = Palette.Accent,
                                ),
                            )
                        }
                    }
                }
            } else if (season != null) {
                item {
                    Text(
                        if (season == 0) "Specials" else "Season $season",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }

            items(episodes, key = { it.video.id }) { e -> EpisodeRow(e) }

            item { CastShelf(CastMember.parse(s?.cast ?: "")) }
            item {
                Text(
                    Tmdb.ATTRIBUTION,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = null),
                    color = Palette.TextFaint,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp)) {
            RoundIconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Box {
                RoundIconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = Color.White) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    if (season != null) {
                        val allWatched = episodes.isNotEmpty() && episodes.all { it.video.watched }
                        DropdownMenuItem(
                            text = { Text(if (allWatched) "Mark season as unwatched" else "Mark season as watched") },
                            onClick = {
                                menu = false
                                scope.launch { g.db.videos().setSeasonWatched(tmdbId, season, !allWatched) }
                            },
                        )
                    }
                    DropdownMenuItem(text = { Text("Fix match") }, onClick = { menu = false; fixing = true })
                    DropdownMenuItem(
                        text = { Text("Refresh details") },
                        onClick = {
                            menu = false
                            scope.launch {
                                runCatching {
                                    val scraper = Scraper(g)
                                    scraper.ensureShow(tmdbId, force = true)
                                    seasons.forEach { scraper.ensureSeason(tmdbId, it, force = true) }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (fixing) {
        FixMatchDialog(
            initialQuery = s?.name ?: "",
            initialYear = null,
            allowMovie = false,
            allowTv = true,
            startWithTv = true,
            onDismiss = { fixing = false },
            onPick = { r ->
                runCatching { Scraper(g).assignShow(oldShowId = tmdbId, videoId = null, tmdbId = r.id) }
                if (r.id != tmdbId) {
                    nav.popBackStack()
                    nav.navigate(Routes.show(r.id))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(e: VideoCard) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val v = e.video
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { Play.video(context, v.id) },
                onLongClick = { scope.launch { MemcApp.graph.db.videos().setWatched(v.id, !v.watched) } },
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Box(Modifier.width(148.dp).aspectRatio(16f / 9f).clip(CardShape)) {
            Artwork(Tmdb.still(e.backdrop), "E${v.episode ?: "?"}", Modifier.fillMaxSize())
            if (v.watched) WatchedTick(Modifier.align(Alignment.TopEnd).padding(4.dp))
            val p = Format.progress(v)
            if (p > 0f && !v.watched) ProgressStrip(p, Modifier.align(Alignment.BottomCenter))
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                "${v.episode ?: "?"}. ${e.epName ?: v.fileName.substringBeforeLast('.')}",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(e.airDate, Format.left(v)).joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = null),
                color = Palette.TextFaint,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (!e.epOverview.isNullOrBlank()) {
                Text(
                    e.epOverview,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TechBadges(v.tech, Modifier.padding(top = 6.dp))
        }
        if (v.uri.startsWith("smb://")) {
            IconButton(onClick = {
                Downloads.enqueue(context, v.uri, v.fileName, "${e.title} ${Format.episodeCode(v.season, v.episode)}")
                Toast.makeText(context, "Downloading - see Library → Downloads", Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Filled.Download, "Download to phone", tint = Palette.TextDim) }
        }
    }
}
