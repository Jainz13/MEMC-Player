package com.jainz.memc.ui.torrents

import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import com.jainz.memc.torrent.TorrentEngine
import com.jainz.memc.torrent.TorrentItem
import com.jainz.memc.torrent.TorrentPhase
import com.jainz.memc.ui.Format
import com.jainz.memc.ui.Play
import com.jainz.memc.ui.components.EmptyMessage
import com.jainz.memc.ui.components.SectionTitle
import com.jainz.memc.ui.theme.Palette
import kotlinx.coroutines.launch

/** Torrent list with add / sources buttons, shown in the Library tab. */
@Composable
fun TorrentsSection(nav: NavController) {
    val items by TorrentEngine.items.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Torrents", Modifier.weight(1f).padding(top = 12.dp))
            if (items.any { it.phase == TorrentPhase.DONE }) {
                TextButton(onClick = { TorrentEngine.clearFinished() }, modifier = Modifier.padding(top = 12.dp)) { Text("Clear finished") }
            }
        }
        items.forEach { TorrentRow(it) }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, null)
                Text("  Add torrent")
            }
            OutlinedButton(onClick = { nav.navigate(Routes.SOURCES) }) { Text("Sources") }
        }
    }
    if (adding) AddTorrentDialog { adding = false }
}

@Composable
private fun TorrentRow(item: TorrentItem) {
    val context = LocalContext.current
    Surface(
        color = Palette.Surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = if (item.phase == TorrentPhase.DOWNLOADING && item.totalBytes > 0) {
                listOf(
                    "${(item.progress * 100).toInt()}% of ${Format.size(item.totalBytes)}",
                    "${Format.size(item.bytesPerSecond)}/s",
                    "${item.peers} peers",
                ).joinToString(" · ")
            } else null
            Text(
                listOfNotNull(item.status, detail).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = when (item.phase) {
                    TorrentPhase.FAILED -> Palette.Danger
                    TorrentPhase.DONE -> Palette.Accent
                    else -> Palette.TextDim
                },
            )
            if (item.phase == TorrentPhase.DOWNLOADING || item.phase == TorrentPhase.MOVING || item.phase == TorrentPhase.PAUSED) {
                LinearProgressIndicator(
                    progress = { item.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Palette.Accent,
                    trackColor = Palette.SurfaceHigh,
                )
            }
            Row {
                when (item.phase) {
                    TorrentPhase.DOWNLOADING -> TextButton(onClick = { TorrentEngine.pause(item.id) }) { Text("Pause") }
                    TorrentPhase.PAUSED, TorrentPhase.FAILED ->
                        TextButton(onClick = { TorrentEngine.resume(context, item.id) }) { Text("Resume") }
                    else -> Unit
                }
                item.playPath?.let { path ->
                    TextButton(onClick = { Play.uri(context, Uri.fromFile(java.io.File(path)).toString(), item.name) }) { Text("Play") }
                }
                if (item.phase != TorrentPhase.MOVING) {
                    TextButton(onClick = { TorrentEngine.remove(context, item.id) }) {
                        Text(if (item.phase == TorrentPhase.DONE) "Hide" else "Remove")
                    }
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
            error = if (bytes == null) "Could not read that file" else TorrentEngine.addTorrentFile(context, bytes)
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
                error = TorrentEngine.addMagnet(context, magnet)
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
                                    if (item.link.startsWith("magnet:")) TorrentEngine.addMagnet(context, item.link)
                                    else TorrentEngine.addTorrentFile(context, Feeds.download(item.link))
                                }.getOrElse { it.message ?: "Could not add it" }
                                Toast.makeText(context, problem ?: "Added - see Library → Torrents", Toast.LENGTH_SHORT).show()
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
