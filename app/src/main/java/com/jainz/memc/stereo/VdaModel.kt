package com.jainz.memc.stereo

import android.content.Context
import java.io.File

/**
 * Video Depth Anything Small (ByteDance, Apache-2.0) for the 3D export, split into three
 * graphs by the "3D export model" workflow (tools/depth/export_vda.py): a per-frame encoder,
 * a 32-frame temporal part and a per-frame decoder. Packed in the phone APK (about 115 MB).
 */
object VdaModel {
    const val WIDTH = 518
    const val HEIGHT = 294
    const val FRAMES = 32
    private const val BASE = "https://github.com/Jainz13/MEMC-Player/releases/download/depth-model/"

    /** File name to the smallest size a complete download can have. */
    val PARTS = linkedMapOf(
        "vda_s_enc.onnx" to 50L * 1024 * 1024,
        "vda_s_tmp.onnx" to 8L * 1024 * 1024,
        "vda_s_dec.onnx" to 400L * 1024,
    )

    /** Packed in the phone APK; downloaded only by builds made without them. */
    val refs = PARTS.map { (name, min) -> ModelRef(name, min) }

    fun file(context: Context, name: String) = ModelRef(name, 0).downloaded(context)

    fun ready(context: Context) = refs.all { it.ready(context) }

    /** Downloads whatever is missing, reporting overall progress 0..1. */
    suspend fun download(context: Context, onProgress: (Float) -> Unit) {
        val missing = PARTS.filter { (name, min) -> !ModelRef(name, min).ready(context) }
        // Weights for the progress bar, roughly the file sizes.
        val weight = mapOf("vda_s_enc.onnx" to 0.82f, "vda_s_tmp.onnx" to 0.17f, "vda_s_dec.onnx" to 0.01f)
        var done = PARTS.keys.filter { it !in missing }.sumOf { (weight[it] ?: 0f).toDouble() }.toFloat()
        for ((name, min) in missing) {
            val w = weight[name] ?: 0f
            DepthModel.downloadTo(BASE + name, file(context, name), min) { got, total ->
                if (total > 0) onProgress((done + w * got / total).coerceIn(0f, 1f))
            }
            done += w
            onProgress(done)
        }
    }
}
