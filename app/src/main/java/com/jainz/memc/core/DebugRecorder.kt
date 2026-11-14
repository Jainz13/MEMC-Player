package com.jainz.memc.core

import android.app.Activity
import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.core.content.FileProvider
import com.jainz.memc.BuildConfig
import com.jainz.memc.MemcApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Debug recording: while switched on, the app keeps
 *  - its own system log (only this app's lines - Android doesn't let an app read others'),
 *  - a running record of what the player and the 60 fps engine are doing,
 *  - pictures of the video frame and of the app's screens,
 * and packs them into one zip to share when a problem needs looking at.
 * Everything stays in the app's private cache until the user shares the zip.
 */
object DebugRecorder {
    private const val PREFS = "debug_recorder"
    private const val MAX_PICTURES = 60
    private const val MAX_LOG_BYTES = 24L * 1024 * 1024

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    val isActive: Boolean get() = _active.value

    private var appContext: Context? = null
    private var logcat: Process? = null
    private var pictures = 0
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileStamp = SimpleDateFormat("HHmmss", Locale.US)

    /** The player's video surface, set while a video is open. */
    @Volatile var videoSurface: WeakReference<SurfaceView>? = null

    private fun dir(context: Context) = File(context.cacheDir, "debug/session").apply { mkdirs() }
    private fun shots(context: Context) = File(dir(context), "pictures").apply { mkdirs() }

    /** Called when the app starts: carries on a recording that was left switched on. */
    fun resume(context: Context) {
        appContext = context.applicationContext
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("active", false)) {
            _active.value = true
            pictures = shots(context).list()?.size ?: 0
            event("app", "App started (recording was already on)")
            startLogcat(context)
        }
    }

    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        discard(app)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("active", true).apply()
        _active.value = true
        event("app", "Recording started. ${BuildConfig.VERSION_NAME}, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        startLogcat(app)
    }

    fun stop(context: Context) {
        if (_active.value) event("app", "Recording stopped")
        _active.value = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("active", false).apply()
        runCatching { logcat?.destroy() }
        logcat = null
    }

    /** Stops and deletes everything recorded so far. */
    fun discard(context: Context) {
        stop(context)
        File(context.cacheDir, "debug").deleteRecursively()
        pictures = 0
    }

    /** Pictures taken and megabytes recorded, for the settings screen. */
    fun summary(context: Context): String {
        val d = File(context.cacheDir, "debug/session")
        if (!d.exists()) return "Nothing recorded"
        val bytes = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val shots = File(d, "pictures").list()?.size ?: 0
        return "$shots pictures, ${bytes / (1024 * 1024)} MB recorded"
    }

    // ---- What gets recorded --------------------------------------------------------------------

    /** One line in the running record ("events.txt"). Cheap no-op while recording is off. */
    fun event(tag: String, message: String) {
        if (!_active.value) return
        val context = appContext ?: return
        runCatching {
            synchronized(this) {
                File(dir(context), "events.txt").appendText("${stamp.format(Date())}  [$tag]  $message\n")
            }
        }
    }

    private fun startLogcat(context: Context) {
        runCatching { logcat?.destroy() }
        val out = File(dir(context), "logcat-${fileStamp.format(Date())}.txt")
        Thread({
            runCatching {
                // Without special permission this returns only this app's own log lines.
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "threadtime", "-T", "200"))
                logcat = process
                var written = 0L
                process.inputStream.bufferedReader().use { reader ->
                    out.bufferedWriter().use { writer ->
                        while (_active.value) {
                            val line = reader.readLine() ?: break
                            written += line.length + 1
                            if (written > MAX_LOG_BYTES) {
                                writer.write("--- log size limit reached, recording of the log stopped ---\n")
                                break
                            }
                            writer.write(line)
                            writer.write("\n")
                            if (line.contains(" E ") || line.contains(" F ")) writer.flush()
                        }
                    }
                }
                process.destroy()
            }
        }, "debug-logcat").apply { isDaemon = true }.start()
    }

    /** Saves the current video frame (the picture exactly as the app produced it). */
    fun captureVideo(label: String, onDone: (Boolean) -> Unit = {}) {
        val context = appContext
        val view = videoSurface?.get()
        if (!_active.value || context == null || view == null || view.width <= 0 || !view.holder.surface.isValid) {
            onDone(false)
            return
        }
        if (pictures >= MAX_PICTURES) {
            onDone(false)
            return
        }
        runCatching {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(view, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    save(context, bitmap, "video-$label", png = true)
                    onDone(true)
                } else {
                    event("capture", "Video frame capture failed (code $result)")
                    onDone(false)
                }
            }, Handler(Looper.getMainLooper()))
        }.onFailure { onDone(false) }
    }

    /** Saves a picture of an app screen (buttons and lists; a playing video shows as a black hole). */
    fun captureScreen(activity: Activity, label: String) {
        val context = appContext ?: return
        if (!_active.value || pictures >= MAX_PICTURES) return
        runCatching {
            val view = activity.window.decorView
            if (view.width <= 0) return
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(activity.window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) save(context, bitmap, "screen-$label", png = false)
            }, Handler(Looper.getMainLooper()))
        }
    }

    private fun save(context: Context, bitmap: Bitmap, name: String, png: Boolean) {
        val n = ++pictures
        Thread {
            runCatching {
                val clean = name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60)
                val file = File(shots(context), "%03d-%s-%s.%s".format(n, fileStamp.format(Date()), clean, if (png) "png" else "jpg"))
                FileOutputStream(file).use {
                    // Video frames are kept lossless so colour banding and smearing stay visible.
                    if (png) bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    else bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)
                }
                event("capture", "Saved ${file.name}")
            }
            bitmap.recycle()
        }.start()
    }

    // ---- Zip and share ---------------------------------------------------------------------------

    /** Packs the recording into a zip in the app's cache. Call from a background thread. */
    fun buildZip(context: Context): File {
        val session = dir(context)
        File(session, "info.txt").writeText(info(context))
        CrashLog.read(context)?.let { File(session, "last-crash.txt").writeText(it) }
        val zip = File(context.cacheDir, "debug/memc-debug-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.zip")
        zip.parentFile?.listFiles { f -> f.name.endsWith(".zip") }?.forEach { it.delete() }
        ZipOutputStream(FileOutputStream(zip).buffered()).use { out ->
            session.walkTopDown().filter { it.isFile }.forEach { f ->
                out.putNextEntry(ZipEntry(f.relativeTo(session).path.replace('\\', '/')))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return zip
    }

    private fun info(context: Context): String = buildString {
        appendLine("MEMC Player ${BuildConfig.VERSION_NAME} (${if (BuildConfig.TV) "TV" else "phone"} variant)")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Chip: ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE}")
        appendLine("Created: ${Date()}")
        appendLine()
        appendLine("Settings (keys and passwords are not included):")
        runCatching {
            val s = MemcApp.graph.settings
            appendLine("  60 fps: enabled=${s.memcEnabled.value} target=${s.memcTargetFps.value} quality=${s.memcQuality.value}")
            appendLine("  picture: hdrBoost=${s.hdrBoost.value} autoHdr=${s.autoHdr.value}")
            appendLine("  playback: matchFrameRate=${s.matchFrameRate.value} ffmpegAudio=${s.ffmpegAudio.value} defaultZoom=${s.defaultZoom.value}")
            appendLine("  npuExport=${s.npuExport.value} showStats=${s.showStats.value}")
        }
        appendLine()
        appendLine("How the app's processes ended recently:")
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                context.getSystemService(ActivityManager::class.java)
                    ?.getHistoricalProcessExitReasons(context.packageName, 0, 6)
                    ?.forEach { appendLine(CrashLog.describe(it).prependIndent("  ")); appendLine() }
            }
        }
    }

    /** Copies the zip to the phone's Download folder; returns its name, or null if that failed. */
    fun saveToDownloads(context: Context, zip: File): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, zip.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out -> zip.inputStream().use { it.copyTo(out) } }
            zip.name
        } else null
    }.getOrNull()

    /** Opens Android's share sheet for the zip. */
    fun share(context: Context, zip: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "MEMC Player debug recording")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share debug recording").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
