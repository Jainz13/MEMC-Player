package com.jainz.memc.stereo

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File

/**
 * An AI model for the NPU that is either packed in the APK (assets/models, phone builds) or
 * downloaded into the app's files. Packed models are used straight away; the download is
 * only a fallback for builds made without them.
 */
class ModelRef(
    /** Name in assets/models and in the download folder, e.g. "vda_s_enc.onnx". */
    val name: String,
    private val minBytes: Long,
) {
    private val asset = "models/$name"
    private val tag = name.removeSuffix(".onnx")

    /** Packed in this APK. Needs the model stored uncompressed (see build.gradle). */
    fun bundled(context: Context): Boolean = bundledSize(context) > 0

    private fun bundledSize(context: Context): Long =
        runCatching { context.assets.openFd(asset).use { it.length } }.getOrDefault(-1L)

    fun downloaded(context: Context): File = File(dir(context), name)

    fun ready(context: Context): Boolean =
        bundled(context) || downloaded(context).let { it.exists() && it.length() >= minBytes }

    /**
     * Opens the model on the NPU. The compiled model is kept (keyed by the model's size, so
     * app updates with the same model reuse it), and after the first time only that is used.
     */
    fun open(context: Context, env: OrtEnvironment, options: () -> OrtSession.SessionOptions): OrtSession {
        val d = dir(context)
        val packed = bundledSize(context)
        // A copy downloaded by an older version isn't needed once the APK carries the model.
        if (packed > 0) downloaded(context).delete()
        val size = if (packed > 0) packed else downloaded(context).length()
        val cached = File(d, "${tag}_ctx_$size.onnx")
        // Compiled copies of other versions of this model.
        d.listFiles()?.filter { it.name.startsWith("${tag}_ctx_") && !it.name.startsWith("${tag}_ctx_${size}") }?.forEach { it.delete() }
        if (cached.exists()) {
            runCatching { return env.createSession(cached.absolutePath, options()) }
            d.listFiles()?.filter { it.name.startsWith("${tag}_ctx_") }?.forEach { it.delete() }
        }
        val source: File = if (packed > 0) {
            // ONNX Runtime needs a file: copy it out of the APK for the one-time compile.
            File(d, "$tag.unpacked.onnx").also { f ->
                if (f.length() != packed) context.assets.open(asset).use { input -> f.outputStream().use { input.copyTo(it, 1 shl 20) } }
            }
        } else {
            downloaded(context)
        }
        val o = options()
        o.addConfigEntry("ep.context_enable", "1")
        o.addConfigEntry("ep.context_file_path", cached.absolutePath)
        o.addConfigEntry("ep.context_embed_mode", "0")
        val session = env.createSession(source.absolutePath, o)
        // The compiled copy is all that's needed from now on.
        if (packed > 0 && cached.exists()) source.delete()
        return session
    }

    /** A compiled (NPU) copy of this model is on the phone. */
    fun hasCompiledCopy(context: Context): Boolean {
        val packed = bundledSize(context)
        val size = if (packed > 0) packed else downloaded(context).length()
        return File(dir(context), "${tag}_ctx_$size.onnx").exists()
    }

    /** The model's bytes (from the APK, or the downloaded copy), for running it on the CPU. */
    fun bytes(context: Context): ByteArray =
        if (bundled(context)) context.assets.open(asset).use { it.readBytes() } else downloaded(context).readBytes()

    private companion object {
        fun dir(context: Context) = File(context.filesDir, "npu").apply { mkdirs() }
    }
}
