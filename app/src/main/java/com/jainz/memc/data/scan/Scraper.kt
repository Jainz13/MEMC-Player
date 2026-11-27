package com.jainz.memc.data.scan

import com.jainz.memc.AppGraph
import com.jainz.memc.data.db.Kind
import com.jainz.memc.data.db.ScrapeState
import com.jainz.memc.data.tmdb.SearchResult
import com.jainz.memc.data.tmdb.TmdbAuthException
import androidx.room.withTransaction
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Matches library videos to TMDB and downloads their posters, artwork and details. */
class Scraper(private val g: AppGraph) {

    private val videos get() = g.db.videos()
    private val meta get() = g.db.meta()

    /**
     * Scrapes everything pending. Reports progress as (done, total).
     *
     * Videos are grouped by the title they will be looked up under (all episodes of a show,
     * all copies of a film), so each title costs one search however many files it has. A few
     * titles are looked up at once, and each title's database changes are saved together,
     * so the library screens refresh once per title instead of once per file.
     */
    suspend fun run(progress: suspend (Int, Int) -> Unit) {
        if (!g.tmdb.hasKey) return
        val pending = videos.pendingScrape()
        val total = pending.size
        if (total == 0) {
            refreshStale()
            return
        }
        val groups = pending.groupBy { v -> Triple(v.kind == Kind.EPISODE, v.kind == Kind.MOVIE, "${norm(v.parsedTitle)}|${v.parsedYear ?: ""}") }
        val done = AtomicInteger()
        val failures = AtomicInteger()
        val showsReady = ConcurrentHashMap.newKeySet<Long>()
        val seasonsDone = ConcurrentHashMap.newKeySet<String>()
        val reportLock = Mutex()
        var lastReport = 0L
        val gate = Semaphore(PARALLEL)

        suspend fun report(n: Int, force: Boolean = false) {
            val now = done.addAndGet(n)
            reportLock.withLock {
                val t = System.currentTimeMillis()
                // Progress is saved by the job system each time: a few times a second is plenty.
                if (force || t - lastReport >= 400) {
                    lastReport = t
                    progress(now, total)
                }
            }
        }

        progress(0, total)
        coroutineScope {
            for ((key, list) in groups) {
                launch {
                    gate.withPermit {
                        val (isShow, isMovie, _) = key
                        val first = list.first()
                        try {
                            when {
                                isShow -> {
                                    val showId = findShow(first.parsedTitle, first.parsedYear)
                                    if (showId != null) {
                                        if (showsReady.add(showId)) ensureShow(showId)
                                        list.mapNotNull { it.season }.distinct().forEach { season ->
                                            if (seasonsDone.add("$showId/$season")) ensureSeason(showId, season)
                                        }
                                    }
                                    g.db.withTransaction {
                                        list.forEach { v ->
                                            if (showId == null) videos.setScrapeState(v.id, ScrapeState.NOT_FOUND)
                                            else videos.setShow(v.id, showId, ScrapeState.MATCHED)
                                        }
                                    }
                                }
                                isMovie -> {
                                    val movieId = findMovie(first.parsedTitle, first.parsedYear)
                                    if (movieId != null) ensureMovie(movieId)
                                    g.db.withTransaction {
                                        list.forEach { v ->
                                            if (movieId == null) videos.setScrapeState(v.id, ScrapeState.NOT_FOUND)
                                            else videos.setMovie(v.id, movieId, ScrapeState.MATCHED)
                                        }
                                    }
                                }
                                else -> g.db.withTransaction { list.forEach { videos.setScrapeState(it.id, ScrapeState.SKIPPED) } }
                            }
                            failures.set(0)
                        } catch (e: TmdbAuthException) {
                            throw e
                        } catch (e: IOException) {
                            // Left pending for the next scan; give up if the network is clearly down.
                            if (failures.incrementAndGet() >= 5) throw e
                        }
                        report(list.size)
                    }
                }
            }
        }
        report(0, force = true)
        refreshStale()
    }

    /** Re-downloads details marked out of date (language change, "Refresh all details") and missing seasons. */
    private suspend fun refreshStale() {
        var failures = 0
        suspend fun attempt(block: suspend () -> Unit) {
            try {
                block()
                failures = 0
            } catch (e: TmdbAuthException) {
                throw e
            } catch (e: IOException) {
                if (++failures >= 5) throw e
            }
        }
        meta.staleMovieIds().forEach { id -> attempt { ensureMovie(id) } }
        meta.staleShowIds().forEach { id -> attempt { ensureShow(id) } }
        meta.seasonsMissingEpisodes().forEach { s -> attempt { ensureSeason(s.showId, s.season) } }
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
        /** Titles looked up on TMDB at the same time (well inside its rate limit). */
        private const val PARALLEL = 4
    }
}
