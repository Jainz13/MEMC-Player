package com.jainz.memc.ui.torrents

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.jainz.memc.Routes
import com.jainz.memc.torrent.Feed
import com.jainz.memc.torrent.FeedItem
import com.jainz.memc.torrent.Feeds
import com.jainz.memc.torrent.SearchOutcome
import com.jainz.memc.torrent.TorrentSearch
import com.jainz.memc.torrent.Torrents
import com.jainz.memc.torrent.TorrentItem
import com.jainz.memc.torrent.TorrentPhase
import com.jainz.memc.ui.Format
import com.jainz.memc.ui.Play
import com.jainz.memc.ui.components.EmptyMessage
import com.jainz.memc.ui.components.SectionTitle
import com.jainz.memc.ui.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The Torrents tab: a search page and a downloads page. */
@Composable
fun TorrentsScreen(nav: NavController) {
    val torrents by Torrents.items.collectAsStateWithLifecycle()
    // 0 = search, 1 = downloads. Opens on downloads when there are any.
    var page by rememberSaveable { mutableStateOf(-1) }
    if (page < 0) page = if (torrents.isEmpty()) 0 else 1
    var choosing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Torrents", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { nav.navigate(Routes.SOURCES) }) { Icon(Icons.Filled.RssFeed, "My sources", tint = Palette.TextDim) }
            IconButton(onClick = { choosing = true }) { Icon(Icons.Filled.Tune, "Search providers", tint = Palette.TextDim) }
        }
        TabRow(
            selectedTabIndex = page,
            containerColor = Palette.Canvas,
            contentColor = Palette.Accent,
            indicator = { positions ->
                TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[page]), color = Palette.Accent)
            },
        ) {
            val active = torrents.count { it.phase == TorrentPhase.DOWNLOADING || it.phase == TorrentPhase.MOVING }
            listOf("Search", if (active > 0) "Downloads ($active)" else "Downloads").forEachIndexed { i, label ->
                Tab(
                    selected = page == i, onClick = { page = i },
                    selectedContentColor = Palette.Accent, unselectedContentColor = Palette.TextDim,
                    text = { Text(label, style = MaterialTheme.typography.titleSmall) },
                )
            }
        }
        if (page == 0) SearchPage(onAdded = { }, onProviders = { choosing = true })
        else DownloadsPage(torrents)
    }
    if (choosing) ProvidersDialog { choosing = false }
}

private fun categoryIcon(c: TorrentSearch.Category): ImageVector = when (c) {
    TorrentSearch.Category.ALL -> Icons.Filled.Category
    TorrentSearch.Category.MOVIES -> Icons.Filled.Movie
    TorrentSearch.Category.TV -> Icons.Filled.Tv
    TorrentSearch.Category.ANIME -> Icons.Filled.AutoAwesome
}

