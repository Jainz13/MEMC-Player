package com.jainz.memc.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.jainz.memc.data.scan.StorageAccess
import com.jainz.memc.ui.components.rememberStorageAccess
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.jainz.memc.MemcApp
import com.jainz.memc.Routes
import com.jainz.memc.core.CrashLog
import com.jainz.memc.data.db.MovieItem
import com.jainz.memc.data.db.ShowItem
import com.jainz.memc.data.scan.ScanWorker
import com.jainz.memc.data.tmdb.Tmdb
import com.jainz.memc.ui.Format
import com.jainz.memc.ui.Play
import com.jainz.memc.ui.components.BackdropHeader
import com.jainz.memc.ui.components.EmptyMessage
import com.jainz.memc.ui.components.LandscapeCard
import com.jainz.memc.ui.components.PosterCard
import com.jainz.memc.ui.components.ScanStatus
import com.jainz.memc.ui.components.StorageAccessCard
import com.jainz.memc.ui.components.Shelf
import com.jainz.memc.ui.theme.Palette
import kotlinx.coroutines.delay

private data class Featured(
    val title: String,
    val backdrop: String?,
    val meta: String,
    val overview: String?,
    val addedAt: Long,
    val route: String,
    val videoId: Long?,
    val resume: Boolean,
)

