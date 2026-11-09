package com.jainz.memc.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

/**
 * Many 4K releases (especially MKV) keep their HDR colour information only inside the HEVC
 * stream, not in the container. The phone's decoder still shows them correctly when it draws
 * straight to the screen, but anything that processes frames (MEMC, export) is told "no colour
 * info", assumes SDR, and the picture comes out grey, flat and dim.
 *
 * This wraps every extractor and, when a video track arrives without valid colour info, reads it
 * from the stream's own sequence header (HEVC SPS / VUI) instead.
 */
@UnstableApi
object StreamColorInfo {

    fun fix(format: Format): Format {
        val current = format.colorInfo
        if (current != null && current.isDataSpaceValid) return format
        val mime = format.sampleMimeType
        if (mime != MimeTypes.VIDEO_H265 && mime != MimeTypes.VIDEO_DOLBY_VISION) return format
        val sps = findSps(format.initializationData) ?: return format
        if (sps.colorSpace == Format.NO_VALUE || sps.colorTransfer == Format.NO_VALUE) return format
        val fixed = (current?.buildUpon() ?: ColorInfo.Builder())
            .setColorSpace(sps.colorSpace)
            .setColorRange(sps.colorRange)
            .setColorTransfer(sps.colorTransfer)
            .setLumaBitdepth(sps.bitDepthLumaMinus8 + 8)
            .setChromaBitdepth(sps.bitDepthChromaMinus8 + 8)
            .build()
        return format.buildUpon().setColorInfo(fixed).build()
    }

    private fun findSps(initData: List<ByteArray>): NalUnitUtil.H265SpsData? {
        for (data in initData) {
            if (data.size < 8) continue
            var start = NalUnitUtil.findNalUnit(data, 0, data.size, BooleanArray(3))
            while (start < data.size - 4) {
                val next = NalUnitUtil.findNalUnit(data, start + 3, data.size, BooleanArray(3))
                if (NalUnitUtil.getH265NalUnitType(data, start) == H265_SPS) {
                    return runCatching { NalUnitUtil.parseH265SpsNalUnit(data, start + 3, next, null) }.getOrNull()
                }
                start = next
            }
        }
        return null
    }

    private const val H265_SPS = 33
}

@UnstableApi
class ColorFixExtractorsFactory(private val delegate: ExtractorsFactory) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map { ColorFixExtractor(it) }.toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map { ColorFixExtractor(it) }.toTypedArray()
}

@UnstableApi
private class ColorFixExtractor(private val inner: Extractor) : Extractor {
    override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)
    override fun init(output: ExtractorOutput) = inner.init(ColorFixOutput(output))
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) = inner.seek(position, timeUs)
    override fun release() = inner.release()
    override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
}

@UnstableApi
private class ColorFixOutput(private val inner: ExtractorOutput) : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput {
        val track = inner.track(id, type)
        return if (type == C.TRACK_TYPE_VIDEO) ColorFixTrackOutput(track) else track
    }

    override fun endTracks() = inner.endTracks()
    override fun seekMap(seekMap: SeekMap) = inner.seekMap(seekMap)
}

@UnstableApi
private class ColorFixTrackOutput(private val inner: TrackOutput) : TrackOutput {
    override fun format(format: Format) = inner.format(StreamColorInfo.fix(format))

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
        inner.sampleData(input, length, allowEndOfInput, sampleDataPart)

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) =
        inner.sampleData(data, length, sampleDataPart)

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) =
        inner.sampleMetadata(timeUs, flags, size, offset, cryptoData)
}
