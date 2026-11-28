package com.jainz.memc.ui.explore

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.jainz.memc.MemcApp
import com.jainz.memc.Routes
import com.jainz.memc.data.tmdb.SearchResult as Title
import com.jainz.memc.data.tmdb.Tmdb
import com.jainz.memc.data.tmdb.TmdbClient
import com.jainz.memc.ui.components.Artwork
import com.jainz.memc.ui.components.EmptyMessage
import com.jainz.memc.ui.components.tvFocusRing
import com.jainz.memc.ui.theme.Palette
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// ---- Data ---------------------------------------------------------------------------------------------

/** First pages of the Discover lists, kept for half an hour so the tab opens instantly. */
private object CatalogCache {
    private val pages = HashMap<String, Pair<Long, TmdbClient.CatalogPage>>()
    private val genreLists = HashMap<Boolean, List<Pair<Int, String>>>()

    fun get(key: String): TmdbClient.CatalogPage? =
        pages[key]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000 }?.second

    fun put(key: String, page: TmdbClient.CatalogPage) {
        pages[key] = System.currentTimeMillis() to page
    }

    suspend fun genres(tv: Boolean): List<Pair<Int, String>> =
        genreLists[tv] ?: MemcApp.graph.tmdb.genres(tv).also { genreLists[tv] = it }
}

private fun key(kind: String, catalog: String, genre: Int?, page: Int) = "$kind/$catalog/${genre ?: 0}/$page"

private suspend fun loadPage(kind: String, catalog: String, genre: Int?, page: Int): TmdbClient.CatalogPage {
    val k = key(kind, catalog, genre, page)
    CatalogCache.get(k)?.let { return it }
    return MemcApp.graph.tmdb.catalog(kind, catalog, genre, page).also { CatalogCache.put(k, it) }
}

private fun kindLabel(kind: String) = when (kind) {
    TmdbClient.SERIES -> "Series"
    TmdbClient.ANIME -> "Anime"
    else -> "Movies"
}

private fun catalogLabel(catalog: String, kind: String) = when (catalog) {
    TmdbClient.TRENDING -> "Trending"
    TmdbClient.TOP_RATED -> "Top rated"
    TmdbClient.NOW -> if (kind == TmdbClient.MOVIES) "In cinema" else "On the air"
    TmdbClient.UPCOMING -> if (kind == TmdbClient.MOVIES) "Coming soon" else "Airing today"
    else -> "Popular"
}

/** TMDB ids already in the library, for the tick on posters. */
@Composable
private fun libraryIds(): Pair<Set<Long>, Set<Long>> {
    val g = MemcApp.graph
    val movies by remember { g.db.meta().movieIds() }.collectAsStateWithLifecycle(emptyList())
    val shows by remember { g.db.meta().showIds() }.collectAsStateWithLifecycle(emptyList())
    return remember(movies, shows) { movies.toSet() to shows.toSet() }
}

private fun open(nav: NavController, t: Title) = nav.navigate(Routes.releases(t.title, releaseQuery(t), t.isTv))

// ---- Shared pieces ----------------------------------------------------------------------------------

/** App mark and a rounded search field, as on top of both Discover screens. */
@Composable
private fun SearchHeader(
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onBack: (() -> Unit)?,
    menu: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        } else {
            Box(
                Modifier.padding(start = 4.dp, end = 10.dp).size(42.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Palette.Accent, Color(0xFFFF7A3D)))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PlayArrow, null, tint = Color(0xFF1A1206), modifier = Modifier.size(26.dp)) }
        }
        Row(
            Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(16.dp)).background(Palette.SurfaceHigh)
                .padding(start = 18.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Search movies and shows…", color = Palette.TextFaint, style = MaterialTheme.typography.bodyLarge)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.Text),
                    cursorBrush = SolidColor(Palette.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, "Clear", tint = Palette.TextDim) }
            } else {
                IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Search", tint = Palette.TextDim) }
            }
        }
        menu?.invoke()
    }
}

/** A poster with rounded corners, the "In cinema" pill and a tick for titles already in the library. */
@Composable
private fun DiscoverPoster(t: Title, inLibrary: Boolean, inCinema: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .tvFocusRing(shape)
            .clip(shape)
            .border(0.5.dp, Palette.Stroke, shape)
            .clickable(onClick = onClick),
    ) {
        Artwork(Tmdb.poster(t.posterPath), t.title, Modifier.fillMaxSize())
        if (inCinema) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                    .background(Color(0xCC0B0D10), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.ConfirmationNumber, null, tint = Color.White, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text("IN CINEMA", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            }
        }
        if (inLibrary) {
            Box(
                Modifier.align(Alignment.TopStart).padding(8.dp).size(24.dp).background(Palette.Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, "In your library", tint = Color(0xFF1A1206), modifier = Modifier.size(16.dp)) }
        }
    }
}

