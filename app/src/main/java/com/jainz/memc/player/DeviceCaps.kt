package com.jainz.memc.player

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import android.view.WindowManager

/** What this phone's display and video hardware can do, for the Settings screen and player overlay. */
object DeviceCaps {

    data class Caps(
        val hdrTypes: List<String>,
        val hardwareDecoders: List<String>,
        val extendedAudio: Boolean,
        val refreshRates: List<Int>,
        val socModel: String,
    )

    fun read(context: Context): Caps {
        val display = displayOf(context)
        @Suppress("DEPRECATION")
        val hdr = display?.hdrCapabilities?.supportedHdrTypes?.map {
            when (it) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                else -> "HDR type $it"
            }
        }?.distinct() ?: emptyList()

        val rates = display?.supportedModes?.map { Math.round(it.refreshRate) }?.distinct()?.sorted() ?: emptyList()

        return Caps(
            hdrTypes = hdr,
            hardwareDecoders = hardwareDecoders(),
            extendedAudio = ffmpegAvailable(),
            refreshRates = rates,
            socModel = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE,
        )
    }

    @Suppress("DEPRECATION")
    private fun displayOf(context: Context): Display? =
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { context.display }.getOrNull()
                ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        } else {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        }

    private fun hardwareDecoders(): List<String> {
        val wanted = linkedMapOf(
            MediaFormat.MIMETYPE_VIDEO_AV1 to "AV1",
            MediaFormat.MIMETYPE_VIDEO_HEVC to "HEVC",
            MediaFormat.MIMETYPE_VIDEO_VP9 to "VP9",
            MediaFormat.MIMETYPE_VIDEO_AVC to "H.264",
            MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION to "Dolby Vision",
        )
        val found = LinkedHashMap<String, MutableSet<String>>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder || !info.isHardwareAccelerated) continue
            for (type in info.supportedTypes) {
                val label = wanted[type.lowercase()] ?: continue
                val extras = found.getOrPut(label) { linkedSetOf() }
                val profiles = runCatching { info.getCapabilitiesForType(type).profileLevels.map { it.profile } }
                    .getOrDefault(emptyList())
                if (type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true)) {
                    if (CodecProfileLevel.HEVCProfileMain10 in profiles) extras += "10-bit"
                    if (CodecProfileLevel.HEVCProfileMain10HDR10Plus in profiles) extras += "HDR10+"
                }
                if (type.equals(MediaFormat.MIMETYPE_VIDEO_VP9, true) && CodecProfileLevel.VP9Profile2HDR10Plus in profiles) {
                    extras += "HDR10+"
                }
                if (type.equals(MediaFormat.MIMETYPE_VIDEO_AV1, true) && CodecProfileLevel.AV1ProfileMain10HDR10Plus in profiles) {
                    extras += "HDR10+"
                }
            }
        }
        return wanted.values.filter { it in found }.map { label ->
            val extras = found[label].orEmpty()
            if (extras.isEmpty()) label else "$label (${extras.joinToString(", ")})"
        }
    }

    private fun ffmpegAvailable(): Boolean = runCatching {
        androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable()
    }.getOrDefault(false)
}
