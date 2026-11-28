package com.jainz.memc.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File

/**
 * Temperatures an app can see. Android always reports the battery; the processor sensors are
 * readable on some phones only (Samsung usually blocks them), and Android 11+ adds a
 * "thermal headroom": how close the phone is to throttling (100% = it starts slowing down).
 */
object SystemTemps {
    data class Reading(
        val batteryC: Float?,
        val cpuC: Float?,
        val gpuC: Float?,
        /** 0..100+, percent of the way to throttling; null when Android doesn't say. */
        val headroomPercent: Int?,
        val status: String?,
    ) {
        fun summary(): String = listOfNotNull(
            batteryC?.let { "battery %.1f °C".format(java.util.Locale.US, it) },
            cpuC?.let { "CPU %.0f °C".format(java.util.Locale.US, it) },
            gpuC?.let { "GPU %.0f °C".format(java.util.Locale.US, it) },
            headroomPercent?.let { "throttle point $it%" },
            status,
        ).joinToString(" · ")
    }

    private var zones: List<Pair<String, File>>? = null

    /** Thermal zones whose type names a CPU or GPU sensor and that this app may read. */
    private fun zones(): List<Pair<String, File>> = zones ?: runCatching {
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty().mapNotNull { dir ->
            val type = runCatching { File(dir, "type").readText().trim().lowercase() }.getOrNull() ?: return@mapNotNull null
            val temp = File(dir, "temp")
            val kind = when {
                type.contains("gpu") -> "gpu"
                type.contains("cpu") || type.contains("cpuss") || type.startsWith("apc") -> "cpu"
                else -> return@mapNotNull null
            }
            if (runCatching { temp.readText().trim().toLong() }.isSuccess) kind to temp else null
        }
    }.getOrDefault(emptyList()).also { zones = it }

    private fun hottest(kind: String): Float? = zones().filter { it.first == kind }.mapNotNull { (_, f) ->
        runCatching { f.readText().trim().toLong() }.getOrNull()?.let { if (it > 1000) it / 1000f else it.toFloat() }
    }.filter { it in 5f..150f }.maxOrNull()

    fun read(context: Context): Reading {
        val battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
        }.getOrNull()
        val headroom = if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                context.getSystemService(PowerManager::class.java)?.getThermalHeadroom(10)
                    ?.takeIf { !it.isNaN() && it >= 0f }?.let { (it * 100).toInt() }
            }.getOrNull()
        } else null
        val status = when (Compat.thermalStatus(context)) {
            0 -> "cool"
            1 -> "warm"
            2 -> "hot (AI paused)"
            3 -> "very hot"
            else -> "critical"
        }
        return Reading(battery, hottest("cpu"), hottest("gpu"), headroom, status)
    }
}
