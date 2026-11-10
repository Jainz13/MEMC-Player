package com.jainz.memc.torrent

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.jainz.memc.MemcApp
import com.jainz.memc.data.scan.NameParser
import com.jainz.memc.data.scan.ScanWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.TorrentAlert
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
)

/**
 * A plain BitTorrent client (libtorrent). Torrents are added from magnet links or .torrent
 * files, download to the phone, and can then be moved to a network share automatically.
 */
object TorrentEngine {

    private class Entry(val id: String, var name: String) {
        @Volatile var handle: TorrentHandle? = null
        @Volatile var phase = TorrentPhase.DOWNLOADING
        @Volatile var message: String? = null
        @Volatile var moveProgress = 0f
        @Volatile var playPath: String? = null
        @Volatile var finishing = false
        /** The download request was already sent to the engine in this run. */
        @Volatile var requested = false
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private var session: SessionManager? = null
    private var sessionPaused = false
    private val _items = MutableStateFlow<List<TorrentItem>>(emptyList())
    val items: StateFlow<List<TorrentItem>> = _items.asStateFlow()

    private fun storeDir(context: Context) = File(context.filesDir, "torrents").apply { mkdirs() }

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

    fun hasSaved(context: Context): Boolean = storeDir(context).list()?.isNotEmpty() == true

    fun hasActive(): Boolean = entries.values.any {
        it.phase == TorrentPhase.DOWNLOADING || it.phase == TorrentPhase.MOVING
    }

    // ---- Adding -----------------------------------------------------------------------------------

    /** Returns null when added, otherwise a message explaining what is wrong with the link. */
    fun addMagnet(context: Context, magnet: String): String? {
        val uri = magnet.trim()
        val id = magnetHash(uri) ?: return "That isn't a valid magnet link"
        val name = Regex("[?&]dn=([^&]+)").find(uri)?.groupValues?.get(1)
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: "Magnet $id".take(24)
        if (entries.containsKey(id)) return "Already in the list"
        File(storeDir(context), "$id.magnet").writeText(uri)
        entries[id] = Entry(id, name)
        publish()
        startService(context)
        return null
    }

    fun addTorrentFile(context: Context, bytes: ByteArray): String? {
        val info = runCatching { TorrentInfo(bytes) }.getOrNull() ?: return "That isn't a valid .torrent file"
        val id = info.infoHash().toHex().lowercase()
        if (entries.containsKey(id)) return "Already in the list"
        File(storeDir(context), "$id.torrent").writeBytes(bytes)
        entries[id] = Entry(id, info.name())
        publish()
        startService(context)
        return null
    }

