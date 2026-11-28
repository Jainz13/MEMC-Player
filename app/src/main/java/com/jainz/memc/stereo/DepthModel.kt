package com.jainz.memc.stereo

import android.content.Context
import com.jainz.memc.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * The AI depth model for 2D-to-3D (Depth Anything V2 Small, Apache-2.0), downloaded once
 * (about 100 MB) instead of making every APK bigger. Built by the "Depth model" workflow.
 */
object DepthModel {
    const val WIDTH = 518
    const val HEIGHT = 294
    private const val NAME = "depth_v2s_518x294.onnx"
    private const val URL = "https://github.com/Jainz13/MEMC-Player/releases/download/depth-model/$NAME"
    private const val MIN_BYTES = 20L * 1024 * 1024

    /** Packed in the phone APK; downloaded only by builds made without it. */
    val ref = ModelRef(NAME, MIN_BYTES)

    fun file(context: Context) = ref.downloaded(context)

    fun ready(context: Context) = ref.ready(context)

    fun sizeMb(context: Context) = file(context).length() / (1024 * 1024)

    fun delete(context: Context) {
        file(context).delete()
        File(context.filesDir, "npu").listFiles()?.filter { it.name.startsWith("depth_ctx") }?.forEach { it.delete() }
    }

    /** Downloads the model, reporting (bytes so far, total or -1). */
    suspend fun download(context: Context, onProgress: (Long, Long) -> Unit) =
        downloadTo(URL, file(context), MIN_BYTES, onProgress)

    /** Downloads [url] to [target] through a .part file, reporting (bytes so far, total or -1). */
    suspend fun downloadTo(url: String, target: File, minBytes: Long, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val part = File(target.parentFile, "${target.name}.part")
        val req = Request.Builder().url(url).build()
        Http.client.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
            .newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Download failed (HTTP ${resp.code}) - the model may not be published yet")
                val body = resp.body ?: throw IOException("Empty download")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport > 512 * 1024) {
                                lastReport = done
                                onProgress(done, total)
                            }
                        }
                        onProgress(done, total)
                    }
                }
            }
        if (part.length() < minBytes) {
            part.delete()
            throw IOException("The download was incomplete")
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("Couldn't save the model")
    }
}
