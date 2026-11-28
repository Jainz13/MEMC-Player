package com.jainz.memc.stereo

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.system.Os
import com.jainz.memc.memc.NpuFlow
import java.nio.FloatBuffer
import java.util.IdentityHashMap
import kotlin.math.abs

/**
 * Video Depth Anything Small on the Snapdragon NPU, for the 3D export. It sees 32 frames at a
 * time, so depth stays steady from frame to frame.
 *
 * Frames are fed one at a time with [push]; depth comes out a window later, in order, so a
 * whole film runs in constant memory. This follows the model's own video inference exactly
 * (and make-it-3d's streaming version of it): 32-frame windows, each new window carrying 10
 * keyframes of the previous one, a least-squares scale and shift that lines each window up with
 * the last, and a linear blend across the 8 overlapping frames. Call [flush] at a scene cut and
 * at the end, so each shot is anchored to its own first frame.
 *
 * Not thread-safe: use from one thread.
 */
class VdaDepth private constructor(
    private val context: Context,
    private val env: OrtEnvironment,
    private val enc: OrtSession,
    private var tmp: OrtSession,
    private val dec: OrtSession,
) : AutoCloseable {
    /** Where the 32-frame temporal part runs ("NPU" or "CPU"), for the status line. */
    @Volatile var tmpWhere = "CPU"
        private set
    private var windows = 0

    /**
     * Moves the temporal part to the NPU once its compiled copy is ready (it is compiled in the
     * background, in its own process; see NpuCompileService).
     */
    private fun maybeUseNpu() {
        if (tmpWhere == "NPU" || windows++ % 20 != 0) return
        val ref = VdaModel.tmpNpu
        if (!NpuCompile.ready(context, ref) || !ref.hasCompiledCopy(context)) return
        val npu = runCatching { ref.open(context, env) { npuOptions(context.applicationInfo.nativeLibraryDir) } }.getOrElse {
            NpuCompile.mark(context, ref, "failed: couldn't load the compiled copy (${it.message})")
            return
        }
        val old = tmp
        tmp = npu
        tmpWhere = "NPU"
        runCatching { old.close() }
        com.jainz.memc.core.DebugRecorder.event("3d", "Temporal part moved to the NPU")
    }

    val width = VdaModel.WIDTH
    val height = VdaModel.HEIGHT
    private val plane = width * height

    /** One picture, planar R, G, B, ImageNet-normalised, top row first. Fill before [push]. */
    val input: FloatBuffer = NpuFlow.floats(3 * plane)
    private val inputTensor = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, height.toLong(), width.toLong()))

    private fun shapeOf(session: OrtSession, name: String, input: Boolean): LongArray {
        val info = (if (input) session.inputInfo else session.outputInfo)[name]?.info as? TensorInfo
            ?: throw IllegalStateException("The 3D model has no \"$name\"")
        return info.shape
    }

    private val l1Shape = shapeOf(enc, "l1rn", false)
    private val l2Shape = shapeOf(enc, "l2rn", false)
    private val l3Shape = shapeOf(enc, "l3", false)
    private val l4Shape = shapeOf(enc, "l4", false)
    private val p3Shape = shapeOf(dec, "path3", true)
    private fun count(s: LongArray) = s.fold(1L) { a, b -> a * b }.toInt()
    private val l3n = count(l3Shape)
    private val l4n = count(l4Shape)
    private val p3n = count(p3Shape)

    /** The encoder's outputs for one frame, kept while the frame is in a window or a keyframe. */
    private inner class Feat {
        val l1: FloatBuffer = NpuFlow.floats(count(l1Shape))
        val l2: FloatBuffer = NpuFlow.floats(count(l2Shape))
        val l3: FloatBuffer = NpuFlow.floats(l3n)
        val l4: FloatBuffer = NpuFlow.floats(l4n)
        val t1: OnnxTensor = OnnxTensor.createTensor(env, l1, l1Shape)
        val t2: OnnxTensor = OnnxTensor.createTensor(env, l2, l2Shape)
        val outs = mapOf(
            "l1rn" to t1,
            "l2rn" to t2,
            "l3" to OnnxTensor.createTensor(env, l3, l3Shape),
            "l4" to OnnxTensor.createTensor(env, l4, l4Shape),
        )
        var inUse = false

        fun close() = outs.values.forEach { runCatching { it.close() } }
    }

    private val pool = ArrayDeque<Feat>()
    private val allFeats = ArrayList<Feat>()

    private fun takeFeat(): Feat = (pool.removeLastOrNull() ?: Feat().also { allFeats += it }).apply { inUse = true }

    private fun give(f: Feat) {
        if (!f.inUse) return
        f.inUse = false
        pool.addLast(f)
    }

    // Temporal part: 32 frames in, 32 mixed maps out.
    private val l3All = NpuFlow.floats(FRAMES * l3n)
    private val l4All = NpuFlow.floats(FRAMES * l4n)
    private val l3AllT = OnnxTensor.createTensor(env, l3All, longArrayOf(FRAMES.toLong(), l3Shape[1], l3Shape[2], l3Shape[3]))
    private val l4AllT = OnnxTensor.createTensor(env, l4All, longArrayOf(FRAMES.toLong(), l4Shape[1], l4Shape[2], l4Shape[3]))
    private val p3All = NpuFlow.floats(FRAMES * p3n)
    private val p3AllT = OnnxTensor.createTensor(env, p3All, longArrayOf(FRAMES.toLong(), p3Shape[1], p3Shape[2], p3Shape[3]))
    private val p3Slices = Array(FRAMES) { i ->
        val view = p3All.duplicate()
        view.position(i * p3n)
        view.limit((i + 1) * p3n)
        OnnxTensor.createTensor(env, view.slice(), p3Shape)
    }

    // Decoder output, one frame.
    private val depthOut = NpuFlow.floats(plane)
    private val depthOutT = run {
        val s = shapeOf(dec, "depth", false).takeIf { s -> s.all { it > 0 } && count(s) == plane }
            ?: longArrayOf(1, height.toLong(), width.toLong())
        OnnxTensor.createTensor(env, depthOut, s)
    }

    // ---- Timing, for the export's status line ----
    @Volatile var encMs = 0f
        private set
    @Volatile var windowMs = 0f
        private set

    private fun ease(old: Float, ms: Float) = if (old == 0f) ms else old * 0.9f + ms * 0.1f

    // ---- Windowing ----
    private val fresh = ArrayList<Feat>()
    private var prevKeys: List<Feat>? = null
    private val pending = ArrayList<FloatArray>()
    private var ref0: FloatArray? = null
    private var ref1: FloatArray? = null

    /** Frames pushed whose depth hasn't come out yet. */
    val delayed: Int get() = fresh.size + pending.size

    /** Encodes the picture in [input]; returns the depth maps finished by it (oldest first, top row first). */
    fun push(): List<FloatArray> {
        val f = takeFeat()
        val start = System.nanoTime()
        input.rewind()
        enc.run(mapOf(ENC_IN to inputTensor), f.outs).close()
        encMs = ease(encMs, (System.nanoTime() - start) / 1e6f)
        fresh += f
        val need = if (prevKeys == null) FRAMES else STEP
        return if (fresh.size >= need) window() else emptyList()
    }

    /** Finishes every frame pushed so far (end of the video or a scene cut) and starts over. */
    fun flush(): List<FloatArray> {
        val n = fresh.size
        val out: List<FloatArray> = if (n > 0) {
            val need = if (prevKeys == null) FRAMES else STEP
            val pad = need - n
            repeat(pad) { fresh += fresh.last() }
            val done = window() + pending
            done.subList(0, done.size - pad).toList()
        } else {
            pending.toList()
        }
        reset()
        return out
    }

    fun reset() {
        prevKeys?.forEach { give(it) }
        fresh.forEach { give(it) }
        prevKeys = null
        fresh.clear()
        pending.clear()
        ref0 = null
        ref1 = null
    }

    private fun window(): List<FloatArray> {
        val start = System.nanoTime()
        val keys = prevKeys
        val inputs: List<Feat>
        if (keys == null) {
            inputs = fresh.subList(0, FRAMES).toList()
            val d = runWindow(inputs)
            pending.clear()
            pending.addAll(d)
            ref0 = d[KEYFRAMES[0]]
            ref1 = d[KEYFRAMES[1]]
            repeat(FRAMES) { fresh.removeAt(0) }
        } else {
            inputs = keys + fresh.subList(0, STEP)
            val d = runWindow(inputs)
            val (s, b) = fit(d[0], d[1], ref0!!, ref1!!)
            for (i in 0 until FRAMES) align(d[i], s, b)
            // Blend the overlap: the old window's last frames fade into the new one's.
            val base = pending.size - INTERP_LEN
            for (i in 0 until INTERP_LEN) {
                val w = i / (INTERP_LEN - 1f)
                val pre = pending[base + i]
                val post = d[2 + i]
                for (k in pre.indices) pre[k] = pre[k] * (1f - w) + post[k] * w
            }
            for (i in OVERLAP until FRAMES) pending += d[i]
            ref1 = d[KEYFRAMES[1]]
            repeat(STEP) { fresh.removeAt(0) }
        }
        // Keep the next window's keyframes, give the other frames' buffers back.
        val next = KEYFRAMES.map { inputs[it] }
        val keep = IdentityHashMap<Feat, Boolean>()
        next.forEach { keep[it] = true }
        fresh.forEach { keep[it] = true }
        inputs.forEach { if (keep[it] != true) give(it) }
        prevKeys = next
        windowMs = ease(windowMs, (System.nanoTime() - start) / 1e6f)
        val out = pending.subList(0, pending.size - INTERP_LEN).toList()
        repeat(out.size) { pending.removeAt(0) }
        return out
    }

    private fun runWindow(inputs: List<Feat>): Array<FloatArray> {
        maybeUseNpu()
        l3All.clear()
        l4All.clear()
        for (f in inputs) {
            f.l3.rewind(); l3All.put(f.l3)
            f.l4.rewind(); l4All.put(f.l4)
        }
        l3All.rewind()
        l4All.rewind()
        tmp.run(mapOf("l3" to l3AllT, "l4" to l4AllT), mapOf("path3" to p3AllT)).close()
        return Array(FRAMES) { i ->
            dec.run(mapOf("path3" to p3Slices[i], "l2rn" to inputs[i].t2, "l1rn" to inputs[i].t1), mapOf("depth" to depthOutT)).close()
            depthOut.rewind()
            FloatArray(plane).also { depthOut.get(it) }
        }
    }

    /** Least-squares scale and shift so pred * s + b matches the reference keyframes. */
    private fun fit(p0: FloatArray, p1: FloatArray, t0: FloatArray, t1: FloatArray): Pair<Float, Float> {
        var a00 = 0.0
        var a01 = 0.0
        var b0 = 0.0
        var b1 = 0.0
        for ((p, t) in listOf(p0 to t0, p1 to t1)) {
            for (k in p.indices) {
                val x = p[k].toDouble()
                val y = t[k].toDouble()
                a00 += x * x
                a01 += x
                b0 += x * y
                b1 += y
            }
        }
        val a11 = (p0.size + p1.size).toDouble()
        val det = a00 * a11 - a01 * a01
        if (abs(det) < 1e-6) return 1f to 0f
        return ((a11 * b0 - a01 * b1) / det).toFloat() to ((-a01 * b0 + a00 * b1) / det).toFloat()
    }

    private fun align(d: FloatArray, s: Float, b: Float) {
        for (k in d.indices) d[k] = (d[k] * s + b).coerceAtLeast(0f)
    }

    override fun close() {
        reset()
        allFeats.forEach { it.close() }
        allFeats.clear()
        pool.clear()
        listOf(inputTensor, l3AllT, l4AllT, p3AllT, depthOutT).forEach { runCatching { it.close() } }
        p3Slices.forEach { runCatching { it.close() } }
        listOf(enc, tmp, dec).forEach { runCatching { it.close() } }
    }

    companion object {
        const val FRAMES = VdaModel.FRAMES
        private const val OVERLAP = 10
        private const val INTERP_LEN = 8
        private const val STEP = FRAMES - OVERLAP
        private val KEYFRAMES = intArrayOf(0, 12, 24, 25, 26, 27, 28, 29, 30, 31)
        private const val ENC_IN = "image"

        /** Prepares the three parts for this chip (the first time takes a few minutes). */
        fun create(context: Context, onStep: (String) -> Unit = {}): VdaDepth {
            if (!NpuFlow.isSnapdragon) throw IllegalStateException("The 3D export needs a Snapdragon chip's NPU")
            if (!VdaModel.ready(context)) throw IllegalStateException("the 3D model isn't downloaded")
            val libs = context.applicationInfo.nativeLibraryDir
            runCatching { Os.setenv("ADSP_LIBRARY_PATH", "$libs;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp", true) }
            val env = OrtEnvironment.getEnvironment()
            val sessions = ArrayList<OrtSession>()
            try {
                for ((i, ref) in VdaModel.refs.withIndex()) {
                    if (i == 1) {
                        // The 32-frame temporal part: the NPU compiler doesn't finish with it
                        // (it stalled for 20+ minutes on a Galaxy S24 and on AI Hub), and Qualcomm's
                        // GPU back end was slower than the CPU (1.36 s vs 1.22 s per window).
                        onStep("Loading the AI 3D model (2/3)…")
                        sessions += env.createSession(ref.bytes(context), cpuOptions())
                        continue
                    }
                    onStep("Preparing the AI 3D model for this phone (${i + 1}/3, first time takes a few minutes)…")
                    sessions += ref.open(context, env) { options(libs) }
                }
                // The NPU version of the temporal part: compiled in the background the first time
                // (the export starts on the CPU and moves over when it's ready).
                NpuCompile.request(context.applicationContext, VdaModel.tmpNpu)
                val vda = VdaDepth(context.applicationContext, env, sessions[0], sessions[1], sessions[2])
                // Test run, so a broken setup fails here and not an hour into a film.
                onStep("Testing the AI 3D model…")
                vda.input.clear()
                vda.input.put(FloatArray(3 * vda.plane))
                repeat(FRAMES) { vda.push() }
                vda.reset()
                vda.encMs = 0f
                vda.windowMs = 0f
                return vda
            } catch (e: Throwable) {
                sessions.forEach { runCatching { it.close() } }
                throw e
            }
        }

        private fun cpuOptions() = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Four threads: more barely helps and heats the phone (CPU hit 97 °C with six).
            setIntraOpNumThreads(4)
        }

        private fun npuOptions(libs: String) = options(libs)

        private fun options(libs: String) = OrtSession.SessionOptions().apply {
            addQnn(
                mapOf(
                    "backend_path" to "$libs/libQnnHtp.so",
                    "htp_performance_mode" to "sustained_high_performance",
                    "htp_graph_finalization_optimization_mode" to "3",
                    "enable_htp_fp16_precision" to "1",
                )
            )
        }
    }
}
