package com.jainz.memc.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

const val CARD_SELECT = """
    SELECT v.*,
        COALESCE(m.title, s.name, v.parsedTitle) AS title,
        COALESCE(m.posterPath, s.posterPath) AS poster,
        COALESCE(e.stillPath, m.backdropPath, s.backdropPath) AS backdrop,
        e.name AS epName, e.overview AS epOverview, e.airDate AS airDate
    FROM videos v
    LEFT JOIN movies m ON m.tmdbId = v.movieId
    LEFT JOIN shows s ON s.tmdbId = v.showId
    LEFT JOIN episodes e ON e.showId = v.showId AND e.season = v.season AND e.episode = v.episode
"""

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY name")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY name")
    suspend fun all(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun get(id: Long): SourceEntity?

    @Query("SELECT * FROM sources WHERE id = :id")
    fun getBlocking(id: Long): SourceEntity?

    @Insert
    suspend fun insert(s: SourceEntity): Long

    @Update
    suspend fun update(s: SourceEntity)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE sources SET lastScanAt = :at, lastError = :error WHERE id = :id")
    suspend fun setScanResult(id: Long, at: Long, error: String?)
}

@Dao
interface VideoDao {
    @Query("SELECT * FROM videos WHERE sourceId = :sourceId")
    suspend fun bySource(sourceId: Long): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE id = :id")
    suspend fun get(id: Long): VideoEntity?

    @Query("SELECT * FROM videos WHERE uri = :uri LIMIT 1")
    suspend fun byUri(uri: String): VideoEntity?

    @Query("SELECT * FROM videos WHERE id = :id")
    fun observe(id: Long): Flow<VideoEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<VideoEntity>)

    @Upsert
    suspend fun upsert(v: VideoEntity): Long

    @Update
    suspend fun updateAll(list: List<VideoEntity>)

    @Query("DELETE FROM videos WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("DELETE FROM videos WHERE sourceId = :sourceId")
    suspend fun deleteSource(sourceId: Long)

    @Query("SELECT * FROM videos WHERE scrapeState = 0 ORDER BY kind DESC, addedAt DESC")
    suspend fun pendingScrape(): List<VideoEntity>

    @Query("UPDATE videos SET movieId = :movieId, showId = NULL, kind = 1, scrapeState = :state WHERE id = :id")
    suspend fun setMovie(id: Long, movieId: Long?, state: Int)

    @Query("UPDATE videos SET showId = :showId, movieId = NULL, kind = 2, scrapeState = :state WHERE id = :id")
    suspend fun setShow(id: Long, showId: Long?, state: Int)

    @Query("UPDATE videos SET showId = :newId, scrapeState = 4 WHERE showId = :oldId")
    suspend fun reassignShow(oldId: Long, newId: Long)

    @Query("UPDATE videos SET scrapeState = :state WHERE id = :id")
    suspend fun setScrapeState(id: Long, state: Int)

    @Query("UPDATE videos SET scrapeState = 0 WHERE scrapeState IN (1, 2)")
    suspend fun resetScrape()

    @Query("UPDATE videos SET scrapeState = 0 WHERE scrapeState = 2")
    suspend fun retryNotFound()

    @Query("UPDATE videos SET positionMs = :pos, durationMs = :dur, lastPlayedAt = :at, watched = :watched WHERE id = :id")
    suspend fun savePlayback(id: Long, pos: Long, dur: Long, at: Long, watched: Boolean)

    @Query("UPDATE videos SET watched = :watched, positionMs = 0 WHERE id = :id")
    suspend fun setWatched(id: Long, watched: Boolean)

    @Query("UPDATE videos SET watched = :watched, positionMs = 0 WHERE showId = :showId AND COALESCE(season, 0) = :season")
    suspend fun setSeasonWatched(showId: Long, season: Int, watched: Boolean)

    @Query("UPDATE videos SET tech = :tech WHERE id = :id")
    suspend fun setTech(id: Long, tech: String)

    @Query("UPDATE videos SET durationMs = :durationMs WHERE id = :id AND durationMs = 0")
    suspend fun setDurationIfUnknown(id: Long, durationMs: Long)

    @Query("SELECT COUNT(*) FROM videos")
    fun observeCount(): Flow<Int>

    // ---- Library views ----

    @Query(CARD_SELECT + " WHERE v.positionMs > 30000 AND v.watched = 0 ORDER BY v.lastPlayedAt DESC LIMIT 20")
    fun continueWatching(): Flow<List<VideoCard>>

    @Query(CARD_SELECT + " WHERE v.kind = 2 AND v.showId IS NOT NULL ORDER BY v.addedAt DESC LIMIT 30")
    fun recentEpisodes(): Flow<List<VideoCard>>

    @Query(CARD_SELECT + " WHERE v.movieId IS NULL AND v.showId IS NULL AND v.scrapeState != 3 ORDER BY v.addedAt DESC")
    fun unidentified(): Flow<List<VideoCard>>

    @Query(CARD_SELECT + " WHERE v.sourceId = :sourceId AND v.folder = :folder ORDER BY v.fileName COLLATE NOCASE")
    fun inFolder(sourceId: Long, folder: String): Flow<List<VideoCard>>

    @Query(CARD_SELECT + " WHERE v.movieId = :movieId ORDER BY (v.uri LIKE 'smb://%'), v.size DESC")
    fun forMovie(movieId: Long): Flow<List<VideoCard>>

    @Query(CARD_SELECT + " WHERE v.showId = :showId AND COALESCE(v.season, 0) = :season ORDER BY v.episode, v.fileName")
    fun episodes(showId: Long, season: Int): Flow<List<VideoCard>>

    @Query(CARD_SELECT + " WHERE v.showId = :showId ORDER BY v.season, v.episode")
    suspend fun allEpisodes(showId: Long): List<VideoCard>

    @Query("SELECT DISTINCT COALESCE(season, 0) AS season FROM videos WHERE showId = :showId ORDER BY season")
    fun seasons(showId: Long): Flow<List<Int>>

    @Query(CARD_SELECT + " WHERE v.id = :id")
    suspend fun card(id: Long): VideoCard?

    @Query(
        CARD_SELECT + """ WHERE COALESCE(m.title, s.name, v.parsedTitle) LIKE '%' || :q || '%'
        OR v.fileName LIKE '%' || :q || '%'
        OR e.name LIKE '%' || :q || '%' ORDER BY v.addedAt DESC LIMIT 60"""
    )
    fun search(q: String): Flow<List<VideoCard>>

    @Query("SELECT sourceId, folder, COUNT(*) AS count FROM videos GROUP BY sourceId, folder ORDER BY sourceId, folder COLLATE NOCASE")
    fun folders(): Flow<List<FolderItem>>
}

