package com.jainz.memc.ui.explore

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.jainz.memc.MemcApp
import com.jainz.memc.Routes
import com.jainz.memc.data.tmdb.SearchResult as Title
import com.jainz.memc.data.tmdb.Tmdb
import com.jainz.memc.torrent.Feed
import com.jainz.memc.torrent.FeedItem
import com.jainz.memc.torrent.Feeds
import com.jainz.memc.torrent.SearchOutcome
import com.jainz.memc.torrent.SearchResult
import com.jainz.memc.torrent.TorrentSearch
import com.jainz.memc.ui.Format
import com.jainz.memc.ui.components.EmptyMessage
import com.jainz.memc.ui.components.PosterCard
import com.jainz.memc.ui.components.Shelf
import com.jainz.memc.ui.theme.Palette
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// ---- Handing a link to another app ----------------------------------------------------------------

/** Opens a magnet link (or .torrent address) in whatever torrent app is installed. */
internal fun openLink(context: Context, link: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        copyLink(context, link, quiet = true)
        Toast.makeText(context, "No torrent app found to open this. The link was copied instead.", Toast.LENGTH_LONG).show()
    }
}

internal fun copyLink(context: Context, link: String, quiet: Boolean = false) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Magnet link", link))
    if (!quiet) {
        Toast.makeText(context, if (link.startsWith("magnet:")) "Magnet link copied" else "Link copied", Toast.LENGTH_SHORT).show()
    }
}

// ---- Explore: what is trending ------------------------------------------------------------------------

/** Trending lists, kept for half an hour so the tab opens instantly. */
private object Trending {
    var movies: List<Title> = emptyList()
    var shows: List<Title> = emptyList()
    var loadedAt = 0L
    val fresh get() = movies.isNotEmpty() && System.currentTimeMillis() - loadedAt < 30 * 60_000
}

private fun releaseQuery(t: Title) = if (t.isTv) t.title else listOfNotNull(t.title, t.year?.toString()).joinToString(" ")

@Composable
fun ExploreScreen(nav: NavController) {
    val g = MemcApp.graph
    val scope = rememberCoroutineScope()
    val tmdbKey by g.settings.tmdbKey.flow.collectAsStateWithLifecycle()
    var movies by remember { mutableStateOf(Trending.movies) }
    var shows by remember { mutableStateOf(Trending.shows) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<Title>?>(null) }
    var matching by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }

    LaunchedEffect(tmdbKey) {
        if (tmdbKey.isBlank() || Trending.fresh) return@LaunchedEffect
        loading = true
        error = null
        runCatching {
            coroutineScope {
                val m = async { g.tmdb.trending(tv = false) }
                val s = async { g.tmdb.trending(tv = true) }
                m.await() to s.await()
            }
        }.onSuccess { (m, s) ->
            Trending.movies = m; Trending.shows = s; Trending.loadedAt = System.currentTimeMillis()
            movies = m; shows = s
        }.onFailure { error = it.message ?: "Could not load what is trending" }
        loading = false
    }

    fun search() {
        val words = query.trim()
        if (words.isBlank()) return
        if (tmdbKey.isBlank()) {
            // Without TMDB there are no titles to pick from: search the sources directly.
            nav.navigate(Routes.releases(words, words, false))
            return
        }
        matching = true
        scope.launch {
            matches = runCatching { g.tmdb.searchAny(words) }.getOrDefault(emptyList())
            matching = false
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(
                Modifier.statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Explore", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { nav.navigate(Routes.SOURCES) }) { Icon(Icons.Filled.RssFeed, "My sources", tint = Palette.TextDim) }
                IconButton(onClick = { choosing = true }) { Icon(Icons.Filled.Tune, "Search providers", tint = Palette.TextDim) }
            }
        }
        item {
            TextField(
                value = query, onValueChange = { query = it; if (it.isBlank()) matches = null },
                placeholder = { Text("Find a movie or show…") },
                singleLine = true,
                shape = RoundedCornerShape(50),
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = ""; matches = null }) { Icon(Icons.Filled.Close, "Clear") }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Palette.SurfaceHigh, unfocusedContainerColor = Palette.SurfaceHigh,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        val found = matches
        when {
            matching -> item { Spinner() }
            found != null -> {
                if (found.isEmpty()) {
                    item {
                        EmptyMessage("No title found", "TMDB has no movie or show called \"${query.trim()}\".") {
                            OutlinedButton(onClick = { nav.navigate(Routes.releases(query.trim(), query.trim(), false)) }) {
                                Text("Search the sources for these words")
                            }
                        }
                    }
                }
                items(found, key = { (if (it.isTv) "t" else "m") + it.id }) { t ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { nav.navigate(Routes.releases(t.title, releaseQuery(t), t.isTv)) }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PosterCard(Tmdb.poster(t.posterPath), "", width = 56.dp) {
                            nav.navigate(Routes.releases(t.title, releaseQuery(t), t.isTv))
                        }
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(if (t.isTv) "TV show" else "Movie", t.year?.toString()).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                            )
                        }
                    }
                }
            }
            tmdbKey.isBlank() -> item {
                EmptyMessage(
                    "Trending needs TMDB",
                    "Add your TMDB key in Settings to see trending movies and shows here. " +
                        "The search box above still searches the sources directly.",
                ) { OutlinedButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Text("Open Settings") } }
            }
            loading && movies.isEmpty() -> item { Spinner() }
            error != null && movies.isEmpty() -> item { EmptyMessage("Couldn't load trending", error ?: "") }
            else -> {
                item {
                    Shelf("Trending movies") {
                        items(movies, key = { it.id }) { t ->
                            PosterCard(Tmdb.poster(t.posterPath), t.title, subtitle = t.year?.toString()) {
                                nav.navigate(Routes.releases(t.title, releaseQuery(t), false))
                            }
                        }
                    }
                }
                item {
                    Shelf("Trending TV shows") {
                        items(shows, key = { it.id }) { t ->
                            PosterCard(Tmdb.poster(t.posterPath), t.title, subtitle = t.year?.toString()) {
                                nav.navigate(Routes.releases(t.title, releaseQuery(t), true))
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Tap a title to see the available sources, with quality and size. " +
                            "Sources open in your torrent app. Only download what you have the right to.\n" + Tmdb.ATTRIBUTION,
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                    )
                }
            }
        }
    }
    if (choosing) ProvidersDialog { choosing = false }
}

@Composable
private fun Spinner() {
    Row(Modifier.fillMaxWidth().padding(40.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
}

// ---- Sources for one title ----------------------------------------------------------------------------

private val qualities = listOf("All", "4K", "1080p", "720p", "Other")

private fun qualityOf(name: String): String {
    val n = name.lowercase()
    return when {
        Regex("2160p|\\b4k\\b|\\buhd\\b").containsMatchIn(n) -> "4K"
        "1080" in n -> "1080p"
        "720" in n -> "720p"
        else -> "Other"
    }
}

/** Extra facts worth seeing at a glance, read from the release name. */
private fun tagsOf(name: String): List<String> {
    val n = name.lowercase()
    return listOfNotNull(
        "Dolby Vision".takeIf { Regex("\\b(dv|dovi)\\b|dolby.?vision").containsMatchIn(n) },
        "HDR10+".takeIf { "hdr10+" in n || "hdr10plus" in n },
        "HDR".takeIf { Regex("\\bhdr(10)?\\b").containsMatchIn(n) && "hdr10+" !in n && "hdr10plus" !in n },
        "Atmos".takeIf { "atmos" in n },
        "Remux".takeIf { "remux" in n },
        "HEVC".takeIf { Regex("x265|h\\.?265|hevc").containsMatchIn(n) },
    )
}

@Composable
fun ReleasesScreen(nav: NavController, title: String, startQuery: String, tv: Boolean) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf(startQuery) }
    var trigger by remember { mutableStateOf(0) }
    var outcome by remember { mutableStateOf<SearchOutcome?>(null) }
    var loading by remember { mutableStateOf(false) }
    var quality by rememberSaveable { mutableStateOf("All") }
    var bySize by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(trigger) {
        val words = query.trim()
        if (words.isBlank()) return@LaunchedEffect
        loading = true
        outcome = null
        val category = if (tv) TorrentSearch.Category.TV else TorrentSearch.Category.MOVIES
        var result = runCatching { TorrentSearch.search(context, words, category) }
            .getOrElse { SearchOutcome(emptyList(), listOf("the search")) }
        // Some titles (anime, documentaries) sit under another category: look again without one.
        if (result.results.isEmpty()) {
            result = runCatching { TorrentSearch.search(context, words, TorrentSearch.Category.ALL) }.getOrDefault(result)
        }
        outcome = result
        loading = false
    }

    val all = outcome?.results ?: emptyList()
    val counts = all.groupingBy { qualityOf(it.title) }.eachCount()
    val shown = all.filter { quality == "All" || qualityOf(it.title) == quality }
        .let { list -> if (bySize) list.sortedByDescending { it.sizeBytes } else list }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text(if (tv) "Search words (add S01E02 for one episode)" else "Search words") },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { trigger++ }),
            )
            TextButton(onClick = { trigger++ }) { Text("Go") }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            qualities.forEach { q ->
                val n = if (q == "All") all.size else counts[q] ?: 0
                FilterChip(selected = quality == q, onClick = { quality = q }, label = { Text("$q ($n)") })
            }
            FilterChip(selected = bySize, onClick = { bySize = !bySize }, label = { Text("Largest first") })
        }
        when {
            loading -> Spinner()
            outcome != null && all.isEmpty() -> EmptyMessage(
                "No sources found",
                "None of the providers has \"${query.trim()}\". Try fewer words, or switch on more providers on the Explore page." +
                    (outcome?.failed?.takeIf { it.isNotEmpty() }?.let { "\nNo answer from: ${it.joinToString(", ")}" } ?: ""),
            )
            outcome != null && shown.isEmpty() -> EmptyMessage("Nothing in $quality", "Pick another quality above.")
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            outcome?.failed?.takeIf { it.isNotEmpty() && all.isNotEmpty() }?.let { failed ->
                item {
                    Text(
                        "No answer from: ${failed.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
            items(shown, key = { it.link }) { r ->
                ReleaseRow(r)
                HorizontalDivider(color = Palette.SurfaceHigh)
            }
        }
    }
}

/** One source: quality and size up front; tap to open in a torrent app, or copy the magnet. */
@Composable
private fun ReleaseRow(r: SearchResult) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable { openLink(context, r.link) }.padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                qualityOf(r.title).let { if (it == "Other") "SD/?" else it },
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Palette.Accent,
                modifier = Modifier.background(Palette.AccentDim, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Text(
                if (r.sizeBytes > 0) Format.size(r.sizeBytes) else "size ?",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                (tagsOf(r.title) + listOfNotNull(if (r.seeders >= 0) "${r.seeders} seeders" else null, r.provider)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
            )
        }
        IconButton(onClick = { copyLink(context, r.link) }) {
            Icon(Icons.Filled.ContentCopy, "Copy magnet link", tint = Palette.TextDim, modifier = Modifier.size(20.dp))
        }
    }
}

