package com.jainz.memc.stereo

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * First pass of the 3D export: turns the video into a small greyscale depth video (white =
 * near), one depth frame per video frame, with Video Depth Anything on the NPU.
 *
 * The model needs to see frames ahead before it can give depth for a frame, so this stage
 * holds its output back: it takes each frame, keeps only the small copy the model needs, and
 * hands the depth frames on later, in order and with the original timestamps.
 */
@UnstableApi
class DepthPassEffect(private val vda: VdaDepth, private val progress: Progress) : GlEffect {
    /** Figures for the export's status line. */
    class Progress {
        val framesIn = AtomicLong()
        val framesOut = AtomicLong()
        @Volatile var cuts = 0
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        try {
            DepthPassProgram(context, useHdr, vda, progress)
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }

    companion object {
        /** Size of the depth video (multiples of 16 suit every hardware encoder). */
        const val OUT_W = 512
        const val OUT_H = 288
    }
}

@UnstableApi
private class DepthPassProgram(
    context: Context,
    private val useHdr: Boolean,
    private val vda: VdaDepth,
    private val progress: DepthPassEffect.Progress,
) : GlShaderProgram {
    private var inputListener: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
    private var outputListener: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
    private var errorListener: GlShaderProgram.ErrorListener = GlShaderProgram.ErrorListener { }
    private var errorExecutor: Executor = Executor { it.run() }

    private val smallProgram = program(context, "memc/npu_small.frag")
    private val grayProgram = program(context, "stereo/depth_gray.frag")
    private val w = vda.width
    private val h = vda.height
    private val plane = w * h
    private var smallTex = 0
    private var smallFbo = 0
    private var depthTex = 0
    private val pixels: ByteBuffer = ByteBuffer.allocateDirect(plane * 4).order(ByteOrder.nativeOrder())
    private val planeR = FloatArray(plane)
    private val planeG = FloatArray(plane)
    private val planeB = FloatArray(plane)
    private val upload: FloatBuffer = ByteBuffer.allocateDirect(plane * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** Output textures (the depth frames waiting for the encoder). */
    private val free = ArrayDeque<GlTextureInfo>()
    private val all = ArrayList<GlTextureInfo>()

    /** Frames pushed into the model, oldest first, waiting for their depth. */
    private val waiting = ArrayDeque<Long>()
    private val waitingCut = ArrayDeque<Boolean>()
    /** Finished depth maps (0..1, bottom row first) waiting for a free output texture. */
    private val ready = ArrayDeque<Pair<Long, FloatArray>>()
    private var inputBlocked = false
    private var inputEnded = false
    private var endSent = false

    // Scene cuts (64x36 thumbnail) and the steadying of the depth range across a shot.
    private val thumb = FloatArray(TW * TH * 3)
    private val thumbN = IntArray(TW * TH)
    private var prevThumb: FloatArray? = null
    private var lo = Float.NaN
    private var hi = Float.NaN
    private var prev: FloatArray? = null

    private fun ensureGl() {
        if (smallTex != 0) return
        smallTex = GlUtil.createTexture(w, h, false)
        smallFbo = GlUtil.createFboForTexture(smallTex)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        depthTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R16F, w, h, 0, GLES30.GL_RED, GLES20.GL_FLOAT, null)
        repeat(OUTPUT_TEXTURES) {
            val tex = GlUtil.createTexture(DepthPassEffect.OUT_W, DepthPassEffect.OUT_H, false)
            val fbo = GlUtil.createFboForTexture(tex)
            val info = GlTextureInfo(tex, fbo, C_UNSET, DepthPassEffect.OUT_W, DepthPassEffect.OUT_H)
            all += info
            free += info
        }
    }

    override fun setInputListener(inputListener: GlShaderProgram.InputListener) {
        this.inputListener = inputListener
        inputListener.onReadyToAcceptInputFrame()
    }

    override fun setOutputListener(outputListener: GlShaderProgram.OutputListener) {
        this.outputListener = outputListener
    }

    override fun setErrorListener(executor: Executor, errorListener: GlShaderProgram.ErrorListener) {
        errorExecutor = executor
        this.errorListener = errorListener
    }

    override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
        try {
            ensureGl()
            // Small copy of the frame for the model.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smallFbo)
            GLES20.glViewport(0, 0, w, h)
            smallProgram.use()
            smallProgram.setSamplerTexIdUniform("uTex", inputTexture.texId, 0)
            smallProgram.setIntUniform("uHdr", if (useHdr) 1 else 0)
            smallProgram.setFloatUniform("uStore", if (useHdr) HDR_STORE else 1f)
            smallProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            pixels.clear()
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            GlUtil.checkGlError()
            inputListener.onInputFrameProcessed(inputTexture)
            progress.framesIn.incrementAndGet()

            val cut = fillInput()
            if (cut) {
                progress.cuts++
                finish(vda.flush())
            }
            waiting.addLast(presentationTimeUs)
            waitingCut.addLast(cut)
            finish(vda.push())
            drain()
            if (ready.size < MAX_READY) inputListener.onReadyToAcceptInputFrame() else inputBlocked = true
        } catch (e: Throwable) {
            fail(e)
        }
    }

