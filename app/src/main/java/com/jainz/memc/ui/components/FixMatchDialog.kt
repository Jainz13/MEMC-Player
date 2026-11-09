package com.jainz.memc.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jainz.memc.MemcApp
import com.jainz.memc.data.tmdb.SearchResult
import com.jainz.memc.data.tmdb.Tmdb
import com.jainz.memc.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * Lets the user search TMDB and pick the right title when automatic matching got it wrong.
 * [onPick] receives the chosen result (isTv tells movie vs show).
 */
@Composable
fun FixMatchDialog(
    initialQuery: String,
    initialYear: Int?,
    allowMovie: Boolean,
    allowTv: Boolean,
    startWithTv: Boolean,
    onDismiss: () -> Unit,
    onPick: suspend (SearchResult) -> Unit,
) {
    val g = MemcApp.graph
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery) }
    var year by remember { mutableStateOf(initialYear?.toString() ?: "") }
    var tv by remember { mutableStateOf(startWithTv && allowTv || !allowMovie) }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var trigger by remember { mutableStateOf(0) }

    LaunchedEffect(trigger, tv) {
        if (query.isBlank()) return@LaunchedEffect
        loading = true
        error = null
        try {
            val y = year.toIntOrNull()
            results = if (tv) g.tmdb.searchTv(query.trim(), y) else g.tmdb.searchMovie(query.trim(), y)
            if (results.isEmpty()) error = "No matches - try a shorter title or clear the year"
        } catch (e: Exception) {
            error = e.message ?: "Search failed"
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Fix match") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (allowMovie && allowTv) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !tv, onClick = { tv = false },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("Movie") }
                        SegmentedButton(
                            selected = tv, onClick = { tv = true },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("TV Show") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text("Title") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { trigger++ }),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = year, onValueChange = { year = it.filter(Char::isDigit).take(4) },
                        label = { Text("Year") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { trigger++ }),
                        modifier = Modifier.width(84.dp),
                    )
                }
                TextButton(onClick = { trigger++ }) { Text("Search") }
                Box(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 360.dp)) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        error != null -> Text(error ?: "", color = Palette.TextDim, modifier = Modifier.align(Alignment.Center))
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(results, key = { it.id }) { r ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { scope.launch { onPick(r); onDismiss() } }
                                        .padding(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Artwork(
                                        Tmdb.poster(r.posterPath), r.title,
                                        Modifier.width(52.dp).height(78.dp).clip(CardShape),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            r.title + (r.year?.let { " ($it)" } ?: ""),
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            r.overview ?: "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Palette.TextDim,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}
