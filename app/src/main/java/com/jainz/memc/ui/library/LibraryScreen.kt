package com.jainz.memc.ui.library

import com.jainz.memc.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.jainz.memc.MemcApp
import com.jainz.memc.Routes
import com.jainz.memc.data.db.SourceEntity
import com.jainz.memc.data.db.VideoCard
import com.jainz.memc.data.scan.LibraryScanner
import com.jainz.memc.data.scan.NameParser
import com.jainz.memc.data.scan.ScanWorker
import com.jainz.memc.data.scan.Scraper
import com.jainz.memc.data.smb.SmbEntry
import com.jainz.memc.data.smb.SmbPool
import com.jainz.memc.data.tmdb.Tmdb
import com.jainz.memc.ui.Format
import com.jainz.memc.ui.Play
import com.jainz.memc.ui.components.Artwork
import com.jainz.memc.ui.components.CardShape
import com.jainz.memc.ui.components.EmptyMessage
import com.jainz.memc.ui.components.DownloadsSection
import com.jainz.memc.ui.components.ExportsSection
import com.jainz.memc.ui.components.FixMatchDialog
import com.jainz.memc.ui.components.ProgressStrip
import com.jainz.memc.ui.components.ScanStatus
import com.jainz.memc.ui.components.SectionTitle
import com.jainz.memc.ui.components.StorageAccessCard
import com.jainz.memc.ui.components.TechBadges
import com.jainz.memc.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(nav: NavController) {
    val g = MemcApp.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by remember { g.db.sources().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val folders by remember { g.db.videos().folders() }.collectAsStateWithLifecycle(emptyList())
    val unidentified by remember { g.db.videos().unidentified() }.collectAsStateWithLifecycle(emptyList())
    val discovered = rememberSmbDiscovery()
    var dialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SourceEntity?>(null) }
    var prefill by remember { mutableStateOf<DiscoveredServer?>(null) }

    val phoneFolders = folders.filter { it.sourceId == LibraryScanner.DEVICE_SOURCE }
    val newServers = discovered.filter { d -> sources.none { it.host == d.host } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column {
                Text(
                    "Library",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.statusBarsPadding().padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                )
                ScanStatus()
            }
        }

        item { DownloadsSection() }
        if (!BuildConfig.TV) item { ExportsSection() }

        item { SectionTitle("Network shares", Modifier.padding(top = 12.dp)) }
        items(sources, key = { "s${it.id}" }) { s ->
            SourceRow(
                s,
                onOpen = { nav.navigate(Routes.smb(s.id, s.path)) },
                onEdit = { editing = s; prefill = null; dialog = true },
                onRescan = { ScanWorker.enqueue(context) },
                onRemove = {
                    scope.launch {
                        g.db.videos().deleteSource(s.id)
                        g.db.sources().delete(s.id)
                        g.db.meta().pruneMovies()
                        g.db.meta().pruneShows()
                    }
                },
            )
        }
        if (newServers.isNotEmpty()) {
            item {
                Column {
                Text(
                    "Found on your Wi-Fi",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(newServers) { d ->
                        AssistChip(
                            onClick = { editing = null; prefill = d; dialog = true },
                            label = { Text("${d.name} · ${d.host}") },
                            leadingIcon = { Icon(Icons.Filled.Wifi, null, Modifier.size(16.dp)) },
                        )
                    }
                }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { editing = null; prefill = null; dialog = true },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("Add network share")
            }
        }

        item { SectionTitle("This phone", Modifier.padding(top = 16.dp)) }
        item { StorageAccessCard() }
        items(phoneFolders, key = { "f${it.folder}" }) { f ->
            NavRow(
                icon = Icons.Filled.Folder,
                title = f.folder.trimEnd('/').substringAfterLast('/').ifBlank { "Internal storage" },
                subtitle = "${f.count} videos · ${f.folder.ifBlank { "/" }}",
                onClick = { nav.navigate(Routes.folder(f.sourceId, f.folder)) },
            )
        }

        if (unidentified.isNotEmpty()) {
            item { SectionTitle("Needs attention", Modifier.padding(top = 16.dp)) }
            item {
                NavRow(
                    icon = Icons.AutoMirrored.Filled.HelpOutline,
                    title = "Unidentified videos",
                    subtitle = "${unidentified.size} videos could not be matched",
                    onClick = { nav.navigate(Routes.UNIDENTIFIED) },
                )
            }
        }
    }

    if (dialog) {
        ShareDialog(existing = editing, prefill = prefill, onDismiss = { dialog = false })
    }
}

@Composable
private fun SourceRow(
    s: SourceEntity,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRescan: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(Icons.Filled.Dns)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(s.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "\\\\${s.host}\\${s.share}" + if (s.path.isNotBlank()) "\\${s.path.replace('/', '\\')}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextDim,
            )
            when {
                s.lastError != null -> Text(s.lastError, style = MaterialTheme.typography.bodySmall, color = Palette.Danger)
                !s.indexed -> Text("Browse only", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Options") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                if (s.indexed) DropdownMenuItem(text = { Text("Rescan") }, onClick = { menu = false; onRescan() })
                DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

@Composable
private fun IconTile(icon: ImageVector) {
    Box(
        Modifier.size(44.dp).background(Palette.SurfaceHigh, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Palette.Accent) }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.TextFaint)
    }
}

@Composable
private fun SubScreen(title: String, subtitle: String?, nav: NavController, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        content()
    }
}

