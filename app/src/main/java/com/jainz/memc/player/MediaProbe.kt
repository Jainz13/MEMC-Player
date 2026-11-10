package com.jainz.memc.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.jainz.memc.AppGraph
import com.jainz.memc.data.db.VideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/** What is inside a video file: read from the file's own headers, not guessed from its name. */
data class MediaInfo(
    val video: String,
    val audio: List<String>,
    val subtitles: List<String>,
    /** Badge tags, e.g. "4K|DV|HEVC|ATMOS" */
    val tags: String,
    val durationMs: Long,
)

/**
 * Opens a file (on the phone or a network share) just far enough to list its tracks, and
 * remembers the result so each file is only read once.
 */
@UnstableApi
object MediaProbe {
    private const val PREFS = "media_info_v1"
    private val lock = Mutex()

    fun cached(context: Context, videoId: Long): MediaInfo? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(videoId.toString(), null) ?: return null
        return runCatching {
            val j = JSONObject(raw)
            MediaInfo(
                video = j.getString("video"),
                audio = j.getJSONArray("audio").let { a -> List(a.length()) { a.getString(it) } },
                subtitles = j.getJSONArray("subs").let { a -> List(a.length()) { a.getString(it) } },
                tags = j.getString("tags"),
                durationMs = j.optLong("duration"),
            )
        }.getOrNull()
    }

    /** Reads the file's tracks (one file at a time). Returns null if it can't be opened. */
    suspend fun probe(context: Context, g: AppGraph, v: VideoEntity): MediaInfo? = lock.withLock {
        cached(context, v.id)?.let { return it }
        val info = withContext(Dispatchers.IO) { runCatching { read(context, g, v) }.getOrNull() } ?: return null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(
            v.id.toString(),
            JSONObject()
                .put("video", info.video)
                .put("audio", JSONArray(info.audio))
                .put("subs", JSONArray(info.subtitles))
                .put("tags", info.tags)
                .put("duration", info.durationMs)
                .toString(),
        ).apply()
        if (info.tags.isNotBlank() && info.tags != v.tech) g.db.videos().setTech(v.id, info.tags)
        if (info.durationMs > 0) g.db.videos().setDurationIfUnknown(v.id, info.durationMs)
        info
    }

    private fun read(context: Context, g: AppGraph, v: VideoEntity): MediaInfo {
        val extractors = ColorFixExtractorsFactory(
            DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
        )
        val sources = DefaultMediaSourceFactory(RoutingDataSource.Factory(context, g.smb), extractors)
        @Suppress("DEPRECATION")
        val retriever = MetadataRetriever.Builder(context, MediaItem.fromUri(v.uri))
            .setMediaSourceFactory(sources)
            .build()
        try {
            val groups = retriever.retrieveTrackGroups().get(25, TimeUnit.SECONDS)
            val durationUs = runCatching { retriever.retrieveDurationUs().get(5, TimeUnit.SECONDS) }.getOrDefault(C.TIME_UNSET)

            val videos = ArrayList<Format>()
            val audios = ArrayList<Format>()
            val texts = ArrayList<Format>()
            for (i in 0 until groups.length) {
                val group = groups[i]
                for (k in 0 until group.length) {
                    val f = group.getFormat(k)
                    when (group.type) {
                        C.TRACK_TYPE_VIDEO -> videos += f
                        C.TRACK_TYPE_AUDIO -> audios += f
                        C.TRACK_TYPE_TEXT -> texts += f
                    }
                }
            }
            // Cover art is sometimes stored as a tiny "video" track: take the largest one.
            val video = videos.maxByOrNull { it.width.toLong() * it.height }
            val fileTags = v.tech.split('|')
            return MediaInfo(
                video = describeVideo(video, fileTags),
                audio = audios.map { describeAudio(it) },
                subtitles = texts.map {
                    val forced = (it.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
                    PlayerState.language(it) + " (" + PlayerState.codecName(it.sampleMimeType) + (if (forced) ", forced" else "") + ")"
                },
                tags = tags(video, audios, fileTags),
                durationMs = if (durationUs != C.TIME_UNSET && durationUs > 0) durationUs / 1000 else 0,
            )
        } finally {
            runCatching { retriever.close() }
        }
    }

    private fun isDolbyVision(f: Format?): Boolean {
        if (f == null) return false
        val codecs = f.codecs ?: ""
        return f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || codecs.startsWith("dvh") || codecs.startsWith("dva") || codecs.startsWith("dav1")
    }

    private fun hdrName(f: Format?, fileTags: List<String>): String? {
        val transfer = f?.colorInfo?.colorTransfer
        return when {
            isDolbyVision(f) -> "Dolby Vision"
            transfer == C.COLOR_TRANSFER_ST2084 -> if ("HDR10+" in fileTags) "HDR10+" else "HDR10"
            transfer == C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
    }

    private fun describeVideo(f: Format?, fileTags: List<String>): String {
        if (f == null) return "No video track found"
        val codec = when {
            f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> if ((f.codecs ?: "").startsWith("dav1")) "AV1" else "HEVC"
            else -> PlayerState.codecName(f.sampleMimeType)
        }
        val bits = f.colorInfo?.lumaBitdepth?.takeIf { it > 0 }
        return listOfNotNull(
            codec,
            if (f.width > 0) "${f.width}×${f.height}" else null,
            if (f.frameRate > 0) String.format(Locale.US, "%.3f fps", f.frameRate).replace(".000 fps", " fps") else null,
            bits?.let { "$it-bit" },
            hdrName(f, fileTags) ?: "SDR",
            if (f.bitrate > 0) String.format(Locale.US, "%.1f Mbps", f.bitrate / 1_000_000.0) else null,
        ).joinToString(" · ")
    }

    /** Best-first rank used for the badge; also names formats the mime type alone can't tell apart. */
    private fun audioKind(f: Format): Pair<Int, String> {
        val mime = f.sampleMimeType ?: ""
        val label = (f.label ?: "").lowercase()
        return when {
            mime == MimeTypes.AUDIO_E_AC3_JOC -> 9 to "Dolby Atmos (Dolby Digital+)"
            mime == MimeTypes.AUDIO_TRUEHD && "atmos" in label -> 10 to "Dolby Atmos (TrueHD)"
            mime == MimeTypes.AUDIO_TRUEHD -> 8 to "Dolby TrueHD"
            mime.startsWith("audio/vnd.dts.uhd") || "dts:x" in label || "dts-x" in label -> 7 to "DTS:X"
            mime == MimeTypes.AUDIO_DTS_HD -> 6 to "DTS-HD"
            mime.startsWith("audio/vnd.dts") -> 5 to "DTS"
            mime == MimeTypes.AUDIO_E_AC3 -> if ("atmos" in label) 9 to "Dolby Atmos (Dolby Digital+)" else 4 to "Dolby Digital+"
            mime == MimeTypes.AUDIO_AC3 -> 3 to "Dolby Digital"
            else -> 1 to PlayerState.codecName(f.sampleMimeType)
        }
    }

    private fun describeAudio(f: Format): String = listOfNotNull(
        PlayerState.language(f),
        audioKind(f).second,
        PlayerState.channels(f.channelCount),
    ).joinToString(" · ")

    private fun tags(video: Format?, audios: List<Format>, fileTags: List<String>): String {
        val tags = ArrayList<String>()
        val h = video?.height ?: 0
        val w = video?.width ?: 0
        when {
            w >= 3200 || h >= 1600 -> tags += "4K"
            w >= 1800 || h >= 1000 -> tags += "1080p"
            w >= 1200 || h >= 700 -> tags += "720p"
        }
        when (hdrName(video, fileTags)) {
            "Dolby Vision" -> tags += "DV"
            "HDR10+" -> tags += "HDR10+"
            "HDR10" -> tags += "HDR10"
            "HLG" -> tags += "HLG"
        }
        when {
            video?.sampleMimeType == MimeTypes.VIDEO_AV1 || (video?.codecs ?: "").startsWith("dav1") -> tags += "AV1"
            video?.sampleMimeType == MimeTypes.VIDEO_H265 || video?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> tags += "HEVC"
        }
        val best = audios.maxByOrNull { audioKind(it).first }?.let { audioKind(it) }
        when (best?.first) {
            10, 9 -> tags += "ATMOS"
            8 -> tags += if ("ATMOS" in fileTags) "ATMOS" else "TrueHD"
            7 -> tags += "DTS:X"
            6 -> tags += "DTS-HD"
            5 -> tags += "DTS"
            4 -> tags += "DD+"
        }
        val maxChannels = audios.maxOfOrNull { it.channelCount } ?: 0
        if (maxChannels >= 8) tags += "7.1" else if (maxChannels >= 6) tags += "5.1"
        if ("REMUX" in fileTags) tags += "REMUX"
        return tags.joinToString("|")
    }
}