    /** Model input from the pixels (top row first), and whether this frame starts a new shot. */
    private fun fillInput(): Boolean {
        val input = vda.input
        input.clear()
        thumb.fill(0f)
        thumbN.fill(0)
        val r = planeR
        val g = planeG
        val b = planeB
        for (y in 0 until h) {
            val src = (h - 1 - y) * w * 4
            val dst = y * w
            val ty = (y * TH / h) * TW
            for (x in 0 until w) {
                val i = src + x * 4
                val rv = (pixels.get(i).toInt() and 0xFF) / 255f
                val gv = (pixels.get(i + 1).toInt() and 0xFF) / 255f
                val bv = (pixels.get(i + 2).toInt() and 0xFF) / 255f
                r[dst + x] = (rv - 0.485f) / 0.229f
                g[dst + x] = (gv - 0.456f) / 0.224f
                b[dst + x] = (bv - 0.406f) / 0.225f
                val t = ty + x * TW / w
                thumb[t * 3] += rv; thumb[t * 3 + 1] += gv; thumb[t * 3 + 2] += bv
                thumbN[t]++
            }
        }
        input.put(r)
        input.put(g)
        input.put(b)
        input.rewind()
        for (t in thumbN.indices) {
            val n = thumbN[t].coerceAtLeast(1).toFloat()
            thumb[t * 3] /= n; thumb[t * 3 + 1] /= n; thumb[t * 3 + 2] /= n
        }
        val p = prevThumb
        if (p == null) {
            prevThumb = thumb.copyOf()
            return false
        }
        var diff = 0f
        for (k in thumb.indices) diff += abs(thumb[k] - p[k])
        thumb.copyInto(p)
        return diff / thumb.size > CUT_THRESHOLD
    }

    /** Steadies each finished depth map and queues it for output. */
    private fun finish(depths: List<FloatArray>) {
        for (d in depths) {
            val pts = waiting.removeFirstOrNull() ?: continue
            val cut = waitingCut.removeFirstOrNull() ?: false
            ready.addLast(pts to stabilise(d, cut))
        }
    }

