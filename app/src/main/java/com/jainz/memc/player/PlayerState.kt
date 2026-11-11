package com.jainz.memc.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.util.Locale

/** Mirrors the player into Compose state so the controls redraw when anything changes. */
@UnstableApi
class PlayerState(val player: ExoPlayer) {
    var isPlaying by mutableStateOf(false)
    var playWhenReady by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    /** Paused for a moment so the 60 fps engine can render ahead. */
    var prebuffering by mutableStateOf(false)
    var ended by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    var title by mutableStateOf("")
    var subtitle by mutableStateOf<String?>(null)
    var hasNext by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var tracks by mutableStateOf(Tracks.EMPTY)
    var videoSize by mutableStateOf(VideoSize.UNKNOWN)
    var videoDecoder by mutableStateOf<String?>(null)
    var audioDecoder by mutableStateOf<String?>(null)
    var speed by mutableStateOf(1f)
    // Kept as Compose state so the screen redraws the moment the format is known (the HDR /
    // Dolby Vision badge depends on it). Declared up here: it must exist before init runs sync().
    private var videoFormatSeen by mutableStateOf<Format?>(null)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()

        override fun onPlayerError(e: PlaybackException) {
            error = describe(e)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            error = null
            videoDecoder = null
            audioDecoder = null
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime, decoderName: String,
            initializedTimestampMs: Long, initializationDurationMs: Long,
        ) { videoDecoder = decoderName }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime, format: Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) { videoFormatSeen = format }

        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            videoFormatSeen = player.videoFormat
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime, decoderName: String,
            initializedTimestampMs: Long, initializationDurationMs: Long,
        ) { audioDecoder = decoderName }
    }

    init {
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        sync()
    }

    fun sync() {
        isPlaying = player.isPlaying
        playWhenReady = player.playWhenReady
        buffering = player.playbackState == Player.STATE_BUFFERING
        ended = player.playbackState == Player.STATE_ENDED
        duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        position = player.currentPosition
        buffered = player.bufferedPosition
        hasNext = player.hasNextMediaItem()
        tracks = player.currentTracks
        videoSize = player.videoSize
        videoFormatSeen = player.videoFormat
        speed = player.playbackParameters.speed
        player.mediaMetadata.let { md ->
            title = md.title?.toString() ?: ""
            subtitle = md.subtitle?.toString()
        }
    }

    fun tick() {
        position = player.currentPosition
        buffered = player.bufferedPosition
        duration = player.duration.takeIf { it != C.TIME_UNSET } ?: duration
    }

    fun release() {
        player.removeListener(listener)
        player.removeAnalyticsListener(analytics)
    }

    // ---- Helpers for labels -----------------------------------------------------

    val videoFormat: Format? get() = videoFormatSeen ?: player.videoFormat
    val audioFormat: Format? get() = player.audioFormat

    companion object {
        fun describe(e: PlaybackException): String = when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Lost connection to the file. Check the network share is reachable."
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The file is no longer there."
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                "This phone can't decode this video format (${e.errorCodeName})."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                "The file looks damaged or uses an unsupported container."
            else -> e.message ?: e.errorCodeName
        }

        fun codecName(mime: String?): String = when {
            mime == null -> "?"
            mime == MimeTypes.VIDEO_H265 -> "HEVC"
            mime == MimeTypes.VIDEO_H264 -> "H.264"
            mime == MimeTypes.VIDEO_AV1 -> "AV1"
            mime == MimeTypes.VIDEO_VP9 -> "VP9"
            mime == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            mime == MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
            mime == MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos (DD+)"
            mime == MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
            mime == MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            mime == MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            mime.startsWith("audio/vnd.dts.uhd") -> "DTS:X"
            mime == MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            mime.startsWith("audio/vnd.dts") -> "DTS"
            mime == MimeTypes.AUDIO_AAC -> "AAC"
            mime == MimeTypes.AUDIO_OPUS -> "Opus"
            mime == MimeTypes.AUDIO_FLAC -> "FLAC"
            mime == MimeTypes.AUDIO_MPEG -> "MP3"
            mime == MimeTypes.APPLICATION_SUBRIP -> "SRT"
            mime == MimeTypes.TEXT_SSA -> "ASS"
            mime == MimeTypes.APPLICATION_PGS -> "PGS"
            mime == MimeTypes.TEXT_VTT -> "WebVTT"
            else -> mime.substringAfter('/').uppercase(Locale.US)
        }

        fun channels(n: Int): String? = when (n) {
            Format.NO_VALUE, 0 -> null
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "$n ch"
        }

        /** Set by the player when Auto HDR flagged the current SDR video as HDR. */
        @Volatile var autoHdrActive = false

        fun hdrLabel(f: Format?): String? {
            val t = f?.colorInfo?.colorTransfer ?: return null
            return when {
                f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
                t == C.COLOR_TRANSFER_ST2084 && autoHdrActive -> "Auto HDR"
                t == C.COLOR_TRANSFER_ST2084 -> "HDR10 / HDR10+"
                t == C.COLOR_TRANSFER_HLG -> "HLG"
                else -> null
            }
        }

        fun language(f: Format): String {
            val lang = f.language?.takeIf { it.isNotBlank() && it != "und" && it != C.LANGUAGE_UNDETERMINED }
            val name = lang?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).ifBlank { it } }
            return listOfNotNull(name, f.label?.takeIf { it.isNotBlank() && it != name })
                .joinToString(" · ").ifBlank { "Unknown" }
        }

        /** Tech tags for the library, from what the player actually decoded. */
        fun techTags(video: Format?, audio: Format?, fileNameTags: String): String {
            val tags = mutableListOf<String>()
            val h = video?.height ?: 0
            when {
                h >= 1600 -> tags += "4K"
                h >= 1000 -> tags += "1080p"
                h >= 700 -> tags += "720p"
            }
            val fileTags = fileNameTags.split('|')
            when {
                video?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || "DV" in fileTags -> tags += "DV"
                video?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 ->
                    tags += if ("HDR10+" in fileTags) "HDR10+" else "HDR10"
                video?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> tags += "HLG"
            }
            when (video?.sampleMimeType) {
                MimeTypes.VIDEO_H265, MimeTypes.VIDEO_DOLBY_VISION -> tags += "HEVC"
                MimeTypes.VIDEO_AV1 -> tags += "AV1"
            }
            val am = audio?.sampleMimeType
            when {
                am == MimeTypes.AUDIO_E_AC3_JOC || "ATMOS" in fileTags -> tags += "ATMOS"
                am == MimeTypes.AUDIO_TRUEHD -> tags += "TrueHD"
                am != null && am.startsWith("audio/vnd.dts.uhd") -> tags += "DTS:X"
                am != null && am.startsWith("audio/vnd.dts") -> tags += "DTS"
            }
            if ("REMUX" in fileTags) tags += "REMUX"
            return tags.joinToString("|")
        }
    }
}
