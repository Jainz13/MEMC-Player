package com.jainz.memc.memc

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
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

    // AI interpolation on the NPU (export). Null until first used; stays null if it failed.
    private var npu: NpuFlow? = null
    private var npuTried = false
    private var npuOwned = false
    private val lumaValid = BooleanArray(2)
    private var motionReady = false
    private var npuFlowValid = false
    private var npuFlowIn: FloatArray? = null
    private var npuFlowOut: FloatArray? = null
    private var npuBytes: ByteArray? = null
    private var npuPlanar: FloatArray? = null
    private var npuTexW = 0
    private var npuTexH = 0
    private val npuSmallProgram = program(context, "npu_small.frag")
    private val npuInterpProgram = program(context, "npu_interp.frag")
    private val npuSmall = arrayOfNulls<GlTextureInfo>(2)
    private val npuImage = arrayOfNulls<java.nio.FloatBuffer>(2)
    private val npuImageValid = BooleanArray(2)
    private var npuPixels: java.nio.ByteBuffer? = null
    private var npuFlowUpload: java.nio.FloatBuffer? = null
    private var npuFlowTex = 0
    private var npuMaskTex = 0
    private var npuSceneCut = false

    // Governor state.
    private var lastWallNs = 0L
    private val behind = ArrayDeque<Boolean>()
    private var lastEaseNs = 0L
    private var lastRecoverNs = 0L
    private var recoverBudget = 2
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

    // ---- GPU time, for the stats overlay ------------------------------------------------------------
    // The GPU reports how long it spent on the work between "begin" and "end" (a timer query).
    // Results arrive a few frames later and are added up in PerfStats.

    private var timerSupported: Boolean? = null
    private var activeQuery = 0
    private val pendingQueries = ArrayDeque<Int>()
    /** NPU time that fell inside each pending measurement (the GPU was only waiting then). */
    private val pendingNpuNs = ArrayDeque<Long>()
    private var npuNsAtBegin = 0L
    private val queryValue = IntArray(1)

    /** Returns true when a measurement was started (and so must be ended). */
    private fun gpuTimerBegin(): Boolean {
        try {
            if (timerSupported == null) {
                timerSupported = GLES20.glGetString(GLES20.GL_EXTENSIONS)?.contains("GL_EXT_disjoint_timer_query") == true
                if (timerSupported != true) PerfStats.gpuTimerWorks = false
            }
            if (timerSupported != true || activeQuery != 0) return false
            // Collect measurements that have finished.
            while (pendingQueries.isNotEmpty()) {
                val q = pendingQueries.first()
                GLES30.glGetQueryObjectuiv(q, GLES30.GL_QUERY_RESULT_AVAILABLE, queryValue, 0)
                if (queryValue[0] == 0) break
                GLES30.glGetQueryObjectuiv(q, GLES30.GL_QUERY_RESULT, queryValue, 0)
                val waited = pendingNpuNs.removeFirst()
                PerfStats.gpuNs.addAndGet(((queryValue[0].toLong() and 0xFFFFFFFFL) - waited).coerceAtLeast(0))
                GLES30.glDeleteQueries(1, intArrayOf(q), 0)
                pendingQueries.removeFirst()
            }
            if (pendingQueries.size > 32) return false
            // Start from a clean error state (bounded: a lost context reports errors forever).
            var guard = 0
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR && guard++ < 8) Unit
            GLES30.glGenQueries(1, queryValue, 0)
            val q = queryValue[0]
            GLES30.glBeginQuery(GL_TIME_ELAPSED, q)
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                timerSupported = false
                PerfStats.gpuTimerWorks = false
                return false
            }
            activeQuery = q
            npuNsAtBegin = PerfStats.npuNs.get()
            return true
        } catch (e: Throwable) {
            timerSupported = false
            PerfStats.gpuTimerWorks = false
            return false
        }
    }

    private fun gpuTimerEnd() {
        val q = activeQuery
        if (q == 0) return
        activeQuery = 0
        runCatching {
            GLES30.glEndQuery(GL_TIME_ELAPSED)
            pendingQueries.addLast(q)
            pendingNpuNs.addLast(PerfStats.npuNs.get() - npuNsAtBegin)
        }
    }

    override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
        val timed = gpuTimerBegin()
        try {
            process(inputTexture, presentationTimeUs)
        } finally {
            if (timed) gpuTimerEnd()
        }
    }

    private fun process(inputTexture: GlTextureInfo, presentationTimeUs: Long) {
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
            val interpolate = control.enabled && control.thermalLevel < 4 && control.easeLevel < MAX_EASE && dt in VALID_DT &&
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
                npuImageValid[slot] = false
                inputListener.onInputFrameProcessed(inputTexture)
                // Brightness maps and motion search are only made if the GPU engine ends up drawing
                // this pair; the AI path doesn't need them.
                lumaValid[slot] = false
                motionReady = false
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
        control.bufferedFrames = inFlight.size
        if (retired.remove(outputTexture)) {
            runCatching { outputTexture.release() }
        } else {
            freeOutputs.addLast(outputTexture)
        }
        val timed = jobs.isNotEmpty() && gpuTimerBegin()
        try {
            drain()
        } finally {
            if (timed) gpuTimerEnd()
        }
    }

    override fun signalEndOfCurrentInputStream() {
        held = 0
        lastPtsUs = C.TIME_UNSET
        recentDeltas.clear()
        resetGovernor()
        control.easeLevel = 0
        recoverBudget = 2
        lastRecoverNs = 0L
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
        control.bufferedFrames = 0
        freeOutputs.clear()
        freeOutputs.addAll(outputs)
        inputListener.onFlush()
        readySignalled = true
        inputListener.onReadyToAcceptInputFrame()
    }

    override fun release() {
        activeQuery = 0
        pendingQueries.clear()
        pendingNpuNs.clear()
        try {
            releaseFrameTextures()
            releaseSearchTextures()
            retired.forEach { runCatching { it.release() } }
            retired.clear()
            npuSmall.filterNotNull().forEach { runCatching { it.release() } }
            if (npuFlowTex != 0) GLES20.glDeleteTextures(2, intArrayOf(npuFlowTex, npuMaskTex), 0)
            if (npuOwned) runCatching { npu?.close() }
            npu = null
            listOf(npuSmallProgram, npuInterpProgram).forEach { it.delete() }
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
        // Stepped down earlier and it has been smooth with a well-filled buffer for a while:
        // try the heavier setting again (a couple of times at most per video).
        if (control.easeLevel > 0 && recoverBudget > 0 && now - lastEaseNs > RECOVER_AFTER_NS &&
            outputs.isNotEmpty() && freeOutputs.size * 4 <= outputs.size
        ) {
            control.easeLevel--
            recoverBudget--
            lastRecoverNs = now
            lastEaseNs = now
            behind.clear()
            cooldown = GOVERNOR_WINDOW * 2
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
        val now = System.nanoTime()
        // Stepping back up didn't hold: stop trying for this video.
        if (lastRecoverNs != 0L && now - lastRecoverNs < 20_000_000_000L) recoverBudget = 0
        lastEaseNs = now
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

    /** Block-matching motion for the current pair, computed on first use. */
    private fun ensureMotion() {
        if (motionReady) return
        for (i in intArrayOf(prev, cur)) {
            if (lumaValid[i]) continue
            drawLuma(frames[i]!!, lumaFine[i]!!)
            drawCopy(lumaFine[i]!!, lumaCoarse[i]!!)
            lumaValid[i] = true
        }
        runMotionSearch()
        motionReady = true
    }

    private fun plan(previousPts: Long, pts: Long, dt: Long, target: Int) {
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
                when {
                    !job.interpolate -> drawCopy(frames[cur]!!, out)
                    !drawInterpNpu(out, job.alpha) -> drawInterp(out, job.alpha)
                }
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
        control.bufferedFrames = inFlight.size
        PerfStats.framesOut.incrementAndGet()
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
        ensureMotion()
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

    // ---- AI interpolation (RIFE on the NPU) ----------------------------------------------------------

    /**
     * Builds the in-between frame with the AI model. Returns false when the NPU isn't in use
     * (not requested, failed to start, or failed mid-way), so the caller uses block matching.
     */
    private fun drawInterpNpu(out: GlTextureInfo, alpha: Float): Boolean {
        val live = control.adaptive
        // Playback: only on the "AI" quality setting and while the phone keeps up.
        if (live && (control.quality != 3 || control.easeLevel >= 1)) return false
        // A hot phone gets the lighter GPU engine (playback and export alike).
        if (control.thermalLevel >= 2) {
            if (control.quality == 3 || !live) control.npuStatus = "AI paused: the phone is hot - using the GPU engine until it cools"
            return false
        }
        val n = ensureNpu() ?: return false
        try {
            val w = n.width
            val h = n.height
            var refreshed = false
            for (i in intArrayOf(prev, cur)) {
                if (npuImageValid[i]) continue
                readSmall(i, w, h)
                npuImageValid[i] = true
                refreshed = true
            }
            if (refreshed) {
                npuSceneCut = isSceneCut(npuImage[prev]!!, npuImage[cur]!!, w * h)
                npuFlowValid = false
            }
            if (npuSceneCut) {
                // A cut has no in-between: show the nearer real frame.
                drawCopy(frames[if (alpha < 0.5f) prev else cur]!!, out)
                return true
            }

            // Export asks the model for each exact moment. Playback asks once per pair of
            // source frames (at the midpoint) and rescales that motion for the other moments,
            // which keeps the NPU work to one pass per source frame.
            if (!live || !npuFlowValid) {
                val (flow, mask) = n.run(npuImage[prev]!!, npuImage[cur]!!, if (live) 0.5f else alpha)
                // Planar (x0,y0,x1,y1 planes) -> one RGBA texel per pixel.
                // Done on plain arrays: element-by-element buffer access was costing several
                // milliseconds per source frame.
                val upload = npuFlowUpload!!
                val plane = w * h
                val src = npuFlowIn ?: FloatArray(4 * plane).also { npuFlowIn = it }
                val dst = npuFlowOut ?: FloatArray(4 * plane).also { npuFlowOut = it }
                flow.rewind()
                flow.get(src, 0, 4 * plane)
                var o = 0
                for (p in 0 until plane) {
                    dst[o] = src[p]
                    dst[o + 1] = src[plane + p]
                    dst[o + 2] = src[2 * plane + p]
                    dst[o + 3] = src[3 * plane + p]
                    o += 4
                }
                upload.clear()
                upload.put(dst, 0, 4 * plane)
                upload.rewind()
                mask.rewind()
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, npuFlowTex)
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RGBA, GLES20.GL_FLOAT, upload)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, npuMaskTex)
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RED, GLES20.GL_FLOAT, mask)
                GlUtil.checkGlError()
                npuFlowValid = true
            }

            draw(out, npuInterpProgram) { p ->
                p.setSamplerTexIdUniform("uPrev", frames[prev]!!.texId, 0)
                p.setSamplerTexIdUniform("uCur", frames[cur]!!.texId, 1)
                p.setSamplerTexIdUniform("uFlow", npuFlowTex, 2)
                p.setSamplerTexIdUniform("uMask", npuMaskTex, 3)
                p.setFloatsUniform("uFlowToUv", floatArrayOf(1f / w, 1f / h))
                p.setFloatsUniform("uFlowScale", if (live) floatArrayOf(alpha / 0.5f, (1f - alpha) / 0.5f) else floatArrayOf(1f, 1f))
                p.setFloatUniform("uAlpha", alpha)
                p.setIntUniform("uTimeBlend", if (live) 1 else 0)
            }
            control.npuStatus = String.format(java.util.Locale.US, "AI on the NPU, %.0f ms per pass", n.averageMs)
            return true
        } catch (e: Throwable) {
            npuFailed(e)
            return false
        }
    }

    /** The model to use, or null. Adopts the player's instance or creates one for export. */
    private fun ensureNpu(): NpuFlow? {
        npu?.let { current ->
            // The player may have dropped or replaced its instance.
            if (!npuOwned && control.npu !== current) npu = null else return current
        }
        val shared = control.npu
        if (shared == null) {
            val factory = control.npuFactory ?: return null
            if (npuTried) return null
            npuTried = true
            try {
                control.npuStatus = "starting"
                npu = factory()
                npuOwned = true
            } catch (e: Throwable) {
                npuFailed(e)
                return null
            }
        } else {
            npu = shared
            npuOwned = false
            // A freshly started model is slow for its first passes: don't judge the load yet.
            resetGovernor()
            cooldown = GOVERNOR_WINDOW * 4
        }
        val n = npu ?: return null
        return try {
            if (npuTexW != n.width || npuTexH != n.height) {
                npuSmall.filterNotNull().forEach { runCatching { it.release() } }
                if (npuFlowTex != 0) GLES20.glDeleteTextures(2, intArrayOf(npuFlowTex, npuMaskTex), 0)
                for (i in 0..1) {
                    npuFlowIn = null; npuFlowOut = null; npuBytes = null; npuPlanar = null
                    npuSmall[i] = texture(n.width, n.height, false)
                    npuImage[i] = NpuFlow.floats(3 * n.width * n.height)
                    npuImageValid[i] = false
                }
                npuPixels = java.nio.ByteBuffer.allocateDirect(n.width * n.height * 4).order(java.nio.ByteOrder.nativeOrder())
                npuFlowUpload = NpuFlow.floats(4 * n.width * n.height)
                npuFlowTex = floatTexture(n.width, n.height, GLES30.GL_RGBA16F, GLES30.GL_RGBA)
                npuMaskTex = floatTexture(n.width, n.height, GLES30.GL_R16F, GLES30.GL_RED)
                npuTexW = n.width
                npuTexH = n.height
            }
            n
        } catch (e: Throwable) {
            npuFailed(e)
            null
        }
    }

    private fun npuFailed(e: Throwable) {
        control.npuStatus = "AI unavailable (${(e.message ?: e.javaClass.simpleName).take(160)}) - used the GPU engine"
        if (npuOwned) runCatching { npu?.close() } else control.npu = null
        npu = null
    }

    /** Renders frame [i] at the model's size and reads it back as planar RGB floats. */
    private fun readSmall(i: Int, w: Int, h: Int) {
        val small = npuSmall[i]!!
        draw(small, npuSmallProgram) { p ->
            p.setSamplerTexIdUniform("uTex", frames[i]!!.texId, 0)
            p.setIntUniform("uHdr", if (useHdr) 1 else 0)
        }
        val pixels = npuPixels!!
        pixels.clear()
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        GlUtil.checkGlError()
        val image = npuImage[i]!!
        val plane = w * h
        val bytes = npuBytes ?: ByteArray(4 * plane).also { npuBytes = it }
        val floats = npuPlanar ?: FloatArray(3 * plane).also { npuPlanar = it }
        pixels.rewind()
        pixels.get(bytes, 0, 4 * plane)
        val scale = 1f / 255f
        var o = 0
        for (p in 0 until plane) {
            floats[p] = (bytes[o].toInt() and 0xff) * scale
            floats[plane + p] = (bytes[o + 1].toInt() and 0xff) * scale
            floats[2 * plane + p] = (bytes[o + 2].toInt() and 0xff) * scale
            o += 4
        }
        image.clear()
        image.put(floats, 0, 3 * plane)
        image.rewind()
    }

    /** Mean brightness change over a sparse grid; a large jump means the shot changed. */
    private fun isSceneCut(a: java.nio.FloatBuffer, b: java.nio.FloatBuffer, plane: Int): Boolean {
        var sum = 0f
        var count = 0
        var p = 0
        while (p < plane) {
            sum += kotlin.math.abs(a.get(plane + p) - b.get(plane + p))   // green plane
            count++
            p += 37
        }
        return count > 0 && sum / count > 0.16f
    }

    private fun floatTexture(w: Int, h: Int, internalFormat: Int, format: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, GLES20.GL_FLOAT, null)
        GlUtil.checkGlError()
        return ids[0]
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
                lumaValid[i] = false
                motionReady = false
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
        /** GL_TIME_ELAPSED_EXT, from GL_EXT_disjoint_timer_query. */
        private const val GL_TIME_ELAPSED = 0x88BF
        private const val BLOCK = 8
        private val VALID_DT = 4_000L..250_000L
        /** GPU memory for pre-rendered frames; the buffer is this divided by the frame size. */
        // Frames rendered ahead of the screen: about 1 GB of graphics memory, at most ~1.2 s at 60 fps.
        private const val POOL_BUDGET_BYTES = 1024L * 1024 * 1024
        private const val MIN_POOL = 4
        private const val MAX_POOL = 72
        private const val RECOVER_AFTER_NS = 30_000_000_000L
        private const val GOVERNOR_WINDOW = 48
        const val MAX_EASE = 4
        private const val BOOST_PEAK_NITS = 1600f

        private fun program(context: Context, fragment: String): GlProgram =
            GlProgram(context, "memc/fullscreen.vert", "memc/$fragment").apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            }
    }
}