    /**
     * 0..1 depth from a percentile range eased over the shot, lightly blended with the last
     * frame; both restart at a cut. Returns a new array, bottom row first (GL order).
     */
    private fun stabilise(d: FloatArray, cut: Boolean): FloatArray {
        val sample = FloatArray(d.size / 8) { d[it * 8] }
        sample.sort()
        val l = sample[(sample.size * 0.02f).toInt()]
        val u = sample[(sample.size * 0.98f).toInt().coerceAtMost(sample.size - 1)]
        if (cut || lo.isNaN()) {
            lo = l; hi = u; prev = null
        } else {
            lo = lo * RANGE_DECAY + l * (1 - RANGE_DECAY)
            hi = hi * RANGE_DECAY + u * (1 - RANGE_DECAY)
        }
        val scale = if (hi - lo > 1e-6f) 1f / (hi - lo) else 0f
        val out = FloatArray(d.size)
        val p = prev
        for (y in 0 until h) {
            val src = y * w
            val dst = (h - 1 - y) * w
            for (x in 0 until w) {
                var v = ((d[src + x] - lo) * scale).coerceIn(0f, 1f)
                if (p != null) v = v * (1 - BLEND) + p[dst + x] * BLEND
                out[dst + x] = v
            }
        }
        prev = out
        return out
    }

    /** Hands finished depth frames to the encoder while it has room. */
    private fun drain() {
        while (free.isNotEmpty() && ready.isNotEmpty()) {
            val (pts, depth) = ready.removeFirst()
            val tex = free.removeFirst()
            upload.clear()
            upload.put(depth)
            upload.rewind()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTex)
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RED, GLES20.GL_FLOAT, upload)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, tex.fboId)
            GLES20.glViewport(0, 0, tex.width, tex.height)
            grayProgram.use()
            grayProgram.setSamplerTexIdUniform("uDepth", depthTex, 0)
            grayProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
            progress.framesOut.incrementAndGet()
            outputListener.onOutputFrameAvailable(tex, pts)
        }
        if (inputBlocked && ready.size < MAX_READY && !inputEnded) {
            inputBlocked = false
            inputListener.onReadyToAcceptInputFrame()
        }
        if (inputEnded && ready.isEmpty() && !endSent) {
            endSent = true
            outputListener.onCurrentOutputStreamEnded()
        }
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
        try {
            val mine = all.firstOrNull { it.texId == outputTexture.texId } ?: return
            if (free.none { it.texId == mine.texId }) free.addLast(mine)
            drain()
        } catch (e: Throwable) {
            fail(e)
        }
    }

    override fun signalEndOfCurrentInputStream() {
        try {
            finish(vda.flush())
            inputEnded = true
            drain()
        } catch (e: Throwable) {
            fail(e)
        }
    }

    override fun flush() {
        vda.reset()
        waiting.clear()
        waitingCut.clear()
        ready.clear()
        prevThumb = null
        lo = Float.NaN
        prev = null
        free.clear()
        free.addAll(all)
        inputBlocked = false
        inputEnded = false
        endSent = false
        inputListener.onFlush()
        inputListener.onReadyToAcceptInputFrame()
    }

    override fun release() {
        try {
            all.forEach {
                GlUtil.deleteFbo(it.fboId)
                GlUtil.deleteTexture(it.texId)
            }
            all.clear()
            free.clear()
            if (smallFbo != 0) GlUtil.deleteFbo(smallFbo)
            if (smallTex != 0) GlUtil.deleteTexture(smallTex)
            if (depthTex != 0) GlUtil.deleteTexture(depthTex)
            smallProgram.delete()
            grayProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private fun fail(e: Throwable) {
        errorExecutor.execute { errorListener.onError(VideoFrameProcessingException.from(e as? Exception ?: RuntimeException(e))) }
    }

    companion object {
        private const val OUTPUT_TEXTURES = 3
        /** Depth frames allowed to wait for the encoder before new video frames are held back. */
        private const val MAX_READY = 48
        private const val C_UNSET = -1
        private const val HDR_STORE = 256f
        private const val TW = 64
        private const val TH = 36
        private const val CUT_THRESHOLD = 0.13f
        /** EMA of the depth range per frame, and the share of the previous frame blended in. */
        private const val RANGE_DECAY = 0.9f
        private const val BLEND = 0.1f

        private fun program(context: Context, fragment: String): GlProgram =
            GlProgram(context, "memc/fullscreen.vert", fragment).apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            }
    }
}
