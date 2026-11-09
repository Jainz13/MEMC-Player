package com.jainz.memc.data.scan

import com.jainz.memc.AppGraph
import com.jainz.memc.data.db.Kind
import com.jainz.memc.data.db.ScrapeState
import com.jainz.memc.data.tmdb.SearchResult
import com.jainz.memc.data.tmdb.TmdbAuthException
import java.io.IOException
import kotlin.math.abs

/** Matches library videos to TMDB and downloads their posters, artwork and details. */
class Scraper(private val g: AppGraph) {

    private val videos get() = g.db.videos()
    private val meta get() = g.db.meta()

    /** Scrapes everything pending. Reports progress as (done, total). */
    suspend fun run(progress: suspend (Int, Int) -> Unit) {
        if (!g.tmdb.hasKey) return
        val pending = videos.pendingScrape()
        val movieCache = HashMap<String, Long?>()
        val showCache = HashMap<String, Long?>()
        val seasonsDone = HashSet<String>()
        var failures = 0

        pending.forEachIndexed { i, v ->
            progress(i, pending.size)
            try {
                when (v.kind) {
                    Kind.EPISODE -> {
                        val key = "${norm(v.parsedTitle)}|${v.parsedYear ?: ""}"
                        val showId = if (showCache.containsKey(key)) showCache[key] else findShow(v.parsedTitle, v.parsedYear).also { showCache[key] = it }
                        if (showId == null) {
                            videos.setScrapeState(v.id, ScrapeState.NOT_FOUND)
                        } else {
                            ensureShow(showId)
                            val season = v.season
                            if (season != null && seasonsDone.add("$showId/$season")) ensureSeason(showId, season)
                            videos.setShow(v.id, showId, ScrapeState.MATCHED)
                        }
                    }
                    Kind.MOVIE -> {
                        val key = "${norm(v.parsedTitle)}|${v.parsedYear ?: ""}"
                        val movieId = if (movieCache.containsKey(key)) movieCache[key] else findMovie(v.parsedTitle, v.parsedYear).also { movieCache[key] = it }
                        if (movieId == null) {
                            videos.setScrapeState(v.id, ScrapeState.NOT_FOUND)
                        } else {
                            ensureMovie(movieId)
                            videos.setMovie(v.id, movieId, ScrapeState.MATCHED)
                        }
                    }
                    else -> videos.setScrapeState(v.id, ScrapeState.SKIPPED)
                }
                failures = 0
            } catch (e: TmdbAuthException) {
                throw e
            } catch (e: IOException) {
                // Leave it pending for the next scan; give up if the network is clearly down.
                if (++failures >= 5) throw e
            }
        }
        progress(pending.size, pending.size)
    }

    // ---- Matching -------------------------------------------------------------

    suspend fun findMovie(title: String, year: Int?): Long? {
        if (title.isBlank()) return null
        var results = g.tmdb.searchMovie(title, year)
        if (results.isEmpty() && year != null) {
            // Release years differ between countries; accept ±1 year.
            results = g.tmdb.searchMovie(title, null).filter { it.year == null || abs(it.year - year) <= 1 }
        }
        return pickBest(results, title, year)?.id
    }

    suspend fun findShow(title: String, year: Int?): Long? {
        if (title.isBlank()) return null
        var results = g.tmdb.searchTv(title, year)
        if (results.isEmpty() && year != null) results = g.tmdb.searchTv(title, null)
        if (results.isEmpty()) {
            // "Show Name US" / "Show Name 2019" style suffixes.
            val shorter = title.replace(Regex("""\s+(US|UK|AU|\d{4})$"""), "")
            if (shorter != title) results = g.tmdb.searchTv(shorter, null)
        }
        return pickBest(results, title, year)?.id
    }

    private fun pickBest(results: List<SearchResult>, title: String, year: Int?): SearchResult? {
        if (results.isEmpty()) return null
        val t = norm(title)
        return results.firstOrNull { norm(it.title) == t && (year == null || it.year == year) }
            ?: results.firstOrNull { norm(it.title) == t }
            ?: results.first()
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    // ---- Details ----------------------------------------------------------------

    suspend fun ensureMovie(id: Long, force: Boolean = false) {
        val existing = meta.movie(id)
        if (force || existing == null || System.currentTimeMillis() - existing.fetchedAt > STALE_MS) {
            meta.upsertMovie(g.tmdb.movie(id))
        }
    }

    suspend fun ensureShow(id: Long, force: Boolean = false) {
        val existing = meta.show(id)
        if (force || existing == null || System.currentTimeMillis() - existing.fetchedAt > STALE_MS) {
            val (show, seasons) = g.tmdb.show(id)
            meta.upsertShow(show)
            meta.upsertSeasons(seasons)
        }
    }

    suspend fun ensureSeason(showId: Long, season: Int, force: Boolean = false) {
        if (force || meta.episodeMetaCount(showId, season) == 0) {
            try {
                meta.upsertEpisodes(g.tmdb.season(showId, season))
            } catch (e: TmdbAuthException) {
                throw e
            } catch (_: IOException) {
                // Season missing on TMDB (e.g. specials) - the episodes still play fine.
            }
        }
    }

    // ---- Manual fixes ("Fix match") ----------------------------------------------

    suspend fun assignMovie(videoId: Long, tmdbId: Long) {
        ensureMovie(tmdbId, force = true)
        videos.setMovie(videoId, tmdbId, ScrapeState.MANUAL)
        meta.pruneMovies()
    }

    /** Moves every episode of [oldShowId] (or the single [videoId]) onto [tmdbId]. */
    suspend fun assignShow(oldShowId: Long?, videoId: Long?, tmdbId: Long) {
        ensureShow(tmdbId, force = true)
        if (oldShowId != null) videos.reassignShow(oldShowId, tmdbId)
        if (videoId != null) videos.setShow(videoId, tmdbId, ScrapeState.MANUAL)
        videos.allEpisodes(tmdbId).mapNotNull { it.video.season }.distinct().forEach { ensureSeason(tmdbId, it) }
        meta.pruneShows()
    }

    companion object {
        private const val STALE_MS = 30L * 24 * 60 * 60 * 1000
    }
}