@Composable
private fun SearchPage(onAdded: () -> Unit, onProviders: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(TorrentSearch.Category.ALL) }
    var outcome by remember { mutableStateOf<SearchOutcome?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var added by remember { mutableStateOf(setOf<String>()) }
    val showResults = searching || outcome != null

    fun closeSearch() {
        searchJob?.cancel()
        searching = false
        outcome = null
    }

    fun search() {
        val words = query.trim()
        if (words.isBlank()) return
        searchJob?.cancel()
        outcome = null
        searching = true
        searchJob = scope.launch {
            outcome = runCatching { TorrentSearch.search(context, words, category) }
                .getOrElse { SearchOutcome(emptyList(), listOf("the search")) }
            searching = false
        }
    }

    BackHandler(enabled = showResults) { closeSearch() }

    val field: @Composable (Modifier) -> Unit = { modifier ->
        TextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Search…") },
            singleLine = true,
            shape = RoundedCornerShape(50),
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty() || showResults) {
                    IconButton(onClick = { query = ""; closeSearch() }) { Icon(Icons.Filled.Close, "Clear search") }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Palette.SurfaceHigh, unfocusedContainerColor = Palette.SurfaceHigh,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            modifier = modifier,
        )
    }
    val chips: @Composable () -> Unit = {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TorrentSearch.Category.entries.forEach { c ->
                FilterChip(
                    selected = category == c,
                    onClick = { category = c; if (showResults) search() },
                    label = { Text(c.label) },
                    leadingIcon = { Icon(categoryIcon(c), null, Modifier.size(18.dp)) },
                )
            }
        }
    }

    if (!showResults) {
        // Landing page: logo, search box, categories.
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 48.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(Palette.AccentDim),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Search, null, Modifier.size(44.dp), tint = Palette.Accent) }
            Text("Torrent Search", style = MaterialTheme.typography.headlineSmall)
            field(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            chips()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = query.isNotBlank(), onClick = { search() }) {
                    Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                    Text("  Search")
                }
                OutlinedButton(onClick = onProviders) {
                    Icon(Icons.Filled.Tune, null, Modifier.size(18.dp))
                    Text("  Providers")
                }
            }
            Text(
                "Only download what you have the right to.",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint,
            )
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        item { field(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
        item { Box(Modifier.padding(top = 10.dp, bottom = 4.dp)) { chips() } }
        if (searching) {
            item {
                Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        outcome?.let { o ->
            item {
                Text(
                    "${o.results.size} results" + if (o.failed.isNotEmpty()) " · no answer from ${o.failed.joinToString(", ")}" else "",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            if (o.results.isEmpty()) {
                item {
                    EmptyMessage(
                        "Nothing found",
                        if (TorrentSearch.enabled(context).isEmpty()) "No search providers are switched on."
                        else "No provider had a torrent for \"${query.trim()}\" in ${category.label}.",
                    ) { OutlinedButton(onClick = onProviders) { Text("Providers") } }
                }
            }
        }
        items(outcome?.results ?: emptyList(), key = { it.link }) { r ->
            val isAdded = r.link in added
            Column(
                Modifier.fillMaxWidth().clickable(enabled = !isAdded) {
                    scope.launch {
                        val problem = runCatching {
                            if (r.link.startsWith("magnet:")) Torrents.addMagnet(context, r.link)
                            else Torrents.addTorrentFile(context, Feeds.download(r.link))
                        }.getOrElse { it.message ?: "Could not add it" }
                        if (problem == null) {
                            added = added + r.link
                            onAdded()
                        }
                        Toast.makeText(context, problem ?: "Added - see Downloads", Toast.LENGTH_SHORT).show()
                    }
                }.padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(r.title, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        if (isAdded) "Added" else null,
                        if (r.sizeBytes > 0) Format.size(r.sizeBytes) else null,
                        if (r.seeders >= 0) "${r.seeders} seeders" else null,
                        r.provider,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isAdded) Palette.Accent else Palette.TextDim,
                )
            }
            HorizontalDivider(color = Palette.SurfaceHigh)
        }
    }
}

/** Download list in the style of a download manager: filter tabs, rows that fill as they progress. */
@Composable
private fun DownloadsPage(torrents: List<TorrentItem>) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    val downloading = torrents.filter {
        it.phase == TorrentPhase.DOWNLOADING || it.phase == TorrentPhase.PAUSED || it.phase == TorrentPhase.MOVING
    }
    val finished = torrents.filter { it.phase == TorrentPhase.DONE }
    val failed = torrents.filter { it.phase == TorrentPhase.FAILED }
    val lists = listOf(torrents, downloading, finished, failed)
    val names = listOf("ALL", "DOWNLOADING", "FINISHED", "ERROR")

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScrollableTabRow(
                selectedTabIndex = filter,
                containerColor = Palette.Surface,
                contentColor = Palette.Text,
                edgePadding = 8.dp,
                divider = {},
                indicator = { positions ->
                    TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[filter]), color = Palette.Accent)
                },
            ) {
                names.forEachIndexed { i, name ->
                    Tab(
                        selected = filter == i, onClick = { filter = i },
                        selectedContentColor = Palette.Text, unselectedContentColor = Palette.TextDim,
                        text = { Text("$name (${lists[i].size})", style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
            val shown = lists[filter]
            if (shown.isEmpty()) {
                EmptyMessage(
                    if (filter == 0) "No downloads yet" else "Nothing here",
                    if (filter == 0) "Find something on the Search page, or tap + to add a magnet link or .torrent file." else "",
                )
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(shown, key = { it.id }) { item ->
                    TorrentRow(item)
                    HorizontalDivider(color = Palette.Canvas)
                }
                if (filter != 1 && finished.isNotEmpty()) {
                    item {
                        TextButton(onClick = { Torrents.clearFinished(context) }, modifier = Modifier.padding(8.dp)) {
                            Text("Clear finished from the list")
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { adding = true },
            containerColor = Palette.Accent, contentColor = Palette.Canvas,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Icon(Icons.Filled.Add, "Add torrent") }
    }
    if (adding) AddTorrentDialog { adding = false }
}

private fun eta(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600}h${(seconds % 3600) / 60}m"
    seconds >= 60 -> "${seconds / 60}m${seconds % 60}s"
    else -> "${seconds}s"
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

@Composable
private fun TorrentRow(item: TorrentItem) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val done = item.phase == TorrentPhase.DONE
    val progress = if (done) 1f else item.progress.coerceIn(0f, 1f)
    val fill = when (item.phase) {
        TorrentPhase.FAILED -> Palette.Danger.copy(alpha = 0.18f)
        TorrentPhase.DONE -> Palette.Accent.copy(alpha = 0.22f)
        else -> Palette.Accent.copy(alpha = 0.30f)
    }
    Row(
        Modifier.fillMaxWidth()
            .background(Palette.Surface)
            // The row itself is the progress bar.
            .drawBehind { drawRect(fill, size = Size(size.width * progress, size.height)) }
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (done) Icons.Filled.DownloadDone else Icons.Filled.Download, null,
            tint = if (item.phase == TorrentPhase.FAILED) Palette.Danger else Palette.TextDim,
            modifier = Modifier.size(28.dp),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Row {
                Text(
                    "${(progress * 100).toInt()}%" + if (item.totalBytes > 0) " | ${Format.size(item.totalBytes)}" else "",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, modifier = Modifier.weight(1f),
                )
                val right = when (item.phase) {
                    TorrentPhase.DOWNLOADING -> if (item.totalBytes > 0) {
                        val left = (item.totalBytes - item.doneBytes).coerceAtLeast(0)
                        listOfNotNull(
                            "${Format.size(item.bytesPerSecond)}/s↓ ${Format.size(item.uploadBytesPerSecond)}/s↑",
                            if (item.bytesPerSecond > 0) eta(left / item.bytesPerSecond) else null,
                        ).joinToString("  ")
                    } else ""
                    TorrentPhase.PAUSED -> "Stopped"
                    TorrentPhase.MOVING -> "Moving to share"
                    TorrentPhase.DONE -> "Completed"
                    TorrentPhase.FAILED -> "Error"
                }
                Text(right, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
            // What it is doing, when that is more than plain downloading.
            val note = when {
                item.phase == TorrentPhase.DOWNLOADING && item.status == "Downloading" -> null
                item.phase == TorrentPhase.PAUSED -> null
                item.phase == TorrentPhase.DOWNLOADING -> item.status + if (item.totalBytes > 0) " · ${item.peers} peers" else ""
                else -> item.status
            }
            if (note != null) {
                Text(
                    note, style = MaterialTheme.typography.bodySmall,
                    color = if (item.phase == TorrentPhase.FAILED) Palette.Danger else Palette.TextFaint,
                )
            }
        }
        when (item.phase) {
            TorrentPhase.DOWNLOADING ->
                IconButton(onClick = { Torrents.pause(context, item.id) }) { Icon(Icons.Filled.PauseCircleOutline, "Pause", Modifier.size(30.dp)) }
            TorrentPhase.PAUSED, TorrentPhase.FAILED ->
                IconButton(onClick = { Torrents.resume(context, item.id) }) { Icon(Icons.Filled.PlayCircleOutline, "Resume", Modifier.size(30.dp)) }
            else -> Unit
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                item.playPath?.let { path ->
                    DropdownMenuItem(text = { Text("Play") }, onClick = {
                        menu = false
                        Play.uri(context, Uri.fromFile(java.io.File(path)).toString(), item.name)
                    })
                }
                val report = Torrents.engineReport
                if (item.phase == TorrentPhase.FAILED && report != null) {
                    DropdownMenuItem(text = { Text("Copy error report") }, onClick = {
                        menu = false
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("MEMC torrent report", report))
                        Toast.makeText(context, "Report copied - paste it where you report the problem", Toast.LENGTH_LONG).show()
                    })
                }
                if (item.phase != TorrentPhase.MOVING) {
                    DropdownMenuItem(
                        text = { Text(if (done) "Remove from list" else "Remove and delete files") },
                        onClick = { menu = false; Torrents.remove(context, item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddTorrentDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var magnet by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            error = if (bytes == null) "Could not read that file" else Torrents.addTorrentFile(context, bytes)
            if (error == null) onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add torrent") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = magnet, onValueChange = { magnet = it; error = null },
                    label = { Text("Magnet link") }, placeholder = { Text("magnet:?xt=urn:btih:…") },
                    maxLines = 4, modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { picker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) }) {
                    Text("Choose a .torrent file instead")
                }
                error?.let { Text(it, color = Palette.Danger, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Only download what you have the right to. Finished downloads can be moved to a network share (Settings → Torrents).",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = magnet.isNotBlank(), onClick = {
                error = Torrents.addMagnet(context, magnet)
                if (error == null) onDismiss()
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Custom sources: feeds the user adds, browsed or searched, with results sent to the client. */
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
                        Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                val problem = runCatching {
                                    if (item.link.startsWith("magnet:")) Torrents.addMagnet(context, item.link)
                                    else Torrents.addTorrentFile(context, Feeds.download(item.link))
                                }.getOrElse { it.message ?: "Could not add it" }
                                Toast.makeText(context, problem ?: "Added to your torrents", Toast.LENGTH_SHORT).show()
                            }
                        }.padding(horizontal = 20.dp, vertical = 10.dp)
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
