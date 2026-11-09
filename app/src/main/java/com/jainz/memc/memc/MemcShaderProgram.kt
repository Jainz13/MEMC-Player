package com.jainz.memc.memc

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.C
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlShaderProgram
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Motion-compensated frame interpolation (MEMC) on the GPU.
 *
 * For every new source frame:
 *  1. copy it, and make a small grey-scale version for motion search (320-640 px wide);
 *  2. block-match against the previous frame: full search at half that size, then a ±2 px
 *     refinement at full search size seeded by neighbouring vectors, then a 3x3 vector median;
 *  3. emit frames on the target-fps time grid (e.g. every 16.7 ms for 60 fps). Grid points
 *     between two source frames are interpolated by following the motion vectors into both
 *     frames; unreliable areas fall back to the nearest real frame instead of smearing.
 *
 * Motion is only searched at low resolution, which keeps GPU load (and heat) low; only the
 * final warp runs at full resolution. Frames are emitted with their own timestamps, so the
 * player schedules them against the audio clock and A/V sync is unaffected.
 */
@UnstableApi
class MemcShaderProgram(
    context: Context,
    private val useHdr: Boolean,
    private val control: MemcControl,
) : GlShaderProgram {

    private class Job(val alpha: Float, val ptsUs: Long, val interpolate: Boolean)

    private var inputListener: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
    private var outputListener: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
    private var errorListener: GlShaderProgram.ErrorListener = GlShaderProgram.ErrorListener { }
    private var errorExecutor: Executor = Executor { it.run() }

    private val copyProgram = program(context, "copy.frag")
    private val lumaProgram = program(context, "luma.frag")
    private val searchProgram = program(context, "search.frag")
    private val refineProgram = program(context, "refine.frag")
    private val smoothProgram = program(context, "smooth.frag")
    private val interpProgram = program(context, "interp.frag")

    // Resources, sized for the current video.
    private var width = 0
    private var height = 0
    private var allocatedQuality = -1
    private var workW = 0
    private var workH = 0
    private val frames = arrayOfNulls<GlTextureInfo>(2)
    private val lumaFine = arrayOfNulls<GlTextureInfo>(2)
    private val lumaCoarse = arrayOfNulls<GlTextureInfo>(2)
    private var mvCoarse: GlTextureInfo? = null
    private var mvFine: GlTextureInfo? = null
    private var mvSmooth: GlTextureInfo? = null
    private val outputs = ArrayList<GlTextureInfo>()
    private val freeOutputs = ArrayDeque<GlTextureInfo>()

    // Frame state.
    private var held = 0
    private var cur = 0
    private var prev = 0
    private var lastPtsUs = C.TIME_UNSET
    private val jobs = ArrayDeque<Job>()
    private val recentDeltas = ArrayDeque<Long>()
    private var readySignalled = false
    private var endOfStreamPending = false

    private val coeffs = if (useHdr) floatArrayOf(0.2627f, 0.6780f, 0.0593f) else floatArrayOf(0.2126f, 0.7152f, 0.0722f)
    private val lumaScale = if (useHdr) 60f else 3f

    // ---- GlShaderProgram -------------------------------------------------------------

    override fun setInputListener(inputListener: GlShaderProgram.InputListener) {
        this.inputListener = inputListener
        readySignalled = true
        inputListener.onReadyToAcceptInputFrame()
    }

    override fun setOutputListener(outputListener: GlShaderProgram.OutputListener) {
        this.outputListener = outputListener
    }

    override fun setErrorListener(executor: Executor, errorListener: GlShaderProgram.ErrorListener) {
        this.errorExecutor = executor
        this.errorListener = errorListener
    }

    override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
        readySignalled = false
        // Never let a new frame overwrite history that queued jobs still need.
        jobs.clear()
        try {
            ensureResources(inputTexture.width, inputTexture.height)
            GLES20.glDisable(GLES20.GL_BLEND)

            val slot = if (held == 0) 0 else 1 - cur
            drawCopy(inputTexture, frames[slot]!!)
            drawLuma(frames[slot]!!, lumaFine[slot]!!)
            drawCopy(lumaFine[slot]!!, lumaCoarse[slot]!!)
            inputListener.onInputFrameProcessed(inputTexture)

            val hadPrevious = held > 0
            val previousPts = lastPtsUs
            prev = cur
            cur = slot
            held = min(2, held + 1)
            lastPtsUs = presentationTimeUs
            plan(hadPrevious, previousPts, presentationTimeUs)
        } catch (e: Exception) {
            fail(e)
            return
        }
        drain()
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
        freeOutputs.addLast(outputTexture)
        drain()
    }

    override fun signalEndOfCurrentInputStream() {
        held = 0
        lastPtsUs = C.TIME_UNSET
        recentDeltas.clear()
        if (jobs.isEmpty()) outputListener.onCurrentOutputStreamEnded() else endOfStreamPending = true
    }

    override fun flush() {
        jobs.clear()
        endOfStreamPending = false
        held = 0
        lastPtsUs = C.TIME_UNSET
        recentDeltas.clear()
        freeOutputs.clear()
        freeOutputs.addAll(outputs)
        inputListener.onFlush()
        readySignalled = true
        inputListener.onReadyToAcceptInputFrame()
    }

    override fun release() {
        try {
            releaseResources()
            listOf(copyProgram, lumaProgram, searchProgram, refineProgram, smoothProgram, interpProgram).forEach { it.delete() }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    // ---- Scheduling ----------------------------------------------------------------------

    private fun plan(hadPrevious: Boolean, previousPts: Long, pts: Long) {
        if (!hadPrevious || previousPts == C.TIME_UNSET) {
            jobs.addLast(Job(1f, pts, false))
            control.active = false
            return
        }
        val dt = pts - previousPts
        if (dt in 4_000..250_000) {
            recentDeltas.addLast(dt)
            if (recentDeltas.size > 9) recentDeltas.removeFirst()
            val sorted = recentDeltas.sorted()
            control.sourceFps = 1_000_000f / sorted[sorted.size / 2]
        }
        val target = control.targetFps.coerceIn(24, 240)
        val interpolate = control.enabled && dt in 4_000..250_000 &&
            control.sourceFps > 0f && control.sourceFps < target * 0.8f
        control.active = interpolate
        if (!interpolate) {
            jobs.addLast(Job(1f, pts, false))
            return
        }

        runMotionSearch()

        // Output grid: k / target seconds, strictly after the previous frame, up to this one.
        val kStart = Math.floorDiv(previousPts * target, 1_000_000L) + 1
        val kEnd = Math.floorDiv(pts * target, 1_000_000L)
        var k = kStart
        while (k <= kEnd) {
            val t = Math.floorDiv(k * 1_000_000L + target / 2, target.toLong())
            if (t > previousPts && t <= pts) {
                if (pts - t < 500) jobs.addLast(Job(1f, pts, false))
                else jobs.addLast(Job((t - previousPts).toFloat() / dt, t, true))
            }
            k++
        }
    }

    private fun drain() {
        try {
            while (jobs.isNotEmpty() && freeOutputs.isNotEmpty()) {
                val job = jobs.removeFirst()
                val out = freeOutputs.removeFirst()
                if (job.interpolate) drawInterp(out, job.alpha) else drawCopy(frames[cur]!!, out)
                outputListener.onOutputFrameAvailable(out, job.ptsUs)
            }
        } catch (e: Exception) {
            fail(e)
            return
        }
        if (jobs.isEmpty()) {
            if (endOfStreamPending) {
                endOfStreamPending = false
                outputListener.onCurrentOutputStreamEnded()
            }
            if (!readySignalled) {
                readySignalled = true
                inputListener.onReadyToAcceptInputFrame()
            }
        }
    }

    // ---- Passes -------------------------------------------------------------------------------

    private fun runMotionSearch() {
        val q = allocatedQuality
        val coarse = mvCoarse!!
        val fine = mvFine!!
        draw(coarse, searchProgram) { p ->
            p.setSamplerTexIdUniform("uCur", lumaCoarse[cur]!!.texId, 0)
            p.setSamplerTexIdUniform("uPrev", lumaCoarse[prev]!!.texId, 1)
            p.setFloatsUniform("uSizeF", floatArrayOf((workW / 2).toFloat(), (workH / 2).toFloat()))
            p.setIntUniform("uBlock", BLOCK)
            p.setIntUniform("uStep", if (q >= 2) 1 else 2)
            p.setIntUniform("uRange", if (q == 0) 6 else 8)
        }
        draw(fine, refineProgram) { p ->
            p.setSamplerTexIdUniform("uCur", lumaFine[cur]!!.texId, 0)
            p.setSamplerTexIdUniform("uPrev", lumaFine[prev]!!.texId, 1)
            p.setSamplerTexIdUniform("uCoarse", coarse.texId, 2)
            p.setFloatsUniform("uSizeF", floatArrayOf(workW.toFloat(), workH.toFloat()))
            p.setFloatsUniform("uCoarseBlocksF", floatArrayOf(coarse.width.toFloat(), coarse.height.toFloat()))
            p.setIntUniform("uBlock", BLOCK)
        }
        draw(mvSmooth!!, smoothProgram) { p ->
            p.setSamplerTexIdUniform("uMv", fine.texId, 0)
            p.setSamplerTexIdUniform("uCur", lumaFine[cur]!!.texId, 1)
            p.setSamplerTexIdUniform("uPrev", lumaFine[prev]!!.texId, 2)
            p.setFloatsUniform("uBlocksF", floatArrayOf(fine.width.toFloat(), fine.height.toFloat()))
            p.setFloatsUniform("uSizeF", floatArrayOf(workW.toFloat(), workH.toFloat()))
            p.setIntUniform("uBlock", BLOCK)
        }
    }

    private fun drawInterp(out: GlTextureInfo, alpha: Float) {
        val (lo, hi) = when (allocatedQuality) {
            0 -> 0.045f to 0.12f
            2 -> 0.030f to 0.09f
            else -> 0.035f to 0.10f
        }
        draw(out, interpProgram) { p ->
            p.setSamplerTexIdUniform("uPrev", frames[prev]!!.texId, 0)
            p.setSamplerTexIdUniform("uCur", frames[cur]!!.texId, 1)
            p.setSamplerTexIdUniform("uMv", mvSmooth!!.texId, 2)
            p.setFloatUniform("uAlpha", alpha)
            p.setFloatsUniform("uMvToUv", floatArrayOf(1f / workW, 1f / workH))
            p.setFloatsUniform("uCoeffs", coeffs)
            p.setFloatUniform("uLumaScale", lumaScale)
            p.setFloatsUniform("uCost", floatArrayOf(lo, hi))
        }
    }

    private fun drawCopy(src: GlTextureInfo, dst: GlTextureInfo) {
        draw(dst, copyProgram) { p -> p.setSamplerTexIdUniform("uTex", src.texId, 0) }
    }

    private fun drawLuma(src: GlTextureInfo, dst: GlTextureInfo) {
        draw(dst, lumaProgram) { p ->
            p.setSamplerTexIdUniform("uTex", src.texId, 0)
            p.setFloatsUniform("uOutTexel", floatArrayOf(1f / dst.width, 1f / dst.height))
            p.setFloatsUniform("uCoeffs", coeffs)
            p.setFloatUniform("uLumaScale", lumaScale)
        }
    }

    private inline fun draw(target: GlTextureInfo, program: GlProgram, setup: (GlProgram) -> Unit) {
        GlUtil.focusFramebufferUsingCurrentContext(target.fboId, target.width, target.height)
        program.use()
        setup(program)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    // ---- Resources ------------------------------------------------------------------------------

    private fun ensureResources(w: Int, h: Int) {
        val quality = control.quality.coerceIn(0, 2)
        if (w != width || h != height || frames[0] == null) {
            // New video size (new stream): rebuild everything.
            releaseFrameTextures()
            releaseSearchTextures()
            width = w
            height = h
            for (i in 0..1) frames[i] = texture(w, h, useHdr)
            repeat(OUTPUT_FRAMES) { outputs += texture(w, h, useHdr) }
            freeOutputs.addAll(outputs)
            held = 0
        }
        if (quality != allocatedQuality || mvSmooth == null) {
            // Quality change: only the small motion-search buffers; output frames that the
            // display may still be holding are left alone.
            releaseSearchTextures()
            allocatedQuality = quality
            // Motion search size: multiples of 16 so both pyramid levels divide into 8-px blocks.
            val wanted = when (quality) { 0 -> 320; 2 -> 640; else -> 480 }
            workW = roundTo16(min(wanted, max(64, w)))
            workH = roundTo16(max(32, (workW.toFloat() * h / w).roundToInt()))
            for (i in 0..1) {
                lumaFine[i] = texture(workW, workH, true)
                lumaCoarse[i] = texture(workW / 2, workH / 2, true)
            }
            mvCoarse = texture(workW / 2 / BLOCK, workH / 2 / BLOCK, true)
            mvFine = texture(workW / BLOCK, workH / BLOCK, true)
            mvSmooth = texture(workW / BLOCK, workH / BLOCK, true)
            held = 0
        }
    }

    private fun releaseFrameTextures() {
        (frames.toList() + outputs).filterNotNull().forEach { runCatching { it.release() } }
        frames.fill(null)
        outputs.clear()
        freeOutputs.clear()
    }

    private fun releaseSearchTextures() {
        (lumaFine.toList() + lumaCoarse.toList() + listOf(mvCoarse, mvFine, mvSmooth))
            .filterNotNull().forEach { runCatching { it.release() } }
        lumaFine.fill(null)
        lumaCoarse.fill(null)
        mvCoarse = null
        mvFine = null
        mvSmooth = null
    }

    private fun releaseResources() {
        releaseFrameTextures()
        releaseSearchTextures()
    }

    private fun texture(w: Int, h: Int, highPrecision: Boolean): GlTextureInfo {
        val tex = GlUtil.createTexture(w, h, highPrecision)
        val fbo = GlUtil.createFboForTexture(tex)
        return GlTextureInfo(tex, fbo, C.INDEX_UNSET, w, h)
    }

    private fun roundTo16(v: Int) = max(16, (v + 8) / 16 * 16)

    private fun fail(e: Exception) {
        control.active = false
        errorExecutor.execute { errorListener.onError(VideoFrameProcessingException.from(e)) }
    }

    companion object {
        private const val BLOCK = 8
        /** Output textures in flight: enough to keep the display pipeline fed at 120 fps. */
        private const val OUTPUT_FRAMES = 4

        private fun program(context: Context, fragment: String): GlProgram =
            GlProgram(context, "memc/fullscreen.vert", "memc/$fragment").apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            }
    }
}
