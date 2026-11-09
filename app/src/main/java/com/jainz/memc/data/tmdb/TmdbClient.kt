package com.jainz.memc.data.tmdb

import com.jainz.memc.core.Settings
import com.jainz.memc.data.db.CastMember
import com.jainz.memc.data.db.EpisodeMetaEntity
import com.jainz.memc.data.db.MovieEntity
import com.jainz.memc.data.db.SeasonEntity
import com.jainz.memc.data.db.ShowEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object Tmdb {
    const val ATTRIBUTION = "This product uses the TMDB API but is not endorsed or certified by TMDB."

    fun img(path: String?, size: String = "w342"): String? =
        path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

    fun poster(path: String?) = img(path, "w342")
    fun posterLarge(path: String?) = img(path, "w500")
    fun backdrop(path: String?) = img(path, "w1280")
    fun still(path: String?) = img(path, "w300")
    fun profile(path: String?) = img(path, "w185")
}

class TmdbAuthException : IOException("TMDB rejected the API key - check it in Settings")

data class SearchResult(
    val id: Long,
    val title: String,
    val year: Int?,
    val overview: String?,
    val posterPath: String?,
    val isTv: Boolean,
)

class TmdbClient(private val settings: Settings) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val hasKey: Boolean get() = settings.tmdbKey.value.isNotBlank()

    private suspend fun get(path: String, params: Map<String, String?> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val key = settings.tmdbKey.value.trim()
            if (key.isEmpty()) throw IOException("No TMDB key set")
            val url = "https://api.themoviedb.org/3$path".toHttpUrl().newBuilder().apply {
                addQueryParameter("language", settings.language.value.ifBlank { "en-US" })
                params.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
                // A v4 "API Read Access Token" is a JWT; a v3 key is a short hex string.
                if (!key.startsWith("eyJ")) addQueryParameter("api_key", key)
            }.build()
            val req = Request.Builder().url(url).header("Accept", "application/json").apply {
                if (key.startsWith("eyJ")) header("Authorization", "Bearer $key")
            }.build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 401) throw TmdbAuthException()
                if (resp.code == 404) throw IOException("TMDB: not found ($path)")
                if (!resp.isSuccessful) throw IOException("TMDB error ${resp.code}")
                JSONObject(resp.body?.string() ?: "{}")
            }
        }

    /** Checks the key; returns null when it works, otherwise a readable error. */
    suspend fun verifyKey(): String? = try {
        get("/configuration")
        null
    } catch (e: Exception) {
        e.message ?: "Could not reach TMDB"
    }

    suspend fun searchMovie(query: String, year: Int?): List<SearchResult> {
        val json = get("/search/movie", mapOf("query" to query, "year" to year?.toString(), "include_adult" to "false"))
        return json.optJSONArray("results").objects().map {
            SearchResult(
                id = it.getLong("id"),
                title = it.optString("title"),
                year = it.optString("release_date").take(4).toIntOrNull(),
                overview = it.optStringOrNull("overview"),
                posterPath = it.optStringOrNull("poster_path"),
                isTv = false,
            )
        }
    }

    suspend fun searchTv(query: String, year: Int?): List<SearchResult> {
        val json = get("/search/tv", mapOf("query" to query, "first_air_date_year" to year?.toString(), "include_adult" to "false"))
        return json.optJSONArray("results").objects().map {
            SearchResult(
                id = it.getLong("id"),
                title = it.optString("name"),
                year = it.optString("first_air_date").take(4).toIntOrNull(),
                overview = it.optStringOrNull("overview"),
                posterPath = it.optStringOrNull("poster_path"),
                isTv = true,
            )
        }
    }

    suspend fun movie(id: Long): MovieEntity {
        val j = get("/movie/$id", mapOf("append_to_response" to "credits,release_dates"))
        val credits = j.optJSONObject("credits")
        val director = credits?.optJSONArray("crew").objects()
            .firstOrNull { it.optString("job") == "Director" }?.optString("name")
        val cert = j.optJSONObject("release_dates")?.optJSONArray("results").objects()
            .firstOrNull { it.optString("iso_3166_1") == "US" }
            ?.optJSONArray("release_dates").objects()
            .map { it.optString("certification") }
            .firstOrNull { it.isNotBlank() }
        return MovieEntity(
            tmdbId = id,
            title = j.optString("title"),
            originalTitle = j.optStringOrNull("original_title"),
            year = j.optString("release_date").take(4).toIntOrNull(),
            overview = j.optStringOrNull("overview"),
            tagline = j.optStringOrNull("tagline"),
            posterPath = j.optStringOrNull("poster_path"),
            backdropPath = j.optStringOrNull("backdrop_path"),
            rating = j.optDouble("vote_average", 0.0),
            runtime = j.optInt("runtime").takeIf { it > 0 },
            genres = j.optJSONArray("genres").objects().joinToString(", ") { it.optString("name") },
            releaseDate = j.optStringOrNull("release_date"),
            director = director,
            cast = CastMember.encode(castOf(credits)),
            certification = cert,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    suspend fun show(id: Long): Pair<ShowEntity, List<SeasonEntity>> {
        val j = get("/tv/$id", mapOf("append_to_response" to "credits"))
        val show = ShowEntity(
            tmdbId = id,
            name = j.optString("name"),
            year = j.optString("first_air_date").take(4).toIntOrNull(),
            overview = j.optStringOrNull("overview"),
            posterPath = j.optStringOrNull("poster_path"),
            backdropPath = j.optStringOrNull("backdrop_path"),
            rating = j.optDouble("vote_average", 0.0),
            genres = j.optJSONArray("genres").objects().joinToString(", ") { it.optString("name") },
            status = j.optStringOrNull("status"),
            network = j.optJSONArray("networks").objects().firstOrNull()?.optString("name"),
            cast = CastMember.encode(castOf(j.optJSONObject("credits"))),
            fetchedAt = System.currentTimeMillis(),
        )
        val seasons = j.optJSONArray("seasons").objects().map {
            SeasonEntity(
                showId = id,
                season = it.optInt("season_number"),
                name = it.optStringOrNull("name"),
                overview = it.optStringOrNull("overview"),
                posterPath = it.optStringOrNull("poster_path"),
            )
        }
        return show to seasons
    }

    suspend fun season(showId: Long, season: Int): List<EpisodeMetaEntity> {
        val j = get("/tv/$showId/season/$season")
        return j.optJSONArray("episodes").objects().map {
            EpisodeMetaEntity(
                showId = showId,
                season = season,
                episode = it.optInt("episode_number"),
                name = it.optStringOrNull("name"),
                overview = it.optStringOrNull("overview"),
                stillPath = it.optStringOrNull("still_path"),
                airDate = it.optStringOrNull("air_date"),
                runtime = it.optInt("runtime").takeIf { r -> r > 0 },
            )
        }
    }

    private fun castOf(credits: JSONObject?): List<CastMember> =
        credits?.optJSONArray("cast").objects().take(15).map {
            CastMember(
                name = it.optString("name"),
                character = it.optString("character"),
                profilePath = it.optStringOrNull("profile_path"),
            )
        }
}

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }
