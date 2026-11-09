package com.jainz.memc.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.jainz.memc.data.smb.SmbDataSource
import com.jainz.memc.core.Settings
import com.jainz.memc.data.smb.SmbPool
import java.util.Locale

/**
 * Builds the player tuned for the S24 Ultra:
 *  - video always goes to the hardware decoders (HEVC/AV1/VP9/H.264, 10-bit, HDR10+)
 *    and is drawn on a SurfaceView so HDR metadata reaches the display untouched;
 *  - audio the phone can't decode itself (DTS, DTS-HD, TrueHD…) falls back to FFmpeg;
 *  - smb:// addresses stream straight from network shares.
 */
@UnstableApi
object PlayerFactory {

    fun create(context: Context, smb: SmbPool, settings: Settings): ExoPlayer {
        val renderers = DefaultRenderersFactory(context)
            // Normally the phone's own decoders first and FFmpeg only for formats they can't handle;
            // "Always use FFmpeg for audio" flips that for audio.
            .setExtensionRendererMode(
                if (settings.ffmpegAudio.value) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
            .setEnableDecoderFallback(true)

        val audioLang = settings.audioLanguage.value.ifBlank { null }
        val subLang = settings.subtitleLanguage.value.ifBlank { Locale.getDefault().language }
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage(audioLang)
                    .apply {
                        if (settings.subtitlesOn.value) {
                            setPreferredTextLanguage(subLang)
                            setSelectUndeterminedTextLanguage(true)
                        } else {
                            // Off by default, but forced subtitles (foreign-language lines) still show.
                            setPreferredTextLanguage(null)
                            setSelectUndeterminedTextLanguage(false)
                            setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        }
                    }
                    // Keep the best audio track even if it needs the FFmpeg decoder.
                    .setExceedRendererCapabilitiesIfNecessary(true)
            )
        }

        // Generous buffer for 4K remuxes over Wi-Fi (~80 Mbps): up to 160 MB / 60 s ahead.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 1_500, 3_000)
            .setTargetBufferBytes(160 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            // Blu-ray .m2ts files carry DTS in a stream type that needs this flag.
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)

        val mediaSources = DefaultMediaSourceFactory(RoutingDataSource.Factory(context, smb), ColorFixExtractorsFactory(extractors))

        return ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSources)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                // Let the 120 Hz LTPO panel switch to a multiple of the video's frame rate (no judder).
                setVideoChangeFrameRateStrategy(
                    if (settings.matchFrameRate.value) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
                    else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
                )
            }
    }
}

/** Sends smb:// to the SMB reader and everything else (content://, file://, http) to the default one. */
@UnstableApi
class RoutingDataSource(context: Context, smb: SmbPool) : DataSource {

    private val default: DataSource = DefaultDataSource.Factory(context).createDataSource()
    private val smbSource: DataSource = SmbDataSource(smb)
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        default.addTransferListener(transferListener)
        smbSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme.equals("smb", ignoreCase = true)) smbSource else default
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    class Factory(private val context: Context, private val smb: SmbPool) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(context, smb)
    }
}