    private fun startService(context: Context) {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, TorrentService::class.java)) }
    }

    private fun magnetHash(uri: String): String? {
        val raw = Regex("xt=urn:btih:([A-Za-z0-9]+)").find(uri)?.groupValues?.get(1) ?: return null
        return when (raw.length) {
            40 -> raw.lowercase()
            32 -> base32ToHex(raw)
            else -> null
        }
    }

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

    // ---- Session ----------------------------------------------------------------------------------

    /** Starts the engine and re-adds everything saved from earlier runs. */
    @Synchronized
    fun ensureStarted(context: Context) {
        if (session != null) return
        val s = SessionManager()
        s.addListener(object : AlertListener {
            override fun types(): IntArray = intArrayOf(AlertType.ADD_TORRENT.swig())
            override fun alert(alert: Alert<*>) {
                val handle = (alert as? TorrentAlert<*>)?.handle() ?: return
                val id = runCatching { handle.infoHash().toHex().lowercase() }.getOrNull() ?: return
                entries[id]?.handle = handle
                runCatching { handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) }
            }
        })
        s.start(SessionParams(SettingsPack().connectionsLimit(200).activeDownloads(4)))
        session = s
        sessionPaused = false
        val dir = saveDir(context)
        storeDir(context).listFiles()?.forEach { f ->
            val id = f.nameWithoutExtension
            runCatching {
                when (f.extension) {
                    "magnet" -> {
                        entries.getOrPut(id) { Entry(id, "Magnet $id".take(24)) }.requested = true
                        s.download(f.readText(), dir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    }
                    "torrent" -> {
                        val info = TorrentInfo(f.readBytes())
                        entries.getOrPut(id) { Entry(id, info.name()) }.requested = true
                        s.download(info, dir)
                    }
                }
            }.onFailure { e -> entries[id]?.let { it.phase = TorrentPhase.FAILED; it.message = e.message } }
        }
        publish()
    }

    /** Adds torrents queued since the engine started (called from the service loop). */
    private fun addNew(context: Context) {
        val s = session ?: return
        val dir = saveDir(context)
        for (e in entries.values) {
            if (e.handle != null || e.requested || e.phase != TorrentPhase.DOWNLOADING || e.finishing) continue
            e.requested = true
            val magnet = File(storeDir(context), "${e.id}.magnet")
            val torrent = File(storeDir(context), "${e.id}.torrent")
            runCatching {
                if (magnet.exists()) s.download(magnet.readText(), dir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                else if (torrent.exists()) s.download(TorrentInfo(torrent.readBytes()), dir)
            }.onFailure { err -> e.phase = TorrentPhase.FAILED; e.message = err.message }
        }
    }

    @Synchronized
    fun stopIfIdle() {
        if (hasActive()) return
        runCatching { session?.stop() }
        session = null
        entries.values.forEach {
            it.handle = null
            it.requested = false
        }
    }

    // ---- Controls ---------------------------------------------------------------------------------

    fun pause(id: String) {
        val e = entries[id] ?: return
        runCatching {
            e.handle?.unsetFlags(TorrentFlags.AUTO_MANAGED)
            e.handle?.pause()
        }
        if (e.phase == TorrentPhase.DOWNLOADING) e.phase = TorrentPhase.PAUSED
        publish()
    }

    fun resume(context: Context, id: String) {
        val e = entries[id] ?: return
        if (e.phase == TorrentPhase.PAUSED || e.phase == TorrentPhase.FAILED) {
            e.phase = TorrentPhase.DOWNLOADING
            e.message = null
            runCatching { e.handle?.resume() }
            publish()
            startService(context)
        }
    }

    /** Removes a torrent; unfinished downloads also delete their partial files. */
    fun remove(context: Context, id: String) {
        val e = entries.remove(id) ?: return
        File(storeDir(context), "$id.magnet").delete()
        File(storeDir(context), "$id.torrent").delete()
        val handle = e.handle
        if (handle != null && e.phase != TorrentPhase.DONE) {
            runCatching { session?.remove(handle, SessionHandle.DELETE_FILES) }
        }
        publish()
    }

    // ---- Progress loop (driven by the service once a second) ------------------------------------------

    fun tick(context: Context) {
        val s = session ?: return
        val settings = MemcApp.graph.settings
        // Wi-Fi only: hold everything while on mobile data.
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val hold = settings.torrentWifiOnly.value && (cm == null || cm.activeNetwork == null || cm.isActiveNetworkMetered)
        if (hold != sessionPaused) {
            runCatching { if (hold) s.pause() else s.resume() }
            sessionPaused = hold
        }
        addNew(context)
        for (e in entries.values) {
            val handle = e.handle ?: continue
            if (e.phase != TorrentPhase.DOWNLOADING || e.finishing) continue
            runCatching {
                if (!handle.isValid) return@runCatching
                val st = handle.status()
                val state = st.state().name
                if (st.name().isNotBlank()) e.name = st.name()
                val downloading = state == "DOWNLOADING" || state == "FINISHED" || state == "SEEDING"
                if (downloading && st.isFinished && st.totalWanted() > 0) {
                    e.finishing = true
                    finish(context, e, handle)
                }
            }
        }
        publish(hold)
    }

    private fun finish(context: Context, e: Entry, handle: TorrentHandle) {
        val g = MemcApp.graph
        val save = handle.savePath()
        val info = handle.torrentFile()
        val storage = info?.files()
        val files = if (storage != null) {
            (0 until storage.numFiles()).map { File(storage.filePath(it, save)) }.filter { it.exists() }
        } else emptyList()
        runCatching {
            handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
            handle.pause()
        }
        e.playPath = files.filter { NameParser.isVideo(it.name) }.maxByOrNull { it.length() }?.absolutePath

        val targetId = g.settings.torrentMoveSource.value
        g.scope.launch {
            val source = if (targetId > 0) g.db.sources().get(targetId) else null
            if (source == null) {
                runCatching { session?.remove(handle) }
                forget(context, e.id)
                e.phase = TorrentPhase.DONE
                e.message = "Saved on this phone"
            } else {
                e.phase = TorrentPhase.MOVING
                publish()
                try {
                    val total = files.sumOf { it.length() }.coerceAtLeast(1)
                    var before = 0L
                    val folder = g.settings.torrentMoveFolder.value.trim('/', ' ')
                    for (f in files) {
                        val relative = f.absolutePath.removePrefix(save).trim('/')
                        val path = listOf(source.path.trim('/'), folder, relative).filter { it.isNotBlank() }.joinToString("/")
                        g.smb.upload(g.smb.location(source, path), f) { done -> e.moveProgress = (before + done).toFloat() / total }
                        before += f.length()
                    }
                    runCatching { session?.remove(handle, SessionHandle.DELETE_FILES) }
                    // Folders the torrent created are left empty by libtorrent's delete; clean up.
                    files.forEach { it.delete() }
                    forget(context, e.id)
                    e.playPath = null
                    e.phase = TorrentPhase.DONE
                    e.message = "Moved to ${source.name}" + if (folder.isNotBlank()) "/$folder" else ""
                } catch (err: Exception) {
                    e.phase = TorrentPhase.FAILED
                    e.message = "Downloaded, but moving to ${source.name} failed: ${err.message}. The files are still on the phone."
                    runCatching { session?.remove(handle) }
                    forget(context, e.id)
                }
            }
            publish()
            ScanWorker.enqueue(context.applicationContext)
        }
    }

    private fun forget(context: Context, id: String) {
        File(storeDir(context), "$id.magnet").delete()
        File(storeDir(context), "$id.torrent").delete()
    }

    fun clearFinished() {
        entries.values.filter { it.phase == TorrentPhase.DONE }.forEach { entries.remove(it.id) }
        publish()
    }

    private fun publish(onHold: Boolean = sessionPaused) {
        _items.value = entries.values.sortedBy { it.name.lowercase() }.map { e ->
            val handle = e.handle
            val st = if (e.phase == TorrentPhase.DOWNLOADING || e.phase == TorrentPhase.PAUSED) {
                runCatching { handle?.takeIf { it.isValid }?.status() }.getOrNull()
            } else null
            val state = st?.state()?.name
            val status = when (e.phase) {
                TorrentPhase.DONE -> e.message ?: "Done"
                TorrentPhase.FAILED -> e.message ?: "Failed"
                TorrentPhase.MOVING -> "Moving to the share…"
                TorrentPhase.PAUSED -> "Paused"
                TorrentPhase.DOWNLOADING -> when {
                    onHold -> "Waiting for Wi-Fi"
                    st == null -> "Starting…"
                    state == "DOWNLOADING_METADATA" -> "Getting torrent details…"
                    state == "CHECKING_FILES" || state == "CHECKING_RESUME_DATA" -> "Checking files…"
                    else -> "Downloading"
                }
            }
            TorrentItem(
                id = e.id,
                name = e.name,
                phase = e.phase,
                status = status,
                progress = when (e.phase) {
                    TorrentPhase.MOVING -> e.moveProgress
                    TorrentPhase.DONE -> 1f
                    else -> st?.progress() ?: 0f
                },
                bytesPerSecond = st?.downloadRate()?.toLong() ?: 0,
                peers = st?.numPeers() ?: 0,
                totalBytes = st?.totalWanted() ?: 0,
                playPath = e.playPath,
            )
        }
    }
}
