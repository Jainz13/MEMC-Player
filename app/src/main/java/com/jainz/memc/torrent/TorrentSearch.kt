package com.jainz.memc.torrent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SearchResult(
    val title: String,
    /** magnet: link or the http address of a .torrent file */
    val link: String,
    val sizeBytes: Long,
    val seeders: Int,
    val provider: String,
)

class SearchOutcome(val results: List<SearchResult>, val failed: List<String>)

/**
 * Built-in search providers. The list of sites and how each one is queried follows the
 * open-source TorrentSearch app (github.com/prajwalch/TorrentSearch, MIT licence); only the
 * ones with a proper data interface (JSON or RSS) and relevant to video are included.
 */
object TorrentSearch {

    enum class Category(val label: String) { ALL("All"), MOVIES("Movies"), TV("TV shows"), ANIME("Anime") }

    class Provider(
        val id: String,
        val name: String,
        val about: String,
        val onByDefault: Boolean,
        /** Categories this provider can answer; it is skipped for the others. */
        val covers: Set<Category>,
        val search: (String, Category) -> List<SearchResult>,
    )

    private val everything = Category.entries.toSet()

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()

    // Public trackers added to magnet links built from a bare hash (ngosang/trackerslist "best").
    private val trackers = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.qu.ax:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://opentracker.io:6969/announce",
        "udp://open.tracker.cl:1337/announce",
    )

    private fun magnetHash(link: String): String? =
        Regex("xt=urn:btih:([A-Za-z0-9]+)").find(link)?.groupValues?.get(1)?.lowercase()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun magnet(hash: String, name: String) =
        "magnet:?xt=urn:btih:${hash.trim().lowercase()}&dn=${enc(name)}" + trackers.joinToString("") { "&tr=${enc(it)}" }

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).header("User-Agent", "MEMC-Player").header("Accept", "application/json, */*").build())
            .execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("error ${resp.code}")
                resp.body?.string() ?: throw IOException("empty answer")
            }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    private fun long(o: JSONObject, key: String): Long = o.opt(key)?.toString()?.toDoubleOrNull()?.toLong() ?: 0L
    private fun int(o: JSONObject, key: String): Int = o.opt(key)?.toString()?.toDoubleOrNull()?.toInt() ?: -1

    /** "1.4 GB" → bytes. */
    private fun parseSize(text: String?): Long {
        val m = Regex("([0-9.]+)\\s*([KMGT])i?B", RegexOption.IGNORE_CASE).find(text ?: "") ?: return 0L
        val n = m.groupValues[1].toDoubleOrNull() ?: return 0L
        val power = "KMGT".indexOf(m.groupValues[2].uppercase()) + 1
        return (n * Math.pow(1024.0, power.toDouble())).toLong()
    }

    val providers: List<Provider> = listOf(
        Provider("knaben", "Knaben", "Searches many torrent sites at once (movies, TV, anime)", true, everything) { q, cat ->
            val categories = when (cat) {
                Category.MOVIES -> listOf(3000000)
                Category.TV -> listOf(2000000)
                Category.ANIME -> listOf(6000000)
                Category.ALL -> listOf(2000000, 3000000, 6000000)
            }
            val body = JSONObject()
                .put("query", q).put("size", 100)
                .put("order_by", "seeders").put("order_direction", "desc")
                .put("hide_unsafe", true).put("hide_xxx", true)
                .put("categories", JSONArray(categories))
            val text = http.newCall(
                Request.Builder().url("https://api.knaben.org/v1")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("error ${resp.code}")
                resp.body?.string() ?: throw IOException("empty answer")
            }
            (JSONObject(text).optJSONArray("hits") ?: JSONArray()).objects().mapNotNull { o ->
                val title = o.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val link = o.optString("magnetUrl").takeIf { it.startsWith("magnet:") }
                    ?: o.optString("hash").takeIf { it.length == 40 }?.let { magnet(it, title) }
                    ?: return@mapNotNull null
                SearchResult(title, link, long(o, "bytes"), int(o, "seeders"), "Knaben")
            }
        },
        Provider("yts", "YTS", "Movies in small file sizes", true, setOf(Category.ALL, Category.MOVIES)) { q, _ ->
            val text = get("https://movies-api.accel.li/api/v2/list_movies.json?query_term=${enc(q)}&limit=50")
            val movies = JSONObject(text).optJSONObject("data")?.optJSONArray("movies") ?: JSONArray()
            movies.objects().flatMap { movie ->
                val title = movie.optString("title_long").ifBlank { movie.optString("title") }
                (movie.optJSONArray("torrents") ?: JSONArray()).objects().mapNotNull { t ->
                    val hash = t.optString("hash").takeIf { it.length == 40 } ?: return@mapNotNull null
                    val name = listOf(title, t.optString("quality"), t.optString("type"), t.optString("video_codec"))
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    SearchResult(name, magnet(hash, name), long(t, "size_bytes"), int(t, "seeds"), "YTS")
                }
            }
        },
        Provider("torrentscsv", "Torrents-CSV", "Open database of torrents", true, setOf(Category.ALL)) { q, _ ->
            val text = get("https://torrents-csv.com/service/search?q=${enc(q)}")
            (JSONObject(text).optJSONArray("torrents") ?: JSONArray()).objects().mapNotNull { o ->
                val title = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val hash = o.optString("infohash").takeIf { it.length == 40 } ?: return@mapNotNull null
                SearchResult(title, magnet(hash, title), long(o, "size_bytes"), int(o, "seeders"), "Torrents-CSV")
            }
        },
        Provider("nyaa", "Nyaa", "Anime", true, setOf(Category.ALL, Category.ANIME)) { q, _ ->
            Feeds.loadBlocking(Feed("Nyaa", "https://nyaa.si/?page=rss&c=1_0&f=0&q={query}"), q)
                .map { SearchResult(it.title, it.link, it.sizeBytes, it.seeders, "Nyaa") }
        },
        Provider("archive", "Internet Archive", "Public-domain and freely shared films", false, setOf(Category.ALL, Category.MOVIES)) { q, _ ->
            val text = get(
                "https://archive.org/advancedsearch.php?q=" + enc("title:($q) AND mediatype:(movies)") +
                    "&fl[]=title,item_size,btih&rows=100&page=1&output=json"
            )
            (JSONObject(text).optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()).objects().mapNotNull { o ->
                val title = o.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val hash = o.optString("btih").takeIf { it.length == 40 } ?: return@mapNotNull null
                SearchResult(title, magnet(hash, title), long(o, "item_size"), -1, "Internet Archive")
            }
        },
        Provider("tpb", "The Pirate Bay", "Large general index, video category. Off by default: unmoderated uploads", false, everything) { q, cat ->
            val id = when (cat) {
                Category.MOVIES -> "201,207"
                Category.TV -> "205,208"
                else -> "200"
            }
            JSONArray(get("https://apibay.org/q.php?q=${enc(q)}&cat=$id")).objects().mapNotNull { o ->
                val title = o.optString("name").takeIf { it.isNotBlank() && it != "No results returned" } ?: return@mapNotNull null
                val hash = o.optString("info_hash").takeIf { it.length == 40 } ?: return@mapNotNull null
                SearchResult(title, magnet(hash, title), long(o, "size"), int(o, "seeders"), "The Pirate Bay")
            }
        },
    )

    // ---- Which providers are switched on ------------------------------------------------------------

    private const val PREFS = "torrent_search"

    fun enabled(context: Context): Set<String> {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("enabled", null)
        return saved?.toSet() ?: providers.filter { it.onByDefault }.map { it.id }.toSet()
    }

    fun setEnabled(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("enabled", ids).apply()
    }

    /**
     * Asks every switched-on provider, and the user's own searchable sources, at the same time.
     * A provider that fails is listed in [SearchOutcome.failed]; the others still answer.
     */
    suspend fun search(context: Context, query: String, category: Category = Category.ALL): SearchOutcome = coroutineScope {
        val on = enabled(context)
        val jobs = providers.filter { it.id in on && category in it.covers }.map { p ->
            async(Dispatchers.IO) { p.name to runCatching { p.search(query, category) } }
        } + Feeds.list(context).filter { it.needsQuery || it.torznab }.map { feed ->
            async(Dispatchers.IO) {
                feed.name to runCatching {
                    Feeds.loadBlocking(feed, query).map { SearchResult(it.title, it.link, it.sizeBytes, it.seeders, feed.name) }
                }
            }
        }
        val answers = jobs.awaitAll()
        val seen = HashSet<String>()
        val results = withContext(Dispatchers.Default) {
            answers.flatMap { it.second.getOrDefault(emptyList()) }
                .sortedByDescending { it.seeders }
                .filter { r -> seen.add(magnetHash(r.link) ?: r.link) }
        }
        SearchOutcome(results, answers.filter { it.second.isFailure }.map { it.first })
    }
}
