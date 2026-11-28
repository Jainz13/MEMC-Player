package com.jainz.memc.stereo

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.media3.common.util.GlUtil
import java.io.File

/**
 * Plays the 3D export's depth video in step with the picture: [showFrameFor] decodes up to the
 * depth frame for a timestamp and puts it in an external (OES) texture. Must be created and
 * used on the GL thread whose context owns the texture.
 */
class DepthVideoReader(file: File) : AutoCloseable {
    val texId: Int = GlUtil.createExternalTexture()
    val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private val thread = HandlerThread("memc-depth-video").apply { start() }
    private val lock = Object()
    private var frameReady = false
    private val surfaceTexture = SurfaceTexture(texId).apply {
        setOnFrameAvailableListener({
            synchronized(lock) {
                frameReady = true
                lock.notifyAll()
            }
        }, Handler(thread.looper))
    }
    private val surface = Surface(surfaceTexture)
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false

    /** Timestamp of the depth frame in the texture, or Long.MIN_VALUE before the first. */
    var currentPts = Long.MIN_VALUE
        private set

    init {
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalStateException("The depth video has no video track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, surface, null, 0)
            codec.start()
        } catch (e: Throwable) {
            releaseQuietly()
            throw e
        }
    }

    /**
     * Brings the texture to the depth frame for [ptsUs] (the first one at or after it, within
     * a couple of milliseconds). Returns false when there is no depth frame at all yet.
     */
    fun showFrameFor(ptsUs: Long): Boolean {
        var tries = 0
        while (!outputDone && currentPts < ptsUs - TOLERANCE_US && tries++ < 2000) {
            feed()
            val index = codec.dequeueOutputBuffer(info, 10_000)
            if (index < 0) continue
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val render = !eos || info.size > 0
            codec.releaseOutputBuffer(index, render)
            if (render && awaitFrame()) {
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(texMatrix)
                currentPts = info.presentationTimeUs
            }
            if (eos) outputDone = true
        }
        return currentPts != Long.MIN_VALUE
    }

    private fun feed() {
        if (inputDone) return
        val index = codec.dequeueInputBuffer(0)
        if (index < 0) return
        val buffer = codec.getInputBuffer(index) ?: return
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun awaitFrame(): Boolean = synchronized(lock) {
        val until = System.currentTimeMillis() + 1000
        while (!frameReady) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) break
            lock.wait(left)
        }
        val ok = frameReady
        frameReady = false
        ok
    }

    private fun releaseQuietly() {
        runCatching { extractor.release() }
        runCatching { surface.release() }
        runCatching { surfaceTexture.release() }
        runCatching { GlUtil.deleteTexture(texId) }
        thread.quitSafely()
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        releaseQuietly()
    }

    private companion object {
        const val TOLERANCE_US = 2_000L
    }
}
