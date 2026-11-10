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
 * Picture adjustments and motion-compensated frame interpolation (MEMC) on the GPU.
 *
 * For every new source frame:
 *  1. ingest it once: scale down to at most the screen's resolution and apply the picture
 *     shader (boost / Auto HDR) in the same pass;
 *  2. make a small grey-scale copy and block-match it against the previous frame
 *     (coarse full search, seeded refinement, 3x3 vector median);
 *  3. emit frames on the target-fps time grid. Grid points between two source frames are
 *     interpolated by following the motion vectors into both frames; unreliable areas fall
 *     back to the nearest real frame instead of smearing.
 *
 * Frames are emitted with their own timestamps into a pool of output textures. The pool is the
 * pre-render buffer: the engine works ahead of the screen until the pool is full, which absorbs
 * short spikes. If it still can't keep up, a governor lowers the processing size step by step
 * and finally pauses interpolation rather than letting playback stutter.
 */
@UnstableApi
class MemcShaderProgram(
    context: Context,
    private val useHdr: Boolean,
    private val control: MemcControl,
    private val picture: PictureControl?,
) : GlShaderProgram {

    private class Job(val alpha: Float, val ptsUs: Long, val interpolate: Boolean)

    private var inputListener: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
    private var outputListener: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
    private var errorListener: GlShaderProgram.ErrorListener = GlShaderProgram.ErrorListener { }
    private var errorExecutor: Executor = Executor { it.run() }

    private val copyProgram = program(context, "copy.frag")
    private val pictureProgram = if (picture != null) program(context, "picture.frag") else null
    private val lumaProgram = program(context, "luma.frag")
    private val searchProgram = program(context, "search.frag")
    private val refineProgram = program(context, "refine.frag")
    private val smoothProgram = program(context, "smooth.frag")
    private val interpProgram = program(context, "interp.frag")

    // Resources, sized for the current video.
    private var procW = 0
    private var procH = 0
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
    /** Output textures currently held by the display pipeline. */
    private val inFlight = HashSet<GlTextureInfo>()
    /** In-flight textures of an old size; deleted when they come back. */
    private val retired = HashSet<GlTextureInfo>()

    // Frame state.
    private var held = 0
    private var cur = 0
    private var prev = 0
    private var lastPtsUs = C.TIME_UNSET
    private val jobs = ArrayDeque<Job>()
    private val recentDeltas = ArrayDeque<Long>()
    private var readySignalled = false
    private var endOfStreamPending = false

    // Governor state.
    private var lastWallNs = 0L
    private val behind = ArrayDeque<Boolean>()
    private var cooldown = GOVERNOR_WINDOW

    private val coeffs = if (useHdr) floatArrayOf(0.2627f, 0.6780f, 0.0593f) else floatArrayOf(0.2126f, 0.7152f, 0.0722f)
    // Media3's HDR working space is linear with 1.0 = 1000 nits; bring ~100 nits to ~1.
    private val lumaScale = if (useHdr) 10f else 3f

    init {
        picture?.pipelineHdr = useHdr
    }

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
        jobs.clear()
        try {
            val previousPts = lastPtsUs
            val dt = if (previousPts != C.TIME_UNSET) presentationTimeUs - previousPts else -1L
            noteTiming(dt)
            govern(dt)
            ensureResources(inputTexture.width, inputTexture.height)
            GLES20.glDisable(GLES20.GL_BLEND)

            val target = control.effectiveTargetFps()
            val interpolate = control.enabled && control.easeLevel < MAX_EASE && dt in VALID_DT &&
                control.sourceFps > 0f && control.sourceFps < target * 0.8f
            control.active = interpolate
            lastPtsUs = presentationTimeUs

            if (!interpolate && freeOutputs.isNotEmpty()) {
                // Nothing to interpolate: one pass straight into an output frame.
                val out = freeOutputs.removeFirst()
                ingest(inputTexture, out)
                inputListener.onInputFrameProcessed(inputTexture)
                held = 0
                emit(out, presentationTimeUs)
            } else {
                val slot = if (held == 0) 0 else 1 - cur
                ingest(inputTexture, frames[slot]!!)
                inputListener.onInputFrameProcessed(inputTexture)
                drawLuma(frames[slot]!!, lumaFine[slot]!!)
                drawCopy(lumaFine[slot]!!, lumaCoarse[slot]!!)
                val hadPrevious = held > 0
                prev = cur
                cur = slot
                held = min(2, held + 1)
                if (interpolate && hadPrevious) {
                    plan(previousPts, presentationTimeUs, dt, target)
                } else {
                    jobs.addLast(Job(1f, presentationTimeUs, false))
                }
            }
        } catch (e: Exception) {
            fail(e)
            return
        }
        drain()
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
        if (!inFlight.remove(outputTexture)) return
        if (retired.remove(outputTexture)) {
            runCatching { outputTexture.release() }
        } else {
            freeOutputs.addLast(outputTexture)
        }
        drain()
    }

    override fun signalEndOfCurrentInputStream() {
        held = 0
        lastPtsUs = C.TIME_UNSET
        recentDeltas.clear()
        resetGovernor()
        control.easeLevel = 0
        if (jobs.isEmpty()) outputListener.onCurrentOutputStreamEnded() else endOfStreamPending = true
    }

    override fun flush() {
        jobs.clear()
        endOfStreamPending = false
        held = 0
        lastPtsUs = C.TIME_UNSET
        recentDeltas.clear()
        resetGovernor()
        // After a flush the display pipeline has dropped every frame it held.
        retired.forEach { runCatching { it.release() } }
        retired.clear()
        inFlight.clear()
        freeOutputs.clear()
        freeOutputs.addAll(outputs)
        inputListener.onFlush()
        readySignalled = true
        inputListener.onReadyToAcceptInputFrame()
    }

    override fun release() {
        try {
            releaseFrameTextures()
            releaseSearchTextures()
            retired.forEach { runCatching { it.release() } }
            retired.clear()
            listOfNotNull(copyProgram, pictureProgram, lumaProgram, searchProgram, refineProgram, smoothProgram, interpProgram)
                .forEach { it.delete() }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    // ---- Timing and load governor ------------------------------------------------------------

    private fun noteTiming(dt: Long) {
        if (dt !in VALID_DT) return
        recentDeltas.addLast(dt)
        if (recentDeltas.size > 9) recentDeltas.removeFirst()
        val sorted = recentDeltas.sorted()
        control.sourceFps = 1_000_000f / sorted[sorted.size / 2]
    }

    /**
     * Detects "can't keep up": frames take longer to produce than they last on screen while the
     * pre-render buffer is empty. Sustained for ~2 seconds, the workload is eased one step.
     */
    private fun govern(dtUs: Long) {
        if (!control.adaptive || control.maxLongSide <= 0) return
        val now = System.nanoTime()
        val last = lastWallNs
        lastWallNs = now
        val requested = control.takeEaseRequest()
        if (cooldown > 0) {
            cooldown--
            return
        }
        if (requested) {
            ease()
            return
        }
        if (last == 0L || dtUs !in VALID_DT || outputs.isEmpty()) return
        val expectedNs = dtUs * 1000.0 / control.playbackSpeed.coerceIn(0.25f, 4f)
        val ratio = (now - last) / expectedNs
        val bufferEmpty = freeOutputs.size * 2 >= outputs.size
        behind.addLast(ratio > 1.08 && ratio < 4.0 && bufferEmpty)
        if (behind.size > GOVERNOR_WINDOW) behind.removeFirst()
        if (behind.size == GOVERNOR_WINDOW && behind.count { it } >= GOVERNOR_WINDOW * 3 / 4) ease()
    }

    private fun ease() {
        if (control.easeLevel < MAX_EASE) control.easeLevel++
        behind.clear()
        cooldown = GOVERNOR_WINDOW * 3 / 2
    }

    private fun resetGovernor() {
        lastWallNs = 0L
        behind.clear()
        cooldown = GOVERNOR_WINDOW
    }

    // ---- Scheduling ----------------------------------------------------------------------

    private fun plan(previousPts: Long, pts: Long, dt: Long, target: Int) {
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
        if (jobs.isEmpty()) jobs.addLast(Job(1f, pts, false))
    }

    private fun drain() {
        try {
            while (jobs.isNotEmpty() && freeOutputs.isNotEmpty()) {
                val job = jobs.removeFirst()
                val out = freeOutputs.removeFirst()
                if (job.interpolate) drawInterp(out, job.alpha) else drawCopy(frames[cur]!!, out)
                emit(out, job.ptsUs)
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

    private fun emit(out: GlTextureInfo, ptsUs: Long) {
        inFlight.add(out)
        outputListener.onOutputFrameAvailable(out, ptsUs)
    }

    // ---- Passes -------------------------------------------------------------------------------

    /** First touch of a source frame: scale to the processing size and apply picture settings. */
    private fun ingest(src: GlTextureInfo, dst: GlTextureInfo) {
        val pic = picture
        val prog = pictureProgram
        if (pic != null && prog != null && pic.needsShader(useHdr)) {
            val mode = when {
                !useHdr -> 0
                pic.fakedHdr -> 2
                else -> 1
            }
            pic.lastMode = mode
            draw(dst, prog) { p ->
                p.setSamplerTexIdUniform("uTex", src.texId, 0)
                p.setIntUniform("uMode", mode)
                p.setFloatUniform("uBoost", pic.boost.coerceIn(0f, 1f))
                p.setFloatUniform("uSdrWhiteNits", pic.sdrWhiteNits)
                p.setFloatUniform("uPeakNits", if (mode == 2) pic.sdrPeakNits else BOOST_PEAK_NITS)
            }
        } else {
            pic?.lastMode = if (useHdr) 1 else 0
            drawCopy(src, dst)
        }
    }

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

    private fun ensureResources(inW: Int, inH: Int) {
        val cap = control.longSideCap()
        val long = max(inW, inH)
        val scale = if (long > cap) cap.toFloat() / long else 1f
        val pw = even(inW * scale)
        val ph = even(inH * scale)
        if (pw != procW || ph != procH || frames[0] == null) {
            // New video or new processing size: rebuild the frame textures. Output frames the
            // display still holds are deleted when they come back.
            releaseFrameTextures()
            releaseSearchTextures()
            procW = pw
            procH = ph
            for (i in 0..1) frames[i] = texture(pw, ph, useHdr)
            val bytesPerFrame = pw.toLong() * ph * (if (useHdr) 8 else 4)
            val count = (POOL_BUDGET_BYTES / bytesPerFrame).toInt().coerceIn(MIN_POOL, MAX_POOL)
            repeat(count) { outputs += texture(pw, ph, useHdr) }
            freeOutputs.addAll(outputs)
            held = 0
            control.procWidth = pw
            control.procHeight = ph
            control.bufferFrames = count
        }
        val quality = control.effectiveQuality()
        if (quality != allocatedQuality || mvSmooth == null) {
            releaseSearchTextures()
            allocatedQuality = quality
            // Motion search size: multiples of 16 so both pyramid levels divide into 8-px blocks.
            val wanted = when (quality) { 0 -> 320; 2 -> 640; else -> 480 }
            if (pw >= ph) {
                workW = roundTo16(min(wanted, max(64, pw)))
                workH = roundTo16(max(32, (workW.toFloat() * ph / pw).roundToInt()))
            } else {
                workH = roundTo16(min(wanted, max(64, ph)))
                workW = roundTo16(max(32, (workH.toFloat() * pw / ph).roundToInt()))
            }
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
        frames.filterNotNull().forEach { runCatching { it.release() } }
        frames.fill(null)
        for (o in outputs) {
            if (o in inFlight) retired += o else runCatching { o.release() }
        }
        outputs.clear()
        freeOutputs.clear()
        jobs.clear()
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

    private fun even(v: Float): Int = max(2, v.roundToInt() / 2 * 2)

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
        private val VALID_DT = 4_000L..250_000L
        /** GPU memory for pre-rendered frames; the buffer is this divided by the frame size. */
        private const val POOL_BUDGET_BYTES = 480L * 1024 * 1024
        private const val MIN_POOL = 4
        private const val MAX_POOL = 24
        private const val GOVERNOR_WINDOW = 48
        const val MAX_EASE = 4
        private const val BOOST_PEAK_NITS = 1600f

        private fun program(context: Context, fragment: String): GlProgram =
            GlProgram(context, "memc/fullscreen.vert", "memc/$fragment").apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            }
    }
}
