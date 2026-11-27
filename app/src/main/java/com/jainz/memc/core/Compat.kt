package com.jainz.memc.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Small wrappers for system features that older Android versions don't have. The phone app
 * needs Android 10, but the TV app runs from Android 7, where these return a neutral answer.
 */
object Compat {

    /** PowerManager heat rating (0 = cool); always 0 before Android 10, which has no rating. */
    fun thermalStatus(context: Context): Int =
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0 }.getOrDefault(0)
        } else {
            0
        }

    /** Calls [onChange] on the main thread when the heat rating changes. Returns a handle for [unwatchHeat]. */
    fun watchHeat(context: Context, onChange: (Int) -> Unit): Any? {
        if (Build.VERSION.SDK_INT < 29) return null
        val pm = context.getSystemService(PowerManager::class.java) ?: return null
        val listener = PowerManager.OnThermalStatusChangedListener { onChange(it) }
        return runCatching {
            pm.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
            listener
        }.getOrNull()
    }

    fun unwatchHeat(context: Context, handle: Any?) {
        if (Build.VERSION.SDK_INT < 29 || handle !is PowerManager.OnThermalStatusChangedListener) return
        runCatching { context.getSystemService(PowerManager::class.java)?.removeThermalStatusListener(handle) }
    }

    /** Creates a notification channel where Android has them (8.0+) and returns its id. */
    fun channel(context: Context, id: String, name: String): String {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(id) == null) {
                nm?.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW))
            }
        }
        return id
    }
}
