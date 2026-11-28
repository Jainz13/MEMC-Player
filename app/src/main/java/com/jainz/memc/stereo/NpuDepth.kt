package com.jainz.memc.stereo

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.system.Os
import com.jainz.memc.memc.NpuFlow
import java.io.File
import java.nio.FloatBuffer

/**
 * Depth Anything V2 Small on the Snapdragon NPU (ONNX Runtime, QNN). Takes one 518x294 RGB
 * picture (planar, ImageNet-normalised) and returns relative depth (larger = nearer).
 * Used from one worker thread at a time.
 */
class NpuDepth private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : AutoCloseable {
    val width = DepthModel.WIDTH
    val height = DepthModel.HEIGHT
    private val plane = width * height

    /** Fill this (3 planes: R, G, B, already normalised) before calling [run]. */
    val input: FloatBuffer = NpuFlow.floats(3 * plane)
    private val inputTensor = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, height.toLong(), width.toLong()))
    private val inputName = session.inputNames.first()
    private val outputName = session.outputNames.first()
    private val output: FloatBuffer = NpuFlow.floats(plane)
    private val outputTensor: OnnxTensor = run {
        val shape = (session.outputInfo[outputName]?.info as? TensorInfo)?.shape
            ?.takeIf { s -> s.all { it > 0 } && s.fold(1L) { a, b -> a * b } == plane.toLong() }
            ?: longArrayOf(1, height.toLong(), width.toLong())
        OnnxTensor.createTensor(env, output, shape)
    }

    @Volatile var averageMs = 0f
        private set
    private var runs = 0

    /** Runs the model on [input]; the result (row by row, top row first) is valid until the next call. */
    fun run(): FloatBuffer {
        val start = System.nanoTime()
        input.rewind()
        session.run(mapOf(inputName to inputTensor), mapOf(outputName to outputTensor)).close()
        val ms = (System.nanoTime() - start) / 1_000_000f
        runs++
        averageMs += (ms - averageMs) / minOf(runs, 20)
        output.rewind()
        return output
    }

    override fun close() {
        runCatching { session.close() }
        runCatching { inputTensor.close() }
        runCatching { outputTensor.close() }
    }

    companion object {
        /** Prepares the model for this chip (the first time can take a minute). */
        fun create(context: Context): NpuDepth {
            if (!NpuFlow.isSnapdragon) throw IllegalStateException("AI 3D needs a Snapdragon chip's NPU")
            if (!DepthModel.ready(context)) throw IllegalStateException("the AI 3D model isn't downloaded")
            val model = DepthModel.file(context)
            val libs = context.applicationInfo.nativeLibraryDir
            runCatching { Os.setenv("ADSP_LIBRARY_PATH", "$libs;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp", true) }
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            options.addQnn(
                mapOf(
                    "backend_path" to "$libs/libQnnHtp.so",
                    "htp_performance_mode" to "sustained_high_performance",
                    "htp_graph_finalization_optimization_mode" to "3",
                    "enable_htp_fp16_precision" to "1",
                )
            )
            val cached = File(model.parentFile, "depth_ctx_${model.length()}.onnx")
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
            val depth = NpuDepth(env, session)
            depth.run() // test run: a broken setup fails here, not mid-video
            depth.averageMs = 0f
            depth.runs = 0
            return depth
        }
    }
}
