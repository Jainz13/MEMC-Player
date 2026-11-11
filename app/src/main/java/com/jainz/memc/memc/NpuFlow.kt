package com.jainz.memc.memc

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.system.Os
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * RIFE's motion network on the Snapdragon NPU (ONNX Runtime with Qualcomm's QNN backend).
 *
 * Given two small frames and a moment t between them it returns, for every pixel of the
 * in-between frame, where that pixel comes from in each frame (flow) and how to blend the two
 * (mask). The GPU then builds the full-size frame from that.
 *
 * The first start compiles the model for this phone's chip, which can take up to a minute;
 * the result is cached in the app's storage.
 */
class NpuFlow private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    val width: Int,
    val height: Int,
) : AutoCloseable {

    private val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
    private val tShape = longArrayOf(1, 1, height.toLong(), width.toLong())
    private val timestep: FloatBuffer = floats(width * height)

    /** Average milliseconds per frame so far. */
    @Volatile var averageMs = 0f
        private set
    @Volatile var runs = 0
        private set

    private var timestepValue = Float.NaN

    /** Returns flow (4 planes of width*height) and mask (1 plane), valid until the next call. */
    fun run(img0: FloatBuffer, img1: FloatBuffer, t: Float): Pair<FloatBuffer, FloatBuffer> {
        val start = System.nanoTime()
        if (t != timestepValue) {
            timestep.clear()
            for (i in 0 until width * height) timestep.put(t)
            timestepValue = t
        }
        timestep.rewind()
        img0.rewind()
        img1.rewind()
        OnnxTensor.createTensor(env, img0, shape).use { a ->
            OnnxTensor.createTensor(env, img1, shape).use { b ->
                OnnxTensor.createTensor(env, timestep, tShape).use { c ->
                    session.run(mapOf("img0" to a, "img1" to b, "timestep" to c)).use { result ->
                        val flow = (result.get(0) as OnnxTensor).floatBuffer
                        val mask = (result.get(1) as OnnxTensor).floatBuffer
                        val spent = System.nanoTime() - start
                        PerfStats.npuNs.addAndGet(spent)
                        PerfStats.npuRuns.incrementAndGet()
                        val ms = spent / 1_000_000f
                        runs++
                        averageMs += (ms - averageMs) / runs
                        return flow to mask
                    }
                }
            }
        }
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        /** A bundled model: asset path and input size. */
        class Model(val name: String, val width: Int, val height: Int) {
            val asset get() = "npu/$name.onnx"
        }

        /** Larger input: finer motion, 35 ms per frame on the S24 Ultra. Used for export. */
        val EXPORT = Model("rife_640x352", 640, 352)
        /** Smaller input: 17 ms per frame. Used for live playback. */
        val LIVE = Model("rife_448x256", 448, 256)
        private const val PREFS = "npu_state"

        fun floats(count: Int): FloatBuffer =
            ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        /** "" (never tried), "trying", "ok", or "failed: reason". Written synchronously so a crash can't lose it. */
        fun state(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("state", "") ?: ""

        fun setState(context: Context, state: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("state", state).commit()
        }

        fun hasModel(context: Context, m: Model): Boolean =
            runCatching { context.assets.open(m.asset).use { true } }.getOrDefault(false)

        /**
         * Loads the model on the NPU and runs it once as a test. Throws with a readable message
         * if the NPU can't be used. If a previous attempt crashed the app, it refuses to try again
         * until the state is reset in Settings.
         */
        @Synchronized
        fun create(context: Context, m: Model): NpuFlow {
            val previous = state(context)
            if (previous == "trying") {
                setState(context, "failed: the NPU runtime crashed the app last time")
            }
            state(context).let { if (it.startsWith("failed")) throw IllegalStateException(it.removePrefix("failed: ")) }
            if (!hasModel(context, m)) throw IllegalStateException("the AI model isn't included in this build")
            // Preparing the model is heavy work; never start it on a phone that is already hot.
            val heat = runCatching { context.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus ?: 0 }.getOrDefault(0)
            if (heat >= android.os.PowerManager.THERMAL_STATUS_MODERATE) {
                throw IllegalStateException("the phone is too hot to start the AI model - let it cool down first")
            }

            setState(context, "trying")
            try {
                val dir = File(context.filesDir, "npu").apply { mkdirs() }
                val model = File(dir, "${m.name}.onnx")
                // Re-copy the model (and drop the compiled cache) whenever the app was updated.
                val marker = File(dir, "${m.name}.version")
                val version = com.jainz.memc.BuildConfig.VERSION_NAME
                if (!model.exists() || runCatching { marker.readText() }.getOrNull() != version) {
                    context.assets.open(m.asset).use { input -> model.outputStream().use { input.copyTo(it) } }
                    dir.listFiles()?.filter { it.name.startsWith("${m.name}_ctx") }?.forEach { it.delete() }
                    marker.writeText(version)
                }
                val libs = context.applicationInfo.nativeLibraryDir
                // The NPU's own half of the runtime is loaded by the chip from this folder.
                runCatching { Os.setenv("ADSP_LIBRARY_PATH", "$libs;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp", true) }

                val env = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions()
                options.addQnn(
                    mapOf(
                        "backend_path" to "$libs/libQnnHtp.so",
                        // The fastest mode meant for long runs. "burst" (short peaks) drew enough to shut
                        // a hot phone down; "balanced" was too slow for real time (32 ms a pass).
                        "htp_performance_mode" to "sustained_high_performance",
                        "htp_graph_finalization_optimization_mode" to "3",
                        "enable_htp_fp16_precision" to "1",
                    )
                )
                // Keep the compiled model so later starts are quick.
                val cached = File(dir, "${m.name}_ctx.onnx")
                val session = if (cached.exists()) {
                    runCatching { env.createSession(cached.absolutePath, options) }.getOrElse {
                        cached.delete()
                        env.createSession(model.absolutePath, options)
                    }
                } else {
                    options.addConfigEntry("ep.context_enable", "1")
                    options.addConfigEntry("ep.context_file_path", cached.absolutePath)
                    options.addConfigEntry("ep.context_embed_mode", "0")
                    env.createSession(model.absolutePath, options)
                }
                val npu = NpuFlow(env, session, m.width, m.height)
                // Test run, so a broken setup fails here and not in the middle of a video.
                val blank = floats(3 * m.width * m.height)
                npu.run(blank, floats(3 * m.width * m.height), 0.5f)
                npu.averageMs = 0f
                npu.runs = 0
                setState(context, "ok")
                return npu
            } catch (e: Throwable) {
                setState(context, "failed: ${e.message ?: e.javaClass.simpleName}".take(300))
                throw e
            }
        }
    }
}
