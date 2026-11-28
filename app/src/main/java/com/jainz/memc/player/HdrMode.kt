package com.jainz.memc.player

import android.os.Build
import android.view.SurfaceControl
import android.view.SurfaceView
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * How HDR videos are shown.
 *
 *  - AUTO / DOLBY_VISION: Dolby Vision files use the phone's Dolby Vision decoder.
 *  - HDR10: Dolby Vision files play their HDR10 / HDR10+ base layer instead (profile 8.1 and
 *    most WEB-DLs have one; profile 5 has none and stays Dolby Vision).
 *  - SDR: the base layer, tone-mapped to SDR by Android (Android 15 and newer).
 */
object HdrMode {
    const val AUTO = 0
    const val DOLBY_VISION = 1
    const val HDR10 = 2
    const val SDR = 3

    val labels = listOf(AUTO to "Auto", DOLBY_VISION to "Dolby Vision", HDR10 to "HDR10 / HDR10+", SDR to "SDR")

    /** Android can only limit a video to SDR brightness from Android 15. */
    val sdrSupported: Boolean get() = Build.VERSION.SDK_INT >= 35

    /** Whether this mode keeps the Dolby Vision decoder. */
    fun usesDolbyVision(mode: Int) = mode == AUTO || mode == DOLBY_VISION

    /**
     * Hides the Dolby Vision decoders when the base layer is wanted. Media3 then plays a
     * Dolby Vision track with the matching HEVC / AV1 decoder (its HDR10 / HDR10+ layer).
     */
    @UnstableApi
    fun codecSelector(mode: Int): MediaCodecSelector =
        if (usesDolbyVision(mode)) {
            MediaCodecSelector.DEFAULT
        } else {
            MediaCodecSelector { mime, secure, tunneling ->
                if (mime == MimeTypes.VIDEO_DOLBY_VISION) emptyList()
                else MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            }
        }

    /**
     * SDR: asks Android to show the video layer within SDR brightness (it tone-maps HDR down).
     * Any other mode lifts the limit again. Does nothing before Android 15.
     */
    fun applyToSurface(view: SurfaceView?, mode: Int) {
        if (Build.VERSION.SDK_INT < 35 || view == null) return
        runCatching {
            val sc = view.surfaceControl ?: return
            if (!sc.isValid) return
            SurfaceControl.Transaction()
                .setDesiredHdrHeadroom(sc, if (mode == SDR) 1f else 0f)
                .apply()
        }
    }
}
