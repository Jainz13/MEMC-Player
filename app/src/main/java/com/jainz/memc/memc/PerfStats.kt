package com.jainz.memc.memc

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Live load figures for the stats overlay.
 *
 * Android gives an app no system-wide CPU/GPU/NPU meters, so these are measured from the
 * inside: time the GPU spent on this app's video passes, time the NPU spent running the AI
 * model, and the CPU time of this app's process. Where the phone does expose a system-wide
 * GPU figure, that is used instead.
 */
object PerfStats {
    /** Nanoseconds of GPU time used by the video engine (GL timer queries). */
    val gpuNs = AtomicLong()
    /** Nanoseconds the NPU spent on the AI model. */
    val npuNs = AtomicLong()
    /** Number of AI model passes. */
    val npuRuns = AtomicLong()
    /** Frames the engine has sent to the screen. */
    val framesOut = AtomicLong()
    /** Late frames the player had to drop. */
    val dropped = AtomicLong()
    /** False once it is known the GPU can't report its time. */
    @Volatile var gpuTimerWorks = true

    class Sample(
        val cpuPercent: Int,
        /** -1 = not measurable right now. */
        val gpuPercent: Int,
        val gpuSystemWide: Boolean,
        val npuPercent: Int,
        /** Milliseconds per AI pass, 0 when the model didn't run. */
        val npuMsPerPass: Int,
        val temperatureC: Float?,
        val thermal: String?,
        val fps: Int,
        val droppedTotal: Long,
    )

    private var lastWall = 0L
    private var lastCpu = 0L
    private var lastGpu = 0L
    private var lastNpu = 0L
    private var lastFrames = 0L
    private var lastRuns = 0L
    private var droppedBase = 0L
    private var sysGpu: File? = null
    private var sysGpuChecked = false

    /** Start of a viewing session: forget what came before. */
    fun reset() {
        lastWall = 0L
        droppedBase = dropped.get()
    }

    /** Call about once a second; returns the load since the previous call (null on the first). */
    fun sample(context: Context): Sample? {
        val wall = SystemClock.elapsedRealtimeNanos()
        val cpu = Process.getElapsedCpuTime() * 1_000_000L
        val gpu = gpuNs.get()
        val npu = npuNs.get()
        val frames = framesOut.get()
        val runs = npuRuns.get()
        val dt = wall - lastWall
        val first = lastWall == 0L || dt <= 0
        val result = if (first) null else {
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val system = systemGpu()
            Sample(
                cpuPercent = ((cpu - lastCpu) * 100 / dt / cores).toInt().coerceIn(0, 100),
                gpuPercent = system ?: if (gpuTimerWorks) ((gpu - lastGpu) * 100 / dt).toInt().coerceIn(0, 100) else -1,
                gpuSystemWide = system != null,
                npuPercent = ((npu - lastNpu) * 100 / dt).toInt().coerceIn(0, 100),
                npuMsPerPass = (runs - lastRuns).let { n -> if (n > 0) ((npu - lastNpu) / n / 1_000_000).toInt() else 0 },
                temperatureC = batteryTemperature(context),
                thermal = thermal(context),
                fps = ((frames - lastFrames) * 1_000_000_000.0 / dt + 0.5).toInt(),
                droppedTotal = dropped.get() - droppedBase,
            )
        }
        lastRuns = runs
        lastWall = wall; lastCpu = cpu; lastGpu = gpu; lastNpu = npu; lastFrames = frames
        return result
    }

    /** Whole-phone GPU load, on phones that let apps read it (most don't). */
    private fun systemGpu(): Int? {
        if (!sysGpuChecked) {
            sysGpuChecked = true
            sysGpu = listOf(
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                "/sys/kernel/gpu/gpu_busy",
            ).map { File(it) }.firstOrNull { f -> runCatching { f.readText().isNotBlank() }.getOrDefault(false) }
        }
        val f = sysGpu ?: return null
        return runCatching { Regex("\\d+").find(f.readText())?.value?.toInt()?.coerceIn(0, 100) }.getOrNull()
    }

    private fun batteryTemperature(context: Context): Float? = runCatching {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
    }.getOrNull()

    private fun thermal(context: Context): String? = runCatching {
        when (context.getSystemService(PowerManager::class.java)?.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "cool"
            PowerManager.THERMAL_STATUS_LIGHT -> "warm"
            PowerManager.THERMAL_STATUS_MODERATE -> "hot"
            PowerManager.THERMAL_STATUS_SEVERE -> "very hot"
            PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "critical"
            else -> null
        }
    }.getOrNull()
}
