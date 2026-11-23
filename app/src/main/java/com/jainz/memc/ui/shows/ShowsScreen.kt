package com.jainz.memc.ui.shows

import com.jainz.memc.ui.movies.LibraryFilter
import com.jainz.memc.ui.movies.FilterBar
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
import com.jainz.memc.ui.movies.SortBy
import com.jainz.memc.ui.theme.Palette

@Composable
fun ShowsScreen(nav: NavController) {
    val g = MemcApp.graph
    val shows by remember { g.db.meta().shows() }.collectAsStateWithLifecycle(emptyList())
    var sort by rememberSaveable { mutableStateOf(SortBy.ADDED) }
    var filters by rememberSaveable { mutableStateOf(setOf<LibraryFilter>()) }

    val shown = remember(shows, sort, filters) {
        val list = shows.filter { s -> filters.all { it.matches(s.tech, s.watchedCount < s.episodeCount) } }
        when (sort) {
            SortBy.ADDED -> list.sortedByDescending { it.addedAt }
            SortBy.TITLE -> list.sortedBy { it.show.name.lowercase().removePrefix("the ") }
            SortBy.YEAR -> list.sortedByDescending { it.show.year ?: 0 }
            SortBy.RATING -> list.sortedByDescending { it.show.rating }
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
                    Text("TV Shows", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        if (filters.isEmpty()) "${shown.size} shows" else "${shown.size} of ${shows.size} shows",
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
        if (shows.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyMessage(
                    "No TV shows yet",
                    "Episodes named like Show.S01E02 or kept in Show/Season 1 folders are grouped here automatically.",
                )
            }
        } else if (shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyMessage("No matching shows", "None of your shows match these filters.") {
                    OutlinedButton(onClick = { filters = emptySet() }) { Text("Reset filters") }
                }
            }
        }
        items(shown, key = { it.show.tmdbId }) { s ->
            val left = s.episodeCount - s.watchedCount
            PosterCard(
                url = Tmdb.poster(s.show.posterPath),
                title = s.show.name,
                subtitle = if (left > 0) "$left unwatched" else "Watched",
                width = null,
                watched = left <= 0,
                onClick = { nav.navigate(Routes.show(s.show.tmdbId)) },
            )
        }
    }
}
