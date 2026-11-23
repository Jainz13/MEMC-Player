package com.jainz.memc.ui.movies

import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.jainz.memc.MemcApp
import com.jainz.memc.Routes
import com.jainz.memc.data.tmdb.Tmdb
import com.jainz.memc.ui.components.EmptyMessage
import com.jainz.memc.ui.components.PosterCard
import com.jainz.memc.ui.components.ScanStatus
import com.jainz.memc.ui.theme.Palette

enum class SortBy(val label: String) { ADDED("Recently added"), TITLE("Title"), YEAR("Release year"), RATING("Rating") }

@Composable
fun MoviesScreen(nav: NavController) {
    val g = MemcApp.graph
    val movies by remember { g.db.meta().movies() }.collectAsStateWithLifecycle(emptyList())
    var sort by rememberSaveable { mutableStateOf(SortBy.ADDED) }
    var filters by rememberSaveable { mutableStateOf(setOf<LibraryFilter>()) }

    val shown = remember(movies, sort, filters) {
        movies.filter { m -> filters.all { it.matches(m.tech, !m.watched) } }.let { list ->
            when (sort) {
                SortBy.ADDED -> list.sortedByDescending { it.addedAt }
                SortBy.TITLE -> list.sortedBy { it.movie.title.lowercase().removePrefix("the ") }
                SortBy.YEAR -> list.sortedByDescending { it.movie.year ?: 0 }
                SortBy.RATING -> list.sortedByDescending { it.movie.rating }
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(108.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.statusBarsPadding()) {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text("Movies", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        if (filters.isEmpty()) "${shown.size} movies" else "${shown.size} of ${movies.size} movies",
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                    )
                }
                FilterBar(
                    sort, { sort = it }, filters,
                    { f -> filters = if (f in filters) filters - f else filters + f },
                    Modifier.padding(top = 8.dp),
                )
                ScanStatus()
            }
        }
        if (movies.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyMessage(
                    "No movies yet",
                    "Movies appear here once your library is scanned and matched. Add a TMDB key in Settings to identify them.",
                )
            }
        } else if (shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyMessage("No matching movies", "None of your movies match these filters.") {
                    OutlinedButton(onClick = { filters = emptySet() }) { Text("Reset filters") }
                }
            }
        }
        items(shown, key = { it.movie.tmdbId }) { m ->
            PosterCard(
                url = Tmdb.poster(m.movie.posterPath),
                title = m.movie.title,
                subtitle = m.movie.year?.toString(),
                width = null,
                progress = if (m.durationMs > 0) m.positionMs.toFloat() / m.durationMs else 0f,
                watched = m.watched,
                onClick = { nav.navigate(Routes.movie(m.movie.tmdbId)) },
            )
        }
    }
}
