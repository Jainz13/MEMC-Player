package com.jainz.memc.stereo

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.system.Os
import java.io.File

/**
 * Prepares (compiles) a model for the NPU in a separate process. The NPU compiler can take a
 * very long time, or never finish, on some graphs; in its own process it can be stopped after a
 * time limit without touching the app, and when it does finish the compiled copy is kept, so the
 * app loads it in a second from then on.
 */
class NpuCompileService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra(EXTRA_MODEL) ?: return START_NOT_STICKY.also { stopSelf() }
        val limitMs = intent.getLongExtra(EXTRA_LIMIT_MS, 30 * 60_000L)
        val ref = ModelRef(name, 0)
        val app = applicationContext
        if (started) return START_NOT_STICKY
        started = true
        NpuCompile.mark(app, ref, "compiling")
        // Watchdog: past the limit, give up and end this process (a stuck compile can't be stopped otherwise).
        Thread({
            try {
                Thread.sleep(limitMs)
                NpuCompile.mark(app, ref, "failed: still compiling after ${limitMs / 60_000} min")
                Process.killProcess(Process.myPid())
            } catch (_: InterruptedException) {
            }
        }, "npu-compile-watchdog").apply { isDaemon = true }.also { watchdog = it }.start()
        Thread({
            val result = runCatching {
                val libs = app.applicationInfo.nativeLibraryDir
                runCatching { Os.setenv("ADSP_LIBRARY_PATH", "$libs;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp", true) }
                val env = OrtEnvironment.getEnvironment()
                val start = System.currentTimeMillis()
                ref.open(app, env) {
                    OrtSession.SessionOptions().apply {
                        addQnn(
                            mapOf(
                                "backend_path" to "$libs/libQnnHtp.so",
                                "htp_performance_mode" to "sustained_high_performance",
                                // Lighter optimisation: compiles far faster; this part is small.
                                "htp_graph_finalization_optimization_mode" to "1",
                                "enable_htp_fp16_precision" to "1",
                            )
                        )
                    }
                }.close()
                System.currentTimeMillis() - start
            }
            watchdog?.interrupt()
            NpuCompile.mark(
                app, ref,
                result.fold({ "ok: compiled in ${it / 1000} s" }, { "failed: ${it.message ?: it.javaClass.simpleName}" }),
            )
            stopSelf()
            // Free the NPU and the memory straight away.
            Process.killProcess(Process.myPid())
        }, "npu-compile").start()
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_MODEL = "model"
        const val EXTRA_LIMIT_MS = "limit"
        @Volatile private var started = false
        @Volatile private var watchdog: Thread? = null
    }
}

/** State of background NPU compiles, kept in small files next to the models. */
object NpuCompile {
    private fun stateFile(context: Context, ref: ModelRef) =
        File(File(context.filesDir, "npu").apply { mkdirs() }, "${ref.name}.compile")

    private val version: String get() = com.jainz.memc.BuildConfig.VERSION_NAME

    internal fun mark(context: Context, ref: ModelRef, state: String) {
        runCatching { stateFile(context, ref).writeText("$version\n$state") }
    }

    /** "" (never tried with this version), "compiling", "ok: …" or "failed: …". */
    fun state(context: Context, ref: ModelRef): String {
        val lines = runCatching { stateFile(context, ref).readLines() }.getOrNull() ?: return ""
        if (lines.size < 2 || lines[0] != version) return ""
        return lines[1]
    }

    fun ready(context: Context, ref: ModelRef) = state(context, ref).startsWith("ok")

    /** Starts the compile in the background unless it is done, running, or failed with this version. */
    fun request(context: Context, ref: ModelRef, limitMs: Long = 30 * 60_000L) {
        if (!ref.ready(context)) return
        val st = state(context, ref)
        if (st.startsWith("ok") || st.startsWith("failed")) return
        if (st == "compiling" && System.currentTimeMillis() - stateFile(context, ref).lastModified() < limitMs + 60_000) return
        runCatching {
            context.startService(
                Intent(context, NpuCompileService::class.java)
                    .putExtra(NpuCompileService.EXTRA_MODEL, ref.name)
                    .putExtra(NpuCompileService.EXTRA_LIMIT_MS, limitMs),
            )
        }
    }
}