/** Switches the built-in search providers on and off. */
@Composable
private fun ProvidersDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(TorrentSearch.enabled(context)) }
    val own = remember { Feeds.list(context).filter { it.needsQuery || it.torznab } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search providers") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TorrentSearch.providers.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            enabled = if (p.id in enabled) enabled - p.id else enabled + p.id
                            TorrentSearch.setEnabled(context, enabled)
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = p.id in enabled, onCheckedChange = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            Text(p.about, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                        }
                    }
                }
                Text(
                    if (own.isEmpty()) "Your own Jackett / Prowlarr addresses (My sources) are searched too once added."
                    else "Also searched: ${own.joinToString(", ") { it.name }} (My sources).",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}


/** Custom sources: feeds the user adds, browsed or searched; results open in a torrent app. */
@Composable
fun SourcesScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var feeds by remember { mutableStateOf(Feeds.list(context)) }
    var open by remember { mutableStateOf<Feed?>(null) }
    var adding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf(0) }
    var results by remember { mutableStateOf<List<FeedItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    val feed = open
    LaunchedEffect(feed, trigger) {
        results = null
        error = null
        if (feed == null || (feed.needsQuery && query.isBlank())) return@LaunchedEffect
        loading = true
        runCatching { Feeds.load(feed, query.trim()) }
            .onSuccess { results = it }
            .onFailure { error = it.message ?: "Could not load this source" }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (open != null) open = null else nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text(feed?.name ?: "Sources", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (feed == null) IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add source") }
        }
        if (feed == null) {
            if (feeds.isEmpty()) {
                EmptyMessage(
                    "No sources yet",
                    "Add the address of a torrent RSS feed, or a Torznab address from your own Jackett or Prowlarr. " +
                        "Every source gets a search box. A normal website address won't work - it has to be the feed's address.",
                ) { OutlinedButton(onClick = { adding = true }) { Text("Add source") } }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(feeds, key = { it.url }) { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { query = ""; open = f; trigger++ }.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                (if (f.needsQuery || f.torznab) "Search · " else "Feed · ") + f.url,
                                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = {
                            feeds = feeds - f
                            Feeds.save(context, feeds)
                        }) { Icon(Icons.Filled.Delete, "Remove source", tint = Palette.TextDim) }
                    }
                }
            }
        } else {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text(if (feed.needsQuery || feed.torznab) "Search" else "Search in this feed") },
                    singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { trigger++ }),
                )
                TextButton(onClick = { trigger++ }) { Text("Go") }
            }
            when {
                loading -> CircularProgressIndicator(Modifier.padding(32.dp).align(Alignment.CenterHorizontally))
                error != null -> EmptyMessage("Couldn't load this source", error ?: "")
                results == null && feed.needsQuery -> EmptyMessage("Search this source", "Type what you're looking for and tap Go.")
                results?.isEmpty() == true -> EmptyMessage(
                    "Nothing found",
                    if (query.isBlank()) "The source returned no torrents." else "No torrents match \"${query.trim()}\".",
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(results ?: emptyList()) { item ->
                    Column(
                        Modifier.fillMaxWidth().clickable { openLink(context, item.link) }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                if (item.sizeBytes > 0) Format.size(item.sizeBytes) else null,
                                if (item.seeders >= 0) "${item.seeders} seeders" else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add source") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        url, { url = it.trim() }, label = { Text("Feed address") },
                        placeholder = { Text("example.org/rss") },
                        maxLines = 4, modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "RSS feed address, or a Torznab address from Jackett / Prowlarr (with its API key). " +
                            "It opens straight after saving so you can see whether it works.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = url.length >= 4 && url.contains('.'), onClick = {
                    val address = Feeds.normalize(url)
                    val added = Feed(name.trim().ifBlank { Uri.parse(address).host ?: "Source" }, address)
                    feeds = feeds.filter { it.url != address } + added
                    Feeds.save(context, feeds)
                    adding = false
                    // Open it right away: shows its torrents, or what is wrong with the address.
                    query = ""
                    open = added
                    trigger++
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

