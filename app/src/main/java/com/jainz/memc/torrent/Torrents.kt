package com.jainz.memc.torrent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.jainz.memc.MemcApp
import com.jainz.memc.data.scan.ScanWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

enum class TorrentPhase { DOWNLOADING, PAUSED, MOVING, DONE, FAILED }

data class TorrentItem(
    val id: String,
    val name: String,
    val phase: TorrentPhase,
    val status: String,
    val progress: Float,
    val bytesPerSecond: Long = 0,
    val peers: Int = 0,
    val totalBytes: Long = 0,
    /** Largest video file on the phone, playable while downloading and after. */
    val playPath: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("phase", phase.name).put("status", status)
        .put("progress", progress.toDouble()).put("bps", bytesPerSecond).put("peers", peers)
        .put("total", totalBytes).put("play", playPath ?: "")

    companion object {
        fun fromJson(o: JSONObject) = TorrentItem(
            id = o.getString("id"),
            name = o.getString("name"),
            phase = runCatching { TorrentPhase.valueOf(o.getString("phase")) }.getOrDefault(TorrentPhase.FAILED),
            status = o.optString("status"),
            progress = o.optDouble("progress", 0.0).toFloat(),
            bytesPerSecond = o.optLong("bps"),
            peers = o.optInt("peers"),
            totalBytes = o.optLong("total"),
            playPath = o.optString("play").takeIf { it.isNotBlank() },
        )
    }
}

/** Folders shared by the app and the torrent engine (they run as two separate processes). */
object TorrentStore {
    /** One file per torrent: <hash>.magnet or <hash>.torrent (plus <hash>.paused). */
    fun dir(context: Context) = File(context.filesDir, "torrents").apply { mkdirs() }

    /** Where downloads land: Movies/MEMC Downloads with full file access, else the app's own folder. */
    fun saveDir(context: Context): File {
        val full = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
        val dir = if (full) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "MEMC Downloads")
        } else {
            context.getExternalFilesDir("Torrents") ?: File(context.filesDir, "Torrents")
        }
        return dir.apply { mkdirs() }
    }

    fun hasSaved(context: Context): Boolean =
        dir(context).list()?.any { it.endsWith(".magnet") || it.endsWith(".torrent") } == true

    fun magnetHash(uri: String): String? {
        val raw = Regex("xt=urn:btih:([A-Za-z0-9]+)").find(uri)?.groupValues?.get(1) ?: return null
        return when (raw.length) {
            40 -> raw.lowercase().takeIf { h -> h.all { it in "0123456789abcdef" } }
            32 -> base32ToHex(raw)
            else -> null
        }
    }

    fun magnetName(uri: String, id: String): String =
        Regex("[?&]dn=([^&]+)").find(uri)?.groupValues?.get(1)
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?.takeIf { it.isNotBlank() } ?: "Magnet $id".take(24)

    private fun base32ToHex(s: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0L
        var count = 0
        val out = StringBuilder()
        for (c in s.uppercase()) {
            val v = alphabet.indexOf(c)
            if (v < 0) return null
            bits = (bits shl 5) or v.toLong()
            count += 5
            while (count >= 4) {
                out.append(((bits shr (count - 4)) and 0xF).toString(16))
                count -= 4
            }
            bits = bits and ((1L shl count) - 1)
        }
        return out.toString().take(40).takeIf { it.length == 40 }
    }

    class Meta(val hash: String, val name: String)

    /**
     * Reads the identity (info-hash) and name out of a .torrent file without the torrent engine,
     * so a damaged file can't take the app down. Returns null when it isn't a torrent file.
     */
    fun readTorrent(bytes: ByteArray): Meta? = runCatching {
        var pos = 0
        fun readString(): ByteArray {
            var len = 0
            while (bytes[pos] != ':'.code.toByte()) {
                val d = bytes[pos] - '0'.code.toByte()
                require(d in 0..9)
                len = len * 10 + d
                require(len <= bytes.size)
                pos++
            }
            pos++
            require(pos + len <= bytes.size)
            return bytes.copyOfRange(pos, pos + len).also { pos += len }
        }
        fun skip(depth: Int) {
            require(depth < 200)
            when (bytes[pos].toInt().toChar()) {
                'i' -> { while (bytes[pos] != 'e'.code.toByte()) pos++; pos++ }
                'l' -> { pos++; while (bytes[pos] != 'e'.code.toByte()) skip(depth + 1); pos++ }
                'd' -> { pos++; while (bytes[pos] != 'e'.code.toByte()) { readString(); skip(depth + 1) }; pos++ }
                else -> readString()
            }
        }
        require(bytes[pos] == 'd'.code.toByte())
        pos++
        var hash: String? = null
        var name: String? = null
        while (bytes[pos] != 'e'.code.toByte()) {
            val key = String(readString(), Charsets.UTF_8)
            if (key == "info") {
                val start = pos
                require(bytes[pos] == 'd'.code.toByte())
                pos++
                while (bytes[pos] != 'e'.code.toByte()) {
                    val k = String(readString(), Charsets.UTF_8)
                    if (k == "name" && bytes[pos] in '0'.code.toByte()..'9'.code.toByte()) {
                        name = String(readString(), Charsets.UTF_8)
                    } else skip(1)
                }
                pos++
                hash = MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(start, pos))
                    .joinToString("") { "%02x".format(it) }
            } else skip(0)
        }
        Meta(hash!!, name?.takeIf { it.isNotBlank() } ?: "Torrent")
    }.getOrNull()
}