/** A video list row: artwork if matched, otherwise a neutral tile. */
@Composable
fun VideoRow(c: VideoCard, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val v = c.video
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(CardShape)) {
            Artwork(Tmdb.still(c.backdrop), "", Modifier.fillMaxSize())
            val p = Format.progress(v)
            if (p > 0f && !v.watched) ProgressStrip(p, Modifier.align(Alignment.BottomCenter))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            val matched = v.movieId != null || v.showId != null
            Text(
                if (matched) c.title + (if (v.season != null) "  ${Format.episodeCode(v.season, v.episode)}" else "") else v.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    Format.size(v.size),
                    if (v.durationMs > 0) Format.clock(v.durationMs) else null,
                    if (matched) v.fileName else null,
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TechBadges(v.tech, Modifier.padding(top = 4.dp))
        }
        trailing()
    }
}

@Composable
fun FolderScreen(nav: NavController, sourceId: Long, folder: String) {
    val g = MemcApp.graph
    val context = LocalContext.current
    val videos by remember(sourceId, folder) { g.db.videos().inFolder(sourceId, folder) }.collectAsStateWithLifecycle(emptyList())
    SubScreen(folder.trimEnd('/').substringAfterLast('/').ifBlank { "Internal storage" }, folder, nav) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(videos, key = { it.video.id }) { c -> VideoRow(c, onClick = { Play.video(context, c.video.id) }) }
        }
    }
}

/** Live view of a network share - works even for folders that are not in the library. */
@Composable
fun SmbBrowseScreen(nav: NavController, sourceId: Long, path: String) {
    val g = MemcApp.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    val source by produceState<SourceEntity?>(null, sourceId) { value = g.db.sources().get(sourceId) }
    val listing by produceState<Result<List<SmbEntry>>?>(null, source, path, reload) {
        val s = source ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                g.smb.list(g.smb.location(s, path))
                    .filter { it.isDir || NameParser.isVideo(it.name) }
                    .sortedWith(compareBy<SmbEntry>({ !it.isDir }, { it.name.lowercase() }))
            }
        }
    }
    val s = source
    SubScreen(
        title = path.trimEnd('/').substringAfterLast('/').ifBlank { s?.name ?: "Network share" },
        subtitle = s?.let { "\\\\${it.host}\\${it.share}" + if (path.isNotBlank()) "\\" + path.replace('/', '\\') else "" },
        nav = nav,
    ) {
        val result = listing
        when {
            result == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            result.isFailure -> EmptyMessage(
                "Can't open this folder",
                SmbPool.friendlyError(result.exceptionOrNull() ?: Exception("Unknown error")),
            ) { OutlinedButton(onClick = { reload++ }) { Text("Try again") } }
            else -> {
                val entries = result.getOrDefault(emptyList())
                if (entries.isEmpty()) EmptyMessage("Empty folder", "No videos or sub-folders here.")
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(entries, key = { it.path }) { e ->
                        if (e.isDir) {
                            NavRow(Icons.Filled.Folder, e.name, "Folder", onClick = { nav.navigate(Routes.smb(sourceId, e.path)) })
                        } else {
                            NavRow(
                                Icons.Filled.PlayCircle,
                                e.name,
                                Format.size(e.size) + (NameParser.detectTech(e.name).takeIf { it.isNotBlank() }?.let { "  ·  " + it.replace("|", " ") } ?: ""),
                                onClick = {
                                    scope.launch {
                                        val src = s ?: return@launch
                                        val uri = g.smb.uriFor(src, e.path)
                                        val known = g.db.videos().byUri(uri)
                                        if (known != null) Play.video(context, known.id) else Play.uri(context, uri, e.name)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UnidentifiedScreen(nav: NavController) {
    val g = MemcApp.graph
    val context = LocalContext.current
    val videos by remember { g.db.videos().unidentified() }.collectAsStateWithLifecycle(emptyList())
    var fixing by remember { mutableStateOf<VideoCard?>(null) }
    SubScreen("Unidentified", "Tap ⋯ to pick the right title", nav) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(videos, key = { it.video.id }) { c ->
                VideoRow(c, onClick = { Play.video(context, c.video.id) }) {
                    IconButton(onClick = { fixing = c }) { Icon(Icons.Filled.MoreVert, "Identify") }
                }
            }
        }
    }
    fixing?.let { c ->
        val v = c.video
        FixMatchDialog(
            initialQuery = v.parsedTitle,
            initialYear = v.parsedYear,
            allowMovie = true,
            allowTv = true,
            startWithTv = v.season != null,
            onDismiss = { fixing = null },
            onPick = { r ->
                runCatching {
                    val scraper = Scraper(g)
                    if (r.isTv) scraper.assignShow(oldShowId = null, videoId = v.id, tmdbId = r.id)
                    else scraper.assignMovie(v.id, r.id)
                }
            },
        )
    }
}
