package com.jainz.memc.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.jainz.memc.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash so it can be read inside the app (Settings → Last crash report)
 * without a computer or log viewer.
 *  - Kotlin/Java crashes: caught by an uncaught-exception handler and written straight away.
 *  - Native crashes (GPU driver, decoder) and freezes: Android records them as the process's
 *    exit reason, which is read on the next start.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val PREFS = "crash_log"
    private const val KEY_LAST_EXIT = "last_exit_ts"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                write(app, "Crash in thread \"${thread.name}\"\n\n${error.stackTraceToString()}")
            }
            previous?.uncaughtException(thread, error)
        }
        runCatching { collectExitReasons(app) }
    }

    fun read(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    private fun write(context: Context, body: String) {
        val header = buildString {
            appendLine("MEMC Player ${BuildConfig.VERSION_NAME}")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine()
        }
        File(context.filesDir, FILE).writeText(header + body)
    }

    /**
     * Android stores a native crash as a binary record. Without decoding it fully, the readable
     * text inside it (signal, abort message, library and function names of the crashing thread,
     * in order) is enough to see which component crashed.
     */
    private fun nativeSummary(bytes: ByteArray): String {
        val found = LinkedHashSet<String>()
        val current = StringBuilder()
        fun flush() {
            val text = current.toString()
            current.setLength(0)
            val interesting = text.length >= 5 && (
                ".so" in text || text.startsWith("SIG") || text.startsWith("SEGV") || text.startsWith("BUS") ||
                    "abort" in text.lowercase() || text.startsWith("_Z") || "::" in text || "libtorrent" in text ||
                    "Qnn" in text || "onnx" in text || "ffmpeg" in text.lowercase()
                )
            if (interesting && found.size < 60) found += text.take(160)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 32..126) current.append(c.toChar()) else flush()
        }
        flush()
        return "Readable parts of the crash record (first entries are closest to the crash):\n" + found.joinToString("\n")
    }

    private fun collectExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_EXIT, 0L)
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        val newest = exits.maxOfOrNull { it.timestamp } ?: return
        prefs.edit().putLong(KEY_LAST_EXIT, newest).apply()
        if (lastSeen == 0L) return // first run with this feature: don't report old history

        val bad = exits.firstOrNull {
            it.timestamp > lastSeen && (
                it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    it.reason == ApplicationExitInfo.REASON_ANR ||
                    it.reason == ApplicationExitInfo.REASON_CRASH ||
                    it.reason == ApplicationExitInfo.REASON_LOW_MEMORY
                )
        } ?: return
        // A Java crash already wrote its own detailed report.
        if (bad.reason == ApplicationExitInfo.REASON_CRASH && read(context) != null) return

        val reason = when (bad.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash (GPU driver, video decoder or other system library)"
            ApplicationExitInfo.REASON_ANR -> "App froze and was closed by Android (not responding)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Closed by Android because the phone ran out of memory"
            else -> "Crash"
        }
        val trace = when {
            Build.VERSION.SDK_INT < 31 -> null
            bad.reason == ApplicationExitInfo.REASON_ANR ->
                runCatching { bad.traceInputStream?.bufferedReader()?.use { it.readText().take(20_000) } }.getOrNull()
            bad.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ->
                runCatching { bad.traceInputStream?.use { nativeSummary(it.readBytes()) } }.getOrNull()
            else -> null
        }
        write(
            context,
            buildString {
                appendLine(reason)
                appendLine("When: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(bad.timestamp))}")
                appendLine("Details: ${bad.description ?: "none"}")
                appendLine("Process: ${bad.processName}, status ${bad.status}, importance ${bad.importance}")
                if (trace != null) {
                    appendLine()
                    append(trace)
                }
            },
        )
    }
}
