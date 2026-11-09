package com.jainz.memc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jainz.memc.ui.home.HomeScreen
import com.jainz.memc.ui.library.FolderScreen
import com.jainz.memc.ui.library.LibraryScreen
import com.jainz.memc.ui.library.SmbBrowseScreen
import com.jainz.memc.ui.library.UnidentifiedScreen
import com.jainz.memc.ui.movies.MovieDetailScreen
import com.jainz.memc.ui.movies.MoviesScreen
import com.jainz.memc.ui.search.SearchScreen
import com.jainz.memc.ui.settings.SettingsScreen
import com.jainz.memc.ui.shows.ShowDetailScreen
import com.jainz.memc.ui.shows.ShowsScreen
import com.jainz.memc.ui.theme.MemcTheme
import com.jainz.memc.ui.theme.Palette

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Palette.Canvas.toArgb()),
        )
        super.onCreate(savedInstanceState)
        setContent { MemcTheme { AppRoot() } }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Filled.Home),
    Tab(Routes.MOVIES, "Movies", Icons.Filled.Movie),
    Tab(Routes.SHOWS, "TV Shows", Icons.Filled.Tv),
    Tab(Routes.LIBRARY, "Library", Icons.Filled.Folder),
    Tab(Routes.SEARCH, "Search", Icons.Filled.Search),
)

object Routes {
    const val HOME = "home"
    const val MOVIES = "movies"
    const val SHOWS = "shows"
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val UNIDENTIFIED = "unidentified"
    fun movie(id: Long) = "movie/$id"
    fun show(id: Long) = "show/$id"
    fun folder(sourceId: Long, folder: String) = "folder/$sourceId?f=" + android.net.Uri.encode(folder)
    fun smb(sourceId: Long, path: String) = "smb/$sourceId?p=" + android.net.Uri.encode(path)
}

@Composable
private fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    val onTab = tabs.any { t -> current?.hierarchy?.any { it.route == t.route } == true }

    Scaffold(
        containerColor = Palette.Canvas,
        bottomBar = {
            if (onTab) {
                NavigationBar(containerColor = Palette.Canvas.copy(alpha = 0.96f), tonalElevation = 0.dp) {
                    tabs.forEach { t ->
                        val selected = current?.hierarchy?.any { it.route == t.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, t.label) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Palette.Accent,
                                selectedTextColor = Palette.Accent,
                                indicatorColor = Palette.AccentDim,
                                unselectedIconColor = Palette.TextDim,
                                unselectedTextColor = Palette.TextDim,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Screens draw their own top insets so artwork can run under the status bar.
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) { HomeScreen(nav) }
                composable(Routes.MOVIES) { MoviesScreen(nav) }
                composable(Routes.SHOWS) { ShowsScreen(nav) }
                composable(Routes.LIBRARY) { LibraryScreen(nav) }
                composable(Routes.SEARCH) { SearchScreen(nav) }
                composable(Routes.SETTINGS) { SettingsScreen(nav) }
                composable(Routes.UNIDENTIFIED) { UnidentifiedScreen(nav) }
                composable(
                    "movie/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { MovieDetailScreen(nav, it.arguments?.getLong("id") ?: 0L) }
                composable(
                    "show/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { ShowDetailScreen(nav, it.arguments?.getLong("id") ?: 0L) }
                composable(
                    "folder/{sourceId}?f={f}",
                    arguments = listOf(
                        navArgument("sourceId") { type = NavType.LongType },
                        navArgument("f") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) {
                    FolderScreen(nav, it.arguments?.getLong("sourceId") ?: 0L, it.arguments?.getString("f") ?: "")
                }
                composable(
                    "smb/{sourceId}?p={p}",
                    arguments = listOf(
                        navArgument("sourceId") { type = NavType.LongType },
                        navArgument("p") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) {
                    SmbBrowseScreen(nav, it.arguments?.getLong("sourceId") ?: 0L, it.arguments?.getString("p") ?: "")
                }
            }
        }
    }
}