/**
 * The app's side of the torrent client. The engine itself (libtorrent) runs in its own
 * process, so a crash inside it only stops downloads and never closes the player or library.
 * This object sends it commands and receives its progress.
 */
object Torrents {
    const val ACTION_STATE = "com.jainz.memc.torrent.STATE"
    const val ACTION_PAUSE = "pause"
    const val ACTION_RESUME = "resume"
    const val ACTION_REMOVE = "remove"
    const val ACTION_CLEAR = "clear"
    const val ACTION_SETTINGS = "settings"
    const val EXTRA_ID = "id"
    const val EXTRA_WIFI_ONLY = "wifiOnly"
    const val EXTRA_MOVE_SOURCE = "moveSource"
    const val EXTRA_MOVE_FOLDER = "moveFolder"

    private val _items = MutableStateFlow<List<TorrentItem>>(emptyList())
    val items: StateFlow<List<TorrentItem>> = _items.asStateFlow()

    /** Just added here, not yet reported by the engine: id → time added. */
    private val provisional = HashMap<String, Long>()
    private var lastUpdate = 0L
    /** Last time anything was heard from, or asked of, the engine. */
    @Volatile private var lastSignal = 0L
    private var started = false

    /** Called once when the app (not the engine process) starts. */
    fun init(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    runCatching { onState(app, intent) }
                }
            },
            IntentFilter(ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val g = MemcApp.graph
        // Watchdog: the engine reports every second while it has work. Silence means it died.
        g.scope.launch {
            while (true) {
                delay(3000)
                val busy = _items.value.any { it.phase == TorrentPhase.DOWNLOADING || it.phase == TorrentPhase.MOVING }
                if (busy && SystemClock.elapsedRealtime() - lastSignal > 15_000) {
                    synchronized(this@Torrents) {
                        _items.value = _items.value.map {
                            if (it.phase == TorrentPhase.DOWNLOADING || it.phase == TorrentPhase.MOVING) {
                                it.copy(
                                    phase = TorrentPhase.FAILED, bytesPerSecond = 0,
                                    status = "The torrent engine stopped unexpectedly. Tap Resume to carry on.",
                                )
                            } else it
                        }
                        lastUpdate = 0L
                    }
                }
            }
        }
        // Setting changes reach a running engine straight away.
        g.scope.launch {
            combine(g.settings.torrentWifiOnly.flow, g.settings.torrentMoveSource.flow, g.settings.torrentMoveFolder.flow) { _, _, _ -> Unit }
                .drop(1)
                .collect { if (engineAlive()) send(app, ACTION_SETTINGS, null) }
        }
        if (TorrentStore.hasSaved(app)) send(app, null, null)
    }

    private fun engineAlive() = lastUpdate != 0L && SystemClock.elapsedRealtime() - lastUpdate < 5_000

    private fun onState(app: Context, intent: Intent) {
        val incoming = JSONArray(intent.getStringExtra("items") ?: "[]").let { a ->
            List(a.length()) { TorrentItem.fromJson(a.getJSONObject(it)) }
        }
        synchronized(this) {
            lastUpdate = SystemClock.elapsedRealtime()
            lastSignal = lastUpdate
            val ids = incoming.map { it.id }.toSet()
            val now = SystemClock.elapsedRealtime()
            provisional.keys.removeAll { it in ids }
            // Keep finished rows the engine has since forgotten, and rows added a moment ago.
            val kept = _items.value.filter { old ->
                old.id !in ids && (
                    old.phase == TorrentPhase.DONE ||
                        (old.phase == TorrentPhase.FAILED && !hasFile(app, old.id)) ||
                        (provisional[old.id]?.let { now - it < 15_000 } == true)
                    )
            }
            _items.value = (incoming + kept).sortedBy { it.name.lowercase() }
        }
        if (intent.getBooleanExtra("finished", false)) ScanWorker.enqueue(app)
    }

    private fun hasFile(context: Context, id: String): Boolean {
        val dir = TorrentStore.dir(context)
        return File(dir, "$id.magnet").exists() || File(dir, "$id.torrent").exists()
    }

    private fun send(context: Context, action: String?, id: String?) {
        val s = MemcApp.graph.settings
        val intent = Intent(context, TorrentService::class.java)
            .setAction(action)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_WIFI_ONLY, s.torrentWifiOnly.value)
            .putExtra(EXTRA_MOVE_SOURCE, s.torrentMoveSource.value)
            .putExtra(EXTRA_MOVE_FOLDER, s.torrentMoveFolder.value)
        lastSignal = SystemClock.elapsedRealtime()
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    private fun addRow(context: Context, id: String, name: String) {
        synchronized(this) {
            provisional[id] = SystemClock.elapsedRealtime()
            _items.value = (_items.value.filter { it.id != id } +
                TorrentItem(id, name, TorrentPhase.DOWNLOADING, "Starting…", 0f)).sortedBy { it.name.lowercase() }
        }
        send(context.applicationContext, null, null)
    }

    private fun active(id: String) = _items.value.any {
        it.id == id && (it.phase == TorrentPhase.DOWNLOADING || it.phase == TorrentPhase.PAUSED || it.phase == TorrentPhase.MOVING)
    }

    /** Returns null when added, otherwise a message explaining what is wrong with the link. */
    fun addMagnet(context: Context, magnet: String): String? {
        val uri = magnet.trim()
        val id = TorrentStore.magnetHash(uri) ?: return "That isn't a valid magnet link"
        if (hasFile(context, id) || active(id)) return "Already in the list"
        return runCatching {
            File(TorrentStore.dir(context), "$id.magnet").writeText(uri)
            addRow(context, id, TorrentStore.magnetName(uri, id))
            null
        }.getOrElse { "Could not save the torrent: ${it.message}" }
    }

    fun addTorrentFile(context: Context, bytes: ByteArray): String? {
        val meta = TorrentStore.readTorrent(bytes) ?: return "That isn't a valid .torrent file"
        if (hasFile(context, meta.hash) || active(meta.hash)) return "Already in the list"
        return runCatching {
            File(TorrentStore.dir(context), "${meta.hash}.torrent").writeBytes(bytes)
            addRow(context, meta.hash, meta.name)
            null
        }.getOrElse { "Could not save the torrent: ${it.message}" }
    }

    fun pause(context: Context, id: String) = send(context.applicationContext, ACTION_PAUSE, id)

    fun resume(context: Context, id: String) {
        synchronized(this) {
            if (hasFile(context, id)) {
                provisional[id] = SystemClock.elapsedRealtime()
                _items.value = _items.value.map {
                    if (it.id == id) it.copy(phase = TorrentPhase.DOWNLOADING, status = "Starting…") else it
                }
            }
        }
        send(context.applicationContext, ACTION_RESUME, id)
    }

    /** Removes a torrent; unfinished downloads also delete their partial files. */
    fun remove(context: Context, id: String) {
        val item = _items.value.firstOrNull { it.id == id }
        synchronized(this) {
            provisional.remove(id)
            _items.value = _items.value.filter { it.id != id }
        }
        if (item?.phase == TorrentPhase.DONE || (item?.phase == TorrentPhase.FAILED && !hasFile(context, id))) {
            if (engineAlive()) send(context.applicationContext, ACTION_REMOVE, id)
        } else {
            send(context.applicationContext, ACTION_REMOVE, id)
        }
    }

    fun clearFinished(context: Context) {
        synchronized(this) { _items.value = _items.value.filter { it.phase != TorrentPhase.DONE } }
        if (engineAlive()) send(context.applicationContext, ACTION_CLEAR, null)
    }
}