@Composable
fun HomeScreen(nav: NavController) {
    val g = MemcApp.graph
    val context = LocalContext.current
    val movies by remember { g.db.meta().movies() }.collectAsStateWithLifecycle(emptyList())
    val shows by remember { g.db.meta().shows() }.collectAsStateWithLifecycle(emptyList())
    val continueWatching by remember { g.db.videos().continueWatching() }.collectAsStateWithLifecycle(emptyList())
    val recentEpisodes by remember { g.db.videos().recentEpisodes() }.collectAsStateWithLifecycle(emptyList())
    val unidentified by remember { g.db.videos().unidentified() }.collectAsStateWithLifecycle(emptyList())
    val count by remember { g.db.videos().observeCount() }.collectAsStateWithLifecycle(-1)
    val tmdbKey by g.settings.tmdbKey.flow.collectAsStateWithLifecycle()
    val storage = rememberStorageAccess()
    val hadCrash = remember { CrashLog.read(context) != null }

    val recentMovies = remember(movies) { movies.sortedByDescending { it.addedAt } }
    val recentShows = remember(shows) { shows.sortedByDescending { it.addedAt } }
    val featured = remember(recentMovies, recentShows) { buildFeatured(recentMovies, recentShows) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            if (featured.isNotEmpty()) Hero(featured, nav) else PlainHeader(nav)
        }
        item { ScanStatus() }
        if (hadCrash) {
            item {
                Surface(
                    onClick = { nav.navigate(Routes.SETTINGS) },
                    shape = RoundedCornerShape(14.dp),
                    color = Palette.Surface,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("The app closed unexpectedly last time", style = MaterialTheme.typography.titleMedium, color = Palette.Danger)
                        Text(
                            "Tap to see the crash report in Settings and share it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.TextDim,
                        )
                    }
                }
            }
        }

        if (count == 0) {
            item {
                Column {
                    EmptyMessage(
                        title = "Build your library",
                        body = "Let the app read this phone's storage, or add a network share from your NAS. " +
                            "Movies and shows are recognised and get posters automatically.",
                    )
                    StorageAccessCard()
                    OutlinedButton(
                        onClick = { nav.navigate(Routes.LIBRARY) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) { Text("Add network share") }
                }
            }
        }
        if (tmdbKey.isBlank() && storage != StorageAccess.NONE) {
            item { TmdbSetupCard() }
        }

        if (continueWatching.isNotEmpty()) {
            item {
                Shelf("Continue Watching") {
                    items(continueWatching, key = { it.video.id }) { c ->
                        val v = c.video
                        LandscapeCard(
                            url = Tmdb.backdrop(c.backdrop),
                            title = c.title,
                            subtitle = listOfNotNull(
                                Format.episodeCode(v.season, v.episode).ifBlank { null },
                                Format.left(v),
                            ).joinToString(" · "),
                            progress = Format.progress(v),
                            onClick = { Play.video(context, v.id) },
                        )
                    }
                }
            }
        }
        if (recentMovies.isNotEmpty()) {
            item {
                Shelf("Recently Added Movies", onSeeAll = { nav.navigate(Routes.MOVIES) }) {
                    items(recentMovies.take(20), key = { it.movie.tmdbId }) { m ->
                        PosterCard(
                            url = Tmdb.poster(m.movie.posterPath),
                            title = m.movie.title,
                            subtitle = m.movie.year?.toString(),
                            progress = if (m.durationMs > 0) m.positionMs.toFloat() / m.durationMs else 0f,
                            watched = m.watched,
                            onClick = { nav.navigate(Routes.movie(m.movie.tmdbId)) },
                        )
                    }
                }
            }
        }
        if (recentEpisodes.isNotEmpty()) {
            item {
                Shelf("Recently Added Episodes") {
                    items(recentEpisodes, key = { it.video.id }) { c ->
                        val v = c.video
                        LandscapeCard(
                            url = Tmdb.backdrop(c.backdrop),
                            title = c.title,
                            subtitle = listOfNotNull(
                                Format.episodeCode(v.season, v.episode),
                                c.epName,
                            ).joinToString(" · "),
                            width = 220.dp,
                            progress = Format.progress(v),
                            watched = v.watched,
                            onClick = { v.showId?.let { nav.navigate(Routes.show(it)) } },
                        )
                    }
                }
            }
        }
        if (recentShows.isNotEmpty()) {
            item {
                Shelf("TV Shows", onSeeAll = { nav.navigate(Routes.SHOWS) }) {
                    items(recentShows.take(20), key = { it.show.tmdbId }) { s ->
                        PosterCard(
                            url = Tmdb.poster(s.show.posterPath),
                            title = s.show.name,
                            subtitle = "${s.episodeCount} episodes",
                            watched = s.episodeCount > 0 && s.watchedCount >= s.episodeCount,
                            onClick = { nav.navigate(Routes.show(s.show.tmdbId)) },
                        )
                    }
                }
            }
        }
        if (unidentified.isNotEmpty()) {
            item {
                Shelf("Unidentified", onSeeAll = { nav.navigate(Routes.UNIDENTIFIED) }) {
                    items(unidentified.take(15), key = { it.video.id }) { c ->
                        LandscapeCard(
                            url = null,
                            title = c.title,
                            subtitle = c.video.fileName,
                            width = 200.dp,
                            onClick = { Play.video(context, c.video.id) },
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

private fun buildFeatured(movies: List<MovieItem>, shows: List<ShowItem>): List<Featured> {
    val m = movies.filter { it.movie.backdropPath != null }.take(5).map {
        Featured(
            title = it.movie.title,
            backdrop = it.movie.backdropPath,
            meta = listOfNotNull(it.movie.year?.toString(), it.movie.genres.split(", ").take(2).joinToString(" · ").ifBlank { null }, Format.runtime(it.movie.runtime)).joinToString(" · "),
            overview = it.movie.overview,
            addedAt = it.addedAt,
            route = Routes.movie(it.movie.tmdbId),
            videoId = it.videoId,
            resume = it.positionMs > 30_000 && !it.watched,
        )
    }
    val s = shows.filter { it.show.backdropPath != null }.take(3).map {
        Featured(
            title = it.show.name,
            backdrop = it.show.backdropPath,
            meta = listOfNotNull(it.show.year?.toString(), it.show.genres.split(", ").take(2).joinToString(" · ").ifBlank { null }, "${it.episodeCount} episodes").joinToString(" · "),
            overview = it.show.overview,
            addedAt = it.addedAt,
            route = Routes.show(it.show.tmdbId),
            videoId = null,
            resume = false,
        )
    }
    return (m + s).sortedByDescending { it.addedAt }.take(6)
}

@Composable
private fun Hero(featured: List<Featured>, nav: NavController) {
    val context = LocalContext.current
    val pager = rememberPagerState { featured.size }
    LaunchedEffect(featured.size) {
        while (featured.size > 1) {
            delay(7000)
            pager.animateScrollToPage((pager.currentPage + 1) % featured.size)
        }
    }
    Box {
        HorizontalPager(pager) { page ->
            val f = featured[page]
            BackdropHeader(Tmdb.backdrop(f.backdrop), f.title, height = 520.dp) {
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                ) {
                    Text(f.title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        f.meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextDim,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (!f.overview.isNullOrBlank()) {
                        Text(
                            f.overview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.Text.copy(alpha = 0.85f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (f.videoId != null) {
                            Button(onClick = { Play.video(context, f.videoId) }) {
                                Icon(Icons.Filled.PlayArrow, null)
                                Spacer(Modifier.size(6.dp))
                                Text(if (f.resume) "Resume" else "Play")
                            }
                        }
                        FilledTonalButton(
                            onClick = { nav.navigate(f.route) },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color(0x33FFFFFF), contentColor = Color.White,
                            ),
                        ) {
                            Icon(Icons.Filled.Info, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Details")
                        }
                    }
                    if (featured.size > 1) {
                        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(featured.size) { i ->
                                Box(
                                    Modifier
                                        .size(if (i == pager.currentPage) 18.dp else 6.dp, 6.dp)
                                        .background(
                                            if (i == pager.currentPage) Palette.Accent else Palette.TextFaint,
                                            CircleShape,
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
        TopActions(nav, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun PlainHeader(nav: NavController) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Home", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        TopActions(nav)
    }
}

@Composable
private fun TopActions(nav: NavController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier.statusBarsPadding().padding(end = 8.dp, top = 4.dp)) {
        IconButton(onClick = { ScanWorker.enqueue(context) }) {
            Icon(Icons.Filled.Refresh, "Rescan library", tint = Color.White)
        }
        IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
            Icon(Icons.Filled.Settings, "Settings", tint = Color.White)
        }
    }
}

/** Step two of set-up, shown once storage access is given: connect TMDB for posters and details. */
@Composable
private fun TmdbSetupCard() {
    val g = MemcApp.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Palette.Surface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connect TMDB for posters and details", style = MaterialTheme.typography.titleMedium)
            Text(
                "1. Open TMDB and sign in (or create a free account).  2. Under Settings → API, copy the API key.  3. Paste it here.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextDim,
            )
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org/settings/api")))
                }
            }) { Text("Open TMDB") }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim(); status = null },
                label = { Text("TMDB API key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = key.isNotBlank(), onClick = {
                    scope.launch {
                        status = "Checking…"
                        val previous = g.settings.tmdbKey.value
                        g.settings.tmdbKey.set(key)
                        val problem = g.tmdb.verifyKey()
                        if (problem == null) {
                            g.db.videos().retryNotFound()
                            ScanWorker.enqueue(context)
                        } else {
                            g.settings.tmdbKey.set(previous)
                            status = problem
                        }
                    }
                }) { Text("Connect") }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim) }
            }
        }
    }
}