@Dao
interface MetaDao {
    @Upsert suspend fun upsertMovie(m: MovieEntity)
    @Upsert suspend fun upsertShow(s: ShowEntity)
    @Upsert suspend fun upsertSeasons(list: List<SeasonEntity>)
    @Upsert suspend fun upsertEpisodes(list: List<EpisodeMetaEntity>)

    @Query("SELECT * FROM movies WHERE tmdbId = :id") suspend fun movie(id: Long): MovieEntity?
    @Query("SELECT * FROM shows WHERE tmdbId = :id") suspend fun show(id: Long): ShowEntity?
    @Query("SELECT * FROM movies WHERE tmdbId = :id") fun observeMovie(id: Long): Flow<MovieEntity?>
    @Query("SELECT * FROM shows WHERE tmdbId = :id") fun observeShow(id: Long): Flow<ShowEntity?>

    @Query("SELECT COUNT(*) FROM episodes WHERE showId = :showId AND season = :season")
    suspend fun episodeMetaCount(showId: Long, season: Int): Int

    @Query("SELECT * FROM seasons WHERE showId = :showId ORDER BY season")
    fun observeSeasons(showId: Long): Flow<List<SeasonEntity>>

    @Query(
        """SELECT m.*, v.id AS videoId, MAX(v.addedAt) AS addedAt, v.positionMs AS positionMs,
        v.durationMs AS durationMs, v.watched AS watched, v.tech AS tech
        FROM movies m JOIN videos v ON v.movieId = m.tmdbId GROUP BY m.tmdbId"""
    )
    fun movies(): Flow<List<MovieItem>>

    @Query(
        """SELECT s.*, COUNT(v.id) AS episodeCount, SUM(v.watched) AS watchedCount, MAX(v.addedAt) AS addedAt
        FROM shows s JOIN videos v ON v.showId = s.tmdbId GROUP BY s.tmdbId"""
    )
    fun shows(): Flow<List<ShowItem>>

    @Query("DELETE FROM movies WHERE tmdbId NOT IN (SELECT movieId FROM videos WHERE movieId IS NOT NULL)")
    suspend fun pruneMovies()

    @Query("DELETE FROM shows WHERE tmdbId NOT IN (SELECT showId FROM videos WHERE showId IS NOT NULL)")
    suspend fun pruneShows()

    /** Marks every movie/show as out of date so the next scrape re-downloads its details. */
    @Query("UPDATE movies SET fetchedAt = 0") suspend fun markMoviesStale()
    @Query("UPDATE shows SET fetchedAt = 0") suspend fun markShowsStale()
    @Query("SELECT tmdbId FROM movies WHERE fetchedAt = 0") suspend fun staleMovieIds(): List<Long>
    @Query("SELECT tmdbId FROM shows WHERE fetchedAt = 0") suspend fun staleShowIds(): List<Long>

    /** Seasons that have episodes in the library but no episode details yet. */
    @Query(
        """SELECT DISTINCT v.showId AS showId, v.season AS season FROM videos v
        WHERE v.showId IS NOT NULL AND v.season IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM episodes e WHERE e.showId = v.showId AND e.season = v.season)"""
    )
    suspend fun seasonsMissingEpisodes(): List<ShowSeason>

    @Query("DELETE FROM movies") suspend fun clearMovies()
    @Query("DELETE FROM shows") suspend fun clearShows()
    @Query("DELETE FROM seasons") suspend fun clearSeasons()
    @Query("DELETE FROM episodes") suspend fun clearEpisodes()
}