/** Results of a title search, as a grid of posters. */
@Composable
private fun SearchResults(nav: NavController, query: String, results: List<Title>?, ids: Pair<Set<Long>, Set<Long>>) {
    when {
        results == null -> Spinner()
        results.isEmpty() -> EmptyMessage("No title found", "TMDB has no movie or show called \"${query.trim()}\".") {
            OutlinedButton(onClick = { nav.navigate(Routes.releases(query.trim(), query.trim(), false)) }) {
                Text("Search the sources for these words")
            }
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(results.filter { it.posterPath != null }, key = { (if (it.isTv) "t" else "m") + it.id }) { t ->
                DiscoverPoster(t, if (t.isTv) t.id in ids.second else t.id in ids.first, false) { open(nav, t) }
            }
        }
    }
}

// ---- Discover home: rows of lists ---------------------------------------------------------------------------

private data class CatalogDef(val kind: String, val catalog: String, val title: String)

private val homeRows = listOf(
    CatalogDef(TmdbClient.MOVIES, TmdbClient.TRENDING, "Trending – Movies"),
    CatalogDef(TmdbClient.SERIES, TmdbClient.TRENDING, "Trending – Series"),
    CatalogDef(TmdbClient.MOVIES, TmdbClient.POPULAR, "Popular – Movies"),
    CatalogDef(TmdbClient.SERIES, TmdbClient.POPULAR, "Popular – Series"),
    CatalogDef(TmdbClient.MOVIES, TmdbClient.NOW, "In cinema"),
    CatalogDef(TmdbClient.SERIES, TmdbClient.NOW, "New episodes – Series"),
    CatalogDef(TmdbClient.MOVIES, TmdbClient.TOP_RATED, "Top rated – Movies"),
    CatalogDef(TmdbClient.SERIES, TmdbClient.TOP_RATED, "Top rated – Series"),
    CatalogDef(TmdbClient.ANIME, TmdbClient.POPULAR, "Popular – Anime"),
    CatalogDef(TmdbClient.MOVIES, TmdbClient.UPCOMING, "Coming soon – Movies"),
)

@Composable
fun ExploreScreen(nav: NavController) {
    val g = MemcApp.graph
    val scope = rememberCoroutineScope()
    val tmdbKey by g.settings.tmdbKey.flow.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Title>?>(null) }
    var searched by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val ids = libraryIds()

    fun search() {
        val words = query.trim()
        if (words.isBlank()) return
        if (tmdbKey.isBlank()) {
            nav.navigate(Routes.releases(words, words, false))
            return
        }
        searched = true
        results = null
        scope.launch { results = runCatching { g.tmdb.searchAny(words) }.getOrDefault(emptyList()) }
    }

    Column(Modifier.fillMaxSize()) {
        SearchHeader(
            query = query,
            onQuery = { query = it; if (it.isBlank()) searched = false },
            onSearch = { search() },
            onBack = null,
            menu = {
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More", tint = Palette.TextDim) }
                    DropdownMenu(open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(text = { Text("My sources") }, onClick = { open = false; nav.navigate(Routes.SOURCES) })
                        DropdownMenuItem(text = { Text("Search providers") }, onClick = { open = false; choosing = true })
                    }
                }
            },
        )
        when {
            searched -> SearchResults(nav, query, results, ids)
            tmdbKey.isBlank() -> EmptyMessage(
                "Discover needs TMDB",
                "Add your TMDB key in Settings to see trending movies and shows here. " +
                    "The search box above still searches the sources directly.",
            ) { OutlinedButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Text("Open Settings") } }
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                // About two and a half posters across, like the reference layout.
                val posterWidth = ((maxWidth - 32.dp - 24.dp) / 2.75f).coerceIn(96.dp, 190.dp)
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(homeRows, key = { it.kind + it.catalog }) { row -> CatalogRow(nav, row, posterWidth, ids) }
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
    }
    if (choosing) ProvidersDialog { choosing = false }
}

@Composable
private fun CatalogRow(nav: NavController, row: CatalogDef, posterWidth: Dp, ids: Pair<Set<Long>, Set<Long>>) {
    var items by remember(row) { mutableStateOf(CatalogCache.get(key(row.kind, row.catalog, null, 1))?.items) }
    var failed by remember(row) { mutableStateOf(false) }
    LaunchedEffect(row) {
        if (items != null) return@LaunchedEffect
        runCatching { loadPage(row.kind, row.catalog, null, 1) }
            .onSuccess { items = it.items }
            .onFailure { failed = true }
    }
    val list = items
    if (failed || list?.isEmpty() == true) return
    Column(Modifier.padding(top = 18.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .tvFocusRing(RoundedCornerShape(8.dp), 1f)
                .clickable { nav.navigate(Routes.discover(row.kind, row.catalog)) }
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("SEE ALL", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim, fontWeight = FontWeight.SemiBold)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.TextDim)
        }
        if (list == null) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(4) {
                    Box(Modifier.width(posterWidth).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(Palette.Surface))
                }
            }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list, key = { it.id }) { t ->
                    DiscoverPoster(
                        t,
                        inLibrary = if (t.isTv) t.id in ids.second else t.id in ids.first,
                        inCinema = row.kind == TmdbClient.MOVIES && row.catalog == TmdbClient.NOW,
                        modifier = Modifier.width(posterWidth),
                    ) { open(nav, t) }
                }
            }
        }
    }
}

