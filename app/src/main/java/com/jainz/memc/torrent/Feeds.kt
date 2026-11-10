package com.jainz.memc.torrent

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** A source the user added: a torrent RSS feed or a Torznab search address. */
data class Feed(val name: String, val url: String) {
    /** Addresses containing {query} (or a Torznab "q=" parameter) can be searched. */
    val searchable: Boolean get() = url.contains("{query}")
}

data class FeedItem(
    val title: String,
    /** magnet: link or the http address of a .torrent file */
    val link: String,
    val sizeBytes: Long,
    val seeders: Int,
)

/**
 * Custom sources. The app ships with none: each source is a standard feed address the user
 * adds (RSS with magnet/.torrent links, or Torznab as used by Jackett and Prowlarr).
 */
object Feeds {
    private const val PREFS = "torrent_feeds"
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    fun list(context: Context): List<Feed> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("feeds", "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            List(a.length()) { i -> a.getJSONObject(i).let { Feed(it.getString("name"), it.getString("url")) } }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, feeds: List<Feed>) {
        val a = JSONArray()
        feeds.forEach { a.put(JSONObject().put("name", it.name).put("url", it.url)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("feeds", a.toString()).apply()
    }

    suspend fun load(feed: Feed, query: String): List<FeedItem> = withContext(Dispatchers.IO) {
        val url = feed.url.replace("{query}", URLEncoder.encode(query, "UTF-8"))
        http.newCall(Request.Builder().url(url).header("Accept", "application/rss+xml, application/xml, text/xml").build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("The source answered with error ${resp.code}")
            parse(resp.body?.byteStream() ?: throw IOException("Empty answer from the source"))
        }
    }

    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Could not fetch the .torrent file (error ${resp.code})")
            resp.body?.bytes() ?: throw IOException("Empty .torrent file")
        }
    }

    private fun parse(stream: java.io.InputStream): List<FeedItem> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)
        val items = ArrayList<FeedItem>()
        var inItem = false
        var title = ""
        var link = ""
        var magnet = ""
        var enclosure = ""
        var size = 0L
        var seeders = -1
        var text = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    text = ""
                    val tag = parser.name.lowercase()
                    when {
                        tag == "item" || tag == "entry" -> {
                            inItem = true
                            title = ""; link = ""; magnet = ""; enclosure = ""; size = 0L; seeders = -1
                        }
                        inItem && tag == "enclosure" -> {
                            enclosure = parser.getAttributeValue(null, "url") ?: ""
                            parser.getAttributeValue(null, "length")?.toLongOrNull()?.let { if (it > 0) size = it }
                        }
                        inItem && tag == "link" -> parser.getAttributeValue(null, "href")?.let { if (link.isBlank()) link = it }
                        inItem && tag.endsWith(":attr") -> {
                            val value = parser.getAttributeValue(null, "value") ?: ""
                            when (parser.getAttributeValue(null, "name")?.lowercase()) {
                                "magneturl" -> magnet = value
                                "size" -> value.toLongOrNull()?.let { size = it }
                                "seeders" -> value.toIntOrNull()?.let { seeders = it }
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> text += parser.text
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.lowercase()
                    val value = text.trim()
                    if (inItem) {
                        when {
                            tag == "title" -> title = value
                            tag == "link" && value.isNotBlank() -> link = value
                            tag == "guid" && value.startsWith("magnet:") -> magnet = value
                            tag == "size" || tag.endsWith(":contentlength") -> value.toLongOrNull()?.let { size = it }
                            tag.endsWith(":seeds") || tag.endsWith(":seeders") || tag == "seeders" -> value.toIntOrNull()?.let { seeders = it }
                            tag.endsWith(":magneturi") || tag.endsWith(":magneturl") -> if (value.startsWith("magnet:")) magnet = value
                            tag == "item" || tag == "entry" -> {
                                inItem = false
                                val best = listOf(magnet, enclosure, link).firstOrNull { it.startsWith("magnet:") }
                                    ?: listOf(enclosure, link).firstOrNull { it.startsWith("http") }
                                if (best != null && title.isNotBlank()) items += FeedItem(title, best, size, seeders)
                            }
                        }
                    }
                    text = ""
                }
            }
            event = parser.next()
        }
        return items
    }
}
