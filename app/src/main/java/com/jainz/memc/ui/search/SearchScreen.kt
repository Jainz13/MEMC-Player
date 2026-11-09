package com.jainz.memc.ui.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.jainz.memc.MemcApp
import com.jainz.memc.Routes
import com.jainz.memc.data.db.Kind
import com.jainz.memc.data.db.VideoCard
import com.jainz.memc.data.tmdb.Tmdb
import com.jainz.memc.ui.Play
import com.jainz.memc.ui.components.EmptyMessage
import com.jainz.memc.ui.components.PosterCard
import com.jainz.memc.ui.components.Shelf
import com.jainz.memc.ui.library.VideoRow
import com.jainz.memc.ui.theme.Palette
import kotlinx.coroutines.flow.flowOf

@Composable
fun SearchScreen(nav: NavController) {
    val g = MemcApp.graph
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val movies by remember { g.db.meta().movies() }.collectAsStateWithLifecycle(emptyList())
    val shows by remember { g.db.meta().shows() }.collectAsStateWithLifecycle(emptyList())
    val q = query.trim()
    val files by remember(q) {
        if (q.length >= 2) g.db.videos().search(q) else flowOf(emptyList<VideoCard>())
    }.collectAsStateWithLifecycle(emptyList())
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val movieHits = if (q.length >= 2) movies.filter {
        it.movie.title.contains(q, true) || (it.movie.originalTitle?.contains(q, true) == true) || it.movie.cast.contains(q, true)
    } else emptyList()
    val showHits = if (q.length >= 2) shows.filter { it.show.name.contains(q, true) || it.show.cast.contains(q, true) } else emptyList()
    val otherFiles = files.filter { !(it.video.kind == Kind.MOVIE && it.video.movieId != null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Movies, shows, actors, files") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Palette.Surface,
                    unfocusedContainerColor = Palette.Surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .focusRequester(focus),
            )
        }
        if (q.length >= 2 && movieHits.isEmpty() && showHits.isEmpty() && otherFiles.isEmpty()) {
            item { EmptyMessage("No results", "Nothing in your library matches \"$q\".") }
        }
        if (movieHits.isNotEmpty()) {
            item {
                Shelf("Movies") {
                    items(movieHits, key = { it.movie.tmdbId }) { m ->
                        PosterCard(Tmdb.poster(m.movie.posterPath), m.movie.title, subtitle = m.movie.year?.toString(), watched = m.watched) {
                            nav.navigate(Routes.movie(m.movie.tmdbId))
                        }
                    }
                }
            }
        }
        if (showHits.isNotEmpty()) {
            item {
                Shelf("TV Shows") {
                    items(showHits, key = { it.show.tmdbId }) { s ->
                        PosterCard(Tmdb.poster(s.show.posterPath), s.show.name, subtitle = s.show.year?.toString()) {
                            nav.navigate(Routes.show(s.show.tmdbId))
                        }
                    }
                }
            }
        }
        if (otherFiles.isNotEmpty()) {
            item {
                Text(
                    "Episodes & files",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 6.dp),
                )
            }
            items(otherFiles, key = { it.video.id }) { c -> VideoRow(c, onClick = { Play.video(context, c.video.id) }) }
        }
    }
}