// ---- Discover grid: one list with filters -------------------------------------------------------------------

@Composable
fun DiscoverScreen(nav: NavController, initialKind: String, initialCatalog: String) {
    val g = MemcApp.graph
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf(initialKind) }
    var catalog by rememberSaveable { mutableStateOf(initialCatalog) }
    var genre by rememberSaveable { mutableStateOf<Int?>(null) }
    var genreName by rememberSaveable { mutableStateOf("Default") }
    var genres by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    val shown = remember { mutableStateListOf<Title>() }
    var page by remember { mutableIntStateOf(0) }
    var totalPages by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Title>?>(null) }
    var searched by remember { mutableStateOf(false) }
    val ids = libraryIds()
    val grid = rememberLazyGridState()

    LaunchedEffect(kind) {
        genres = runCatching { CatalogCache.genres(kind != TmdbClient.MOVIES) }.getOrDefault(emptyList())
        // A genre from the other list may not exist here.
        if (genre != null && genres.none { it.first == genre }) {
            genre = null
            genreName = "Default"
        }
    }

    suspend fun loadMore() {
        if (loading || page >= totalPages) return
        loading = true
        val want = page + 1
        runCatching { loadPage(kind, catalog, genre, want) }
            .onSuccess { p ->
                val seen = shown.map { it.id }.toHashSet()
                shown += p.items.filter { seen.add(it.id) }
                page = want
                totalPages = p.totalPages
                error = null
            }
            .onFailure { error = it.message ?: "Couldn't load the list" }
        loading = false
    }

    LaunchedEffect(kind, catalog, genre) {
        shown.clear()
        page = 0
        totalPages = 1
        grid.scrollToItem(0)
        loadMore()
    }
    // Next page when the end comes into view.
    val nearEnd by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 9
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { nearEnd }.distinctUntilChanged().collect { if (it && shown.isNotEmpty()) loadMore() }
    }

    Column(Modifier.fillMaxSize()) {
        SearchHeader(
            query = query,
            onQuery = { query = it; if (it.isBlank()) searched = false },
            onSearch = {
                val words = query.trim()
                if (words.isNotBlank()) {
                    searched = true
                    results = null
                    scope.launch { results = runCatching { g.tmdb.searchAny(words) }.getOrDefault(emptyList()) }
                }
            },
            onBack = { nav.popBackStack() },
        )
        if (searched) {
            SearchResults(nav, query, results, ids)
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilterDropdown(
                kindLabel(kind),
                listOf(TmdbClient.MOVIES, TmdbClient.SERIES, TmdbClient.ANIME).map { it to kindLabel(it) },
                Modifier.weight(1f),
            ) { kind = it }
            FilterDropdown(
                catalogLabel(catalog, kind),
                TmdbClient.CATALOGS.map { it to catalogLabel(it, kind) },
                Modifier.weight(1f),
            ) { catalog = it }
            FilterDropdown(
                genreName,
                listOf("0" to "Default") + genres.map { it.first.toString() to it.second },
                Modifier.weight(1f),
            ) { id ->
                genre = id.toInt().takeIf { it != 0 }
                genreName = genres.firstOrNull { it.first == genre }?.second ?: "Default"
            }
        }
        if (shown.isEmpty() && error != null) {
            EmptyMessage("Couldn't load this list", error ?: "") {
                OutlinedButton(onClick = { scope.launch { loadMore() } }) { Text("Try again") }
            }
            return@Column
        }
        if (shown.isEmpty() && !loading && page > 0) {
            EmptyMessage("Nothing here", "No ${kindLabel(kind).lowercase()} in this list right now.")
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            state = grid,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(shown, key = { it.id }) { t ->
                DiscoverPoster(
                    t,
                    inLibrary = if (t.isTv) t.id in ids.second else t.id in ids.first,
                    inCinema = kind == TmdbClient.MOVIES && catalog == TmdbClient.NOW,
                ) { open(nav, t) }
            }
            if (loading) item(span = { GridItemSpan(maxLineSpan) }) { Spinner() }
        }
    }
}

/** An outlined, rounded button showing the current choice, opening a menu of the others. */
@Composable
private fun FilterDropdown(label: String, options: List<Pair<String, String>>, modifier: Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(14.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, Color(0x33FFFFFF)),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Row(Modifier.padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.ArrowDropDown, null)
            }
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { (value, name) ->
                DropdownMenuItem(
                    text = { Text(name, fontWeight = if (name == label) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { open = false; onPick(value) },
                )
            }
        }
    }
}
