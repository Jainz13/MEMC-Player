package com.jainz.memc.stereo

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.jainz.memc.core.DebugRecorder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Media3 video effect that turns each frame into a side-by-side 3D frame. */
@UnstableApi
class StereoEffect(private val control: StereoControl) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        try {
            StereoShaderProgram(context, useHdr, control)
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
}

/**
 * Renders the side-by-side frame. For 2D-to-3D it keeps a depth map of the picture: from the
 * AI model on the NPU when available (refreshed about 12 times a second on a worker thread,
 * so the video never waits for it), otherwise from simple picture cues on the GPU. The map is
 * smoothed over time so it doesn't flicker, and every frame is shifted by it for each eye.
 */
@UnstableApi
private class StereoShaderProgram(
    context: Context,
    private val useHdr: Boolean,
    private val control: StereoControl,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 2) {

    private val sbsProgram = program(context, "stereo/sbs.frag")
    private val smoothProgram = program(context, "stereo/depth_smooth.frag")
    private val heuristicProgram = program(context, "stereo/heuristic_depth.frag")
    private val smallProgram = program(context, "memc/npu_small.frag")
    /** 3D export: the depth comes from the depth video made in the first pass. */
    private val depthFile = control.depthVideo
    private val videoProgram = if (depthFile != null) program(context, "stereo_oes/depth_video.frag") else null
    private var reader: DepthVideoReader? = null
    private var readerFailed = false

    private var outW = 0
    private var outH = 0

    // Depth resources (2D to 3D only)
    private var smallW = 0
    private var smallH = 0
    private var smallTex = 0
    private var smallFbo = 0
    private var rawTex = 0
    private var rawFbo = 0
    private val smoothTex = IntArray(2)
    private val smoothFbo = IntArray(2)
    private var smoothIndex = 0
    private var depthW = 0
    private var depthH = 0
    private var haveDepth = false
    private var lastPts = Long.MIN_VALUE

    // AI depth on a worker thread
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "memc-depth").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val busy = AtomicBoolean(false)
    private val result = AtomicReference<DepthResult?>(null)
    private class DepthResult(val depth: FloatArray, val cut: Boolean)

    // Scene cuts and a steady depth range (ideas from make-it-3d / iw3): the range of each
    // depth map is eased over time instead of jumping, and both restart at a cut so a new
    // shot never inherits the last one's depth.
    private val thumb = FloatArray(THUMB_W * THUMB_H * 3)
    private val thumbCount = IntArray(THUMB_W * THUMB_H)
    private var prevThumb: FloatArray? = null
    @Volatile private var pendingCut = false
    private var rangeLo = Float.NaN
    private var rangeHi = Float.NaN
    private var cuts = 0
    private var pixels: ByteBuffer? = null
    private var lastDepthPts = Long.MIN_VALUE
    private var aiFailed = false
    private var depthCount = 0
    private var depthCountAt = 0L

    /**
     * Half-float colour targets (all recent phones; some TV boxes lack them). Without them the
     * depth maps are 8-bit, which is plenty for the picture-cue depth used there.
     */
    private val halfFloat: Boolean = runCatching {
        val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
        ext.contains("GL_EXT_color_buffer_half_float") || ext.contains("GL_EXT_color_buffer_float")
    }.getOrDefault(false)

    private val saved = IntArray(4)
    private val savedFbo = IntArray(1)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        when (control.mode) {
            StereoControl.SBS -> {
                val s = min(1f, 3840f / inputWidth)
                outW = even(inputWidth * s)
                outH = even(inputHeight * s)
            }
            StereoControl.HALF_SBS -> {
                val s = min(1f, 3840f / (inputWidth * 2f))
                outW = even(inputWidth * 2f * s)
                outH = even(inputHeight * s)
            }
            else -> {
                // Each eye fits 1920x1080 (the glasses' resolution per eye).
                val s = min(1f, min(1920f / inputWidth, 1080f / inputHeight))
                val eyeW = even(inputWidth * s)
                // Half side-by-side keeps one eye's width for both (each squeezed to half).
                outW = if (control.halfSbsOutput) eyeW else eyeW * 2
                outH = even(inputHeight * s)
                setUpDepth()
            }
        }
        control.outWidth = outW
        control.outHeight = outH
        DebugRecorder.event("3d", "3D output ${outW}x$outH from ${inputWidth}x$inputHeight (mode ${control.mode}, HDR $useHdr)")
        return Size(outW, outH)
    }

    private fun setUpDepth(ai: Boolean = control.npu != null && !aiFailed && !control.hot) {
        val file = depthFile != null && !readerFailed
        val w = if (file) DepthPassEffect.OUT_W else if (ai) DepthModel.WIDTH else 192
        val h = if (file) DepthPassEffect.OUT_H else if (ai) DepthModel.HEIGHT else 108
        prevThumb = null
        rangeLo = Float.NaN
        if (w == smallW && h == smallH && smallTex != 0) return
        releaseDepth()
        smallW = w
        smallH = h
        smallTex = GlUtil.createTexture(w, h, false)
        smallFbo = GlUtil.createFboForTexture(smallTex)
        depthW = w
        depthH = h
        rawTex = floatTexture(w, h)
        rawFbo = GlUtil.createFboForTexture(rawTex)
        for (i in 0..1) {
            smoothTex[i] = floatTexture(w, h)
            smoothFbo[i] = GlUtil.createFboForTexture(smoothTex[i])
        }
        pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        haveDepth = false
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, savedFbo, 0)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, saved, 0)
            val convert = control.mode == StereoControl.CONVERT
            if (convert) updateDepth(inputTexId, presentationTimeUs)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, savedFbo[0])
            GLES20.glViewport(saved[0], saved[1], saved[2], saved[3])
            sbsProgram.use()
            sbsProgram.setSamplerTexIdUniform("uSrc", inputTexId, 0)
            sbsProgram.setSamplerTexIdUniform("uDepth", if (convert) smoothTex[smoothIndex] else inputTexId, 1)
            sbsProgram.setIntUniform(
                "uMode",
                when (control.mode) {
                    StereoControl.HALF_SBS -> 1
                    StereoControl.SBS -> 2
                    else -> 0
                },
            )
            sbsProgram.setFloatUniform("uStrength", StereoControl.MAX_SHIFT * 2f * control.strength.coerceIn(0f, 1f))
            sbsProgram.setFloatUniform("uConv", control.convergence.coerceIn(0f, 1f))
            sbsProgram.setIntUniform("uSwap", if (control.swapEyes) 1 else 0)
            sbsProgram.setFloatUniform("uDepthTexel", if (convert && depthW > 0) 1f / depthW else 0f)
            sbsProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
            control.framesDone.incrementAndGet()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    /** 3D export: the depth frame for [pts] from the depth video, into the smoothed map. */
    private fun depthFromVideo(file: java.io.File, pts: Long): Boolean {
        val r = reader ?: try {
            DepthVideoReader(file).also { reader = it }
        } catch (e: Exception) {
            readerFailed = true
            control.status = "Couldn't read the depth video (${e.message}) - using picture cues"
            DebugRecorder.event("3d", control.status)
            setUpDepth(false)
            return false
        }
        if (!r.showFrameFor(pts)) {
            if (!haveDepth) clearSmooth()
            return true
        }
        val p = videoProgram ?: return false
        focus(rawFbo, depthW, depthH)
        p.use()
        p.setSamplerTexIdUniform("uTex", r.texId, 0)
        p.setFloatsUniform("uTexMatrix", r.texMatrix)
        p.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        // Already steady over time (Video Depth Anything + the first pass): no blending here.
        val next = 1 - smoothIndex
        focus(smoothFbo[next], depthW, depthH)
        smoothProgram.use()
        smoothProgram.setSamplerTexIdUniform("uNew", rawTex, 0)
        smoothProgram.setSamplerTexIdUniform("uPrev", smoothTex[smoothIndex], 1)
        smoothProgram.setFloatsUniform("uTexel", floatArrayOf(1f / depthW, 1f / depthH))
        smoothProgram.setFloatUniform("uMix", 1f)
        smoothProgram.setFloatUniform("uBlur", 0f)
        smoothProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        smoothIndex = next
        haveDepth = true
        control.status = "3D export: Video Depth Anything depth"
        return true
    }

    private fun updateDepth(inputTexId: Int, pts: Long) {
        if (depthFile != null && !readerFailed && depthFromVideo(depthFile, pts)) return
        // A seek or a jump: start the depth map over instead of blending across it.
        val jumped = lastPts != Long.MIN_VALUE && (pts < lastPts || pts - lastPts > 1_000_000)
        lastPts = pts
        val npu = control.npu?.takeIf { !aiFailed && !control.hot && halfFloat }
        if ((npu != null) != (smallW == DepthModel.WIDTH)) setUpDepth(npu != null)

        // Small copy of the frame (0..1 RGB, HDR compressed) for either depth method.
        focus(smallFbo, smallW, smallH)
        smallProgram.use()
        smallProgram.setSamplerTexIdUniform("uTex", inputTexId, 0)
        smallProgram.setIntUniform("uHdr", if (useHdr) 1 else 0)
        smallProgram.setFloatUniform("uStore", if (useHdr) HDR_STORE else 1f)
        smallProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        var fresh = false
        var mix = 0.15f
        if (npu != null) {
            // Take a finished depth map, if one is waiting.
            result.getAndSet(null)?.let { r ->
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rawTex)
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, depthW, depthH, GLES30.GL_RED, GLES20.GL_FLOAT, java.nio.FloatBuffer.wrap(r.depth))
                fresh = true
                // A new shot replaces the depth outright instead of fading in from the last one.
                mix = if (r.cut) 1f else 0.55f
                countDepth()
            }
            // Start the next one: about 12 a second of video, never more than one at a time.
            val due = lastDepthPts == Long.MIN_VALUE || jumped || pts - lastDepthPts >= DEPTH_INTERVAL_US
            if (due && busy.compareAndSet(false, true)) {
                lastDepthPts = pts
                val buf = pixels!!
                buf.clear()
                GLES20.glReadPixels(0, 0, smallW, smallH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                val cut = fillModelInput(buf, npu) || jumped
                pendingCut = cut
                if (cut && !jumped) cuts++
                worker.execute { runModel(npu) }
            }
        } else {
            // GPU cues, every frame.
            focus(rawFbo, depthW, depthH)
            heuristicProgram.use()
            heuristicProgram.setSamplerTexIdUniform("uTex", smallTex, 0)
            heuristicProgram.setFloatsUniform("uTexel", floatArrayOf(1f / smallW, 1f / smallH))
            heuristicProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            fresh = true
            countDepth()
            control.status = "3D from picture cues (GPU)"
        }
        if (!fresh && haveDepth && !jumped) return
        if (!fresh && !haveDepth) {
            // No depth yet (AI still working on the first one): flat until it arrives.
            return clearSmooth()
        }
        val next = 1 - smoothIndex
        focus(smoothFbo[next], depthW, depthH)
        smoothProgram.use()
        smoothProgram.setSamplerTexIdUniform("uNew", rawTex, 0)
        smoothProgram.setSamplerTexIdUniform("uPrev", smoothTex[smoothIndex], 1)
        smoothProgram.setFloatsUniform("uTexel", floatArrayOf(1f / depthW, 1f / depthH))
        smoothProgram.setFloatUniform("uMix", if (!haveDepth || jumped) 1f else mix)
        smoothProgram.setFloatUniform("uBlur", if (npu != null) 0f else 1f)
        smoothProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        smoothIndex = next
        haveDepth = true
    }

    private fun clearSmooth() {
        for (i in 0..1) {
            focus(smoothFbo[i], depthW, depthH)
            GLES20.glClearColor(control.convergence, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glClearColor(0f, 0f, 0f, 0f)
    }

    /**
     * RGBA bytes (bottom row first) -> the model's planar, normalised input (top row first).
     * Also builds a tiny thumbnail of the frame and returns true when it differs enough from
     * the previous one to be a scene cut.
     */
    private fun fillModelInput(buf: ByteBuffer, npu: NpuDepth): Boolean {
        val w = smallW
        val h = smallH
        val plane = w * h
        val input = npu.input
        val arr = modelInput ?: FloatArray(3 * plane).also { modelInput = it }
        thumb.fill(0f)
        thumbCount.fill(0)
        for (y in 0 until h) {
            val srcRow = (h - 1 - y) * w * 4
            val dst = y * w
            val tRow = (y * THUMB_H / h) * THUMB_W
            for (x in 0 until w) {
                val i = srcRow + x * 4
                val o = dst + x
                val r = (buf.get(i).toInt() and 0xFF) / 255f
                val g = (buf.get(i + 1).toInt() and 0xFF) / 255f
                val b = (buf.get(i + 2).toInt() and 0xFF) / 255f
                arr[o] = (r - 0.485f) / 0.229f
                arr[plane + o] = (g - 0.456f) / 0.224f
                arr[2 * plane + o] = (b - 0.406f) / 0.225f
                val t = tRow + x * THUMB_W / w
                thumb[t * 3] += r; thumb[t * 3 + 1] += g; thumb[t * 3 + 2] += b
                thumbCount[t]++
            }
        }
        for (t in thumbCount.indices) {
            val n = thumbCount[t].coerceAtLeast(1).toFloat()
            thumb[t * 3] /= n; thumb[t * 3 + 1] /= n; thumb[t * 3 + 2] /= n
        }
        val prev = prevThumb
        var cut = false
        if (prev == null) {
            prevThumb = thumb.copyOf()
        } else {
            var diff = 0f
            for (k in thumb.indices) diff += abs(thumb[k] - prev[k])
            cut = diff / thumb.size > CUT_THRESHOLD
            thumb.copyInto(prev)
        }
        synchronized(npu) {
            input.clear()
            input.put(arr)
            input.rewind()
        }
        return cut
    }

    private var modelInput: FloatArray? = null

    /** Worker thread: run the model, scale to 0..1 (1 = near), flip back to bottom-row-first. */
    private fun runModel(npu: NpuDepth) {
        try {
            val out = synchronized(npu) { npu.run() }
            val w = npu.width
            val h = npu.height
            val plane = w * h
            val raw = FloatArray(plane)
            out.get(raw, 0, plane)
            // Robust range: ignore the extreme 2% at each end so one bright speck doesn't flatten
            // the rest, then ease it over time so the depth doesn't "breathe" from map to map.
            val cut = pendingCut
            val sample = FloatArray(plane / 16) { raw[it * 16] }
            sample.sort()
            val lo = sample[(sample.size * 0.02f).toInt()]
            val hi = sample[(sample.size * 0.98f).toInt().coerceAtMost(sample.size - 1)]
            if (cut || rangeLo.isNaN()) {
                rangeLo = lo; rangeHi = hi
            } else {
                rangeLo += (lo - rangeLo) * RANGE_EASE
                rangeHi += (hi - rangeHi) * RANGE_EASE
            }
            val scale = if (rangeHi - rangeLo > 1e-6f) 1f / (rangeHi - rangeLo) else 0f
            val lo0 = rangeLo
            val depth = FloatArray(plane)
            for (y in 0 until h) {
                val src = y * w
                val dst = (h - 1 - y) * w
                for (x in 0 until w) depth[dst + x] = ((raw[src + x] - lo0) * scale).coerceIn(0f, 1f)
            }
            result.set(DepthResult(depth, cut))
            control.status = "AI 3D on the NPU, ${npu.averageMs.roundToInt()} ms per depth map" +
                if (cuts > 0) ", $cuts scene cuts" else ""
        } catch (e: Throwable) {
            aiFailed = true
            control.status = "AI 3D stopped (${e.message ?: e.javaClass.simpleName}) - using picture cues"
            DebugRecorder.event("3d", control.status)
        } finally {
            busy.set(false)
        }
    }

    private fun countDepth() {
        depthCount++
        val now = System.nanoTime()
        if (depthCountAt == 0L) depthCountAt = now
        val dt = now - depthCountAt
        if (dt >= 2_000_000_000L) {
            control.depthRate = depthCount * 1e9f / dt
            depthCount = 0
            depthCountAt = now
        }
    }

    private fun focus(fbo: Int, w: Int, h: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
    }

    private fun floatTexture(w: Int, h: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        if (halfFloat) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R16F, w, h, 0, GLES30.GL_RED, GLES20.GL_FLOAT, null)
        } else {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0, GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, null)
        }
        return ids[0]
    }

    private fun releaseDepth() {
        val tex = intArrayOf(smallTex, rawTex, smoothTex[0], smoothTex[1]).filter { it != 0 }.toIntArray()
        if (tex.isNotEmpty()) GLES20.glDeleteTextures(tex.size, tex, 0)
        val fbos = intArrayOf(smallFbo, rawFbo, smoothFbo[0], smoothFbo[1]).filter { it != 0 }.toIntArray()
        if (fbos.isNotEmpty()) GLES20.glDeleteFramebuffers(fbos.size, fbos, 0)
        smallTex = 0; rawTex = 0; smallFbo = 0; rawFbo = 0
        smoothTex.fill(0); smoothFbo.fill(0)
        smallW = 0; smallH = 0
    }

    override fun release() {
        super.release()
        worker.shutdownNow()
        reader?.let { runCatching { it.close() } }
        reader = null
        runCatching { releaseDepth() }
        listOfNotNull(sbsProgram, smoothProgram, heuristicProgram, smallProgram, videoProgram).forEach { runCatching { it.delete() } }
    }

    private fun even(v: Float) = max(2, v.roundToInt() / 2 * 2)

    companion object {
        /** Must match STORE in the overridden Media3 shaders (HDR stored at 1/256). */
        private const val HDR_STORE = 256f
        private const val DEPTH_INTERVAL_US = 80_000L
        /** Scene-cut thumbnail (64x36) and the mean colour change that counts as a cut. */
        private const val THUMB_W = 64
        private const val THUMB_H = 36
        private const val CUT_THRESHOLD = 0.13f
        /** Share of each new depth range taken (depth maps arrive about 12 a second). */
        private const val RANGE_EASE = 0.2f

        private fun program(context: Context, fragment: String): GlProgram =
            GlProgram(context, "memc/fullscreen.vert", fragment).apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            }
    }
}
