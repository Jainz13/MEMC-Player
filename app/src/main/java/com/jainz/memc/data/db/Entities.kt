package com.jainz.memc.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object Kind {
    const val UNKNOWN = 0
    const val MOVIE = 1
    const val EPISODE = 2
}

object ScrapeState {
    const val PENDING = 0
    const val MATCHED = 1
    const val NOT_FOUND = 2
    const val SKIPPED = 3
    /** The user picked the match by hand; automatic scraping leaves it alone. */
    const val MANUAL = 4
}

/** A network share (SMB). Device storage is the implicit source id 0. */
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 445,
    val share: String,
    /** Optional sub-folder inside the share, without leading slash. */
    val path: String = "",
    val username: String = "",
    val passwordEnc: String = "",
    val domain: String = "",
    /** Whether this share is scanned into the library. */
    val indexed: Boolean = true,
    val lastScanAt: Long = 0,
    val lastError: String? = null,
)

/** One video file, on the device or on a network share. */
@Entity(
    tableName = "videos",
    indices = [
        Index(value = ["uri"], unique = true),
        Index("movieId"), Index("showId"), Index("sourceId"), Index("folder"),
    ],
)
data class VideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** content://… for device files, smb://srcN@host/share/path for network files. */
    val uri: String,
    val sourceId: Long,
    /** Folder shown in the folder browser, e.g. "Movies/" or "/Media/Movies". */
    val folder: String,
    val fileName: String,
    val size: Long,
    val modified: Long,
    val addedAt: Long,
    val kind: Int,
    val parsedTitle: String,
    val parsedYear: Int?,
    val season: Int?,
    val episode: Int?,
    /** Tech tags from the file name or from playback, e.g. "4K|HDR10|HEVC|ATMOS". */
    val tech: String = "",
    val movieId: Long? = null,
    val showId: Long? = null,
    val scrapeState: Int = ScrapeState.PENDING,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val lastPlayedAt: Long = 0,
    val watched: Boolean = false,
)

@Entity(tableName = "movies")
data class MovieEntity(
    @PrimaryKey val tmdbId: Long,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val overview: String?,
    val tagline: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val rating: Double,
    val runtime: Int?,
    val genres: String,
    val releaseDate: String?,
    val director: String?,
    /** One cast member per line: name|character|profilePath */
    val cast: String,
    val certification: String?,
    val fetchedAt: Long,
)

@Entity(tableName = "shows")
data class ShowEntity(
    @PrimaryKey val tmdbId: Long,
    val name: String,
    val year: Int?,
    val overview: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val rating: Double,
    val genres: String,
    val status: String?,
    val network: String?,
    val cast: String,
    val fetchedAt: Long,
)

@Entity(tableName = "seasons", primaryKeys = ["showId", "season"])
data class SeasonEntity(
    val showId: Long,
    val season: Int,
    val name: String?,
    val overview: String?,
    val posterPath: String?,
)

@Entity(tableName = "episodes", primaryKeys = ["showId", "season", "episode"])
data class EpisodeMetaEntity(
    val showId: Long,
    val season: Int,
    val episode: Int,
    val name: String?,
    val overview: String?,
    val stillPath: String?,
    val airDate: String?,
    val runtime: Int?,
)

// ---- Query result shapes ---------------------------------------------------

data class MovieItem(
    @Embedded val movie: MovieEntity,
    val videoId: Long,
    val addedAt: Long,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val tech: String,
)

data class ShowItem(
    @Embedded val show: ShowEntity,
    val episodeCount: Int,
    val watchedCount: Int,
    val addedAt: Long,
)

/** A video plus whatever artwork and titles the library knows for it. */
data class VideoCard(
    @Embedded val video: VideoEntity,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "poster") val poster: String?,
    @ColumnInfo(name = "backdrop") val backdrop: String?,
    @ColumnInfo(name = "epName") val epName: String?,
    @ColumnInfo(name = "epOverview") val epOverview: String?,
    @ColumnInfo(name = "airDate") val airDate: String?,
)

data class ShowSeason(val showId: Long, val season: Int)

data class FolderItem(
    val sourceId: Long,
    val folder: String,
    val count: Int,
)

data class CastMember(val name: String, val character: String, val profilePath: String?) {
    companion object {
        fun parse(raw: String): List<CastMember> = raw.lines().filter { it.isNotBlank() }.map {
            val p = it.split("|")
            CastMember(p.getOrElse(0) { "" }, p.getOrElse(1) { "" }, p.getOrNull(2)?.takeIf { s -> s.isNotBlank() })
        }

        fun encode(list: List<CastMember>): String =
            list.joinToString("\n") { "${it.name.replace("|", " ")}|${it.character.replace("|", " ")}|${it.profilePath ?: ""}" }
    }
}
