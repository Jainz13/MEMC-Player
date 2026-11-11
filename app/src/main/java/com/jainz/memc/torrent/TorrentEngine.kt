package com.jainz.memc.torrent

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import com.jainz.memc.MemcApp
import com.jainz.memc.data.scan.NameParser
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.TorrentAlert
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A plain BitTorrent client (libtorrent). It lives in the ":torrent" process, apart from the
 * player and library: torrents are picked up from files the app writes ([TorrentStore]),
 * commands arrive through [TorrentService], and progress goes back as broadcasts ([Torrents]).
 * Nothing outside this process may touch this object.
 */
object TorrentEngine {

    private class Entry(val id: String, var name: String) {
        @Volatile var handle: TorrentHandle? = null
        @Volatile var phase = TorrentPhase.DOWNLOADING
        @Volatile var message: String? = null
        @Volatile var moveProgress = 0f
        @Volatile var playPath: String? = null
        @Volatile var finishing = false
        /** Last error the engine reported for this torrent while downloading. */
        @Volatile var problem: String? = null
        /** The download request was already sent to the engine in this run. */
        @Volatile var requested = false
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private var session: SessionManager? = null
    private var sessionPaused = false
    @Volatile private var finishedEvent = false

    // Settings, handed over by the app with every command.
    @Volatile private var wifiOnly = true
    @Volatile private var moveSource = 0L
    @Volatile private var moveFolder = ""

    fun applySettings(intent: Intent?) {
        if (intent == null || !intent.hasExtra(Torrents.EXTRA_WIFI_ONLY)) return
        wifiOnly = intent.getBooleanExtra(Torrents.EXTRA_WIFI_ONLY, true)
        moveSource = intent.getLongExtra(Torrents.EXTRA_MOVE_SOURCE, 0L)
        moveFolder = intent.getStringExtra(Torrents.EXTRA_MOVE_FOLDER) ?: ""
    }

    val running: Boolean get() = session != null

    fun hasActive(): Boolean = entries.values.any {
        it.phase == TorrentPhase.DOWNLOADING || it.phase == TorrentPhase.MOVING
    }

    // ---- Session ----------------------------------------------------------------------------------

    @Synchronized
    fun ensureStarted() {
        if (session != null) return
        val s = SessionManager()
        s.addListener(object : AlertListener {
            override fun types(): IntArray = intArrayOf(
                AlertType.ADD_TORRENT.swig(), AlertType.TORRENT_ERROR.swig(), AlertType.FILE_ERROR.swig(),
            )

            override fun alert(alert: Alert<*>) {
                runCatching {
                    val handle = (alert as? TorrentAlert<*>)?.handle() ?: return
                    if (!handle.isValid) return
                    val id = handle.infoHash().toHex().lowercase()
                    val entry = entries[id] ?: return
                    when (alert.type()) {
                        AlertType.ADD_TORRENT -> {
                            val error = (alert as? AddTorrentAlert)?.error()
                            if (error != null && error.isError) {
                                entry.phase = TorrentPhase.FAILED
                                entry.message = "Could not add: ${error.message}"
                            } else {
                                entry.handle = handle
                                handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                                if (entry.phase == TorrentPhase.PAUSED) {
                                    handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
                                    handle.pause()
                                }
                            }
                        }
                        // Disk full, no permission to write, damaged data…
                        else -> entry.problem = alert.message()
                    }
                }
            }
        })
        // Modest limits: a phone on Wi-Fi, usually with a video playing next door.
        val settings = SettingsPack().connectionsLimit(80).activeDownloads(3).activeSeeds(1)
        val params = SessionParams(settings)
        // Plain read/write file access instead of memory-mapped files, which are unreliable on
        // Android's shared storage.
        params.setPosixDiskIO()
        s.start(params)
        session = s
        sessionPaused = false
    }

    /** Picks up torrents the app has written to the store folder. */
    private fun syncStore(context: Context) {
        val dir = TorrentStore.dir(context)
        dir.listFiles()?.forEach { f ->
            val id = f.nameWithoutExtension
            if (entries.containsKey(id)) return@forEach
            val name = when (f.extension) {
                "magnet" -> runCatching { TorrentStore.magnetName(f.readText(), id) }.getOrDefault("Magnet")
                "torrent" -> runCatching { TorrentStore.readTorrent(f.readBytes())?.name }.getOrNull() ?: "Torrent"
                else -> return@forEach
            }
            entries[id] = Entry(id, name).apply {
                if (File(dir, "$id.paused").exists()) phase = TorrentPhase.PAUSED
            }
        }
    }

    /** Hands waiting torrents to the engine. */
    private fun addNew(context: Context) {
        val s = session ?: return
        val dir = TorrentStore.saveDir(context)
        val store = TorrentStore.dir(context)
        for (e in entries.values) {
            if (e.handle != null || e.requested || e.phase != TorrentPhase.DOWNLOADING || e.finishing) continue
            e.requested = true
            val magnet = File(store, "${e.id}.magnet")
            val torrent = File(store, "${e.id}.torrent")
            runCatching {
                when {
                    magnet.exists() -> s.download(magnet.readText(), dir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    torrent.exists() -> s.download(TorrentInfo(torrent.readBytes()), dir)
                    else -> error("The torrent's details are missing; add it again")
                }
            }.onFailure { err ->
                e.phase = TorrentPhase.FAILED
                e.message = "Could not start: ${err.message ?: err.javaClass.simpleName}"
            }
        }
    }

    /** The engine itself could not start: say so on every waiting torrent. */
    fun startFailed(context: Context, error: Throwable) {
        syncStore(context)
        entries.values.filter { it.phase == TorrentPhase.DOWNLOADING }.forEach {
            it.phase = TorrentPhase.FAILED
            it.message = "The torrent engine could not start: ${error.message ?: error.javaClass.simpleName}"
        }
        publish(context)
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

    // ---- Commands from the app --------------------------------------------------------------------

    fun command(context: Context, action: String?, id: String?) {
        val store = TorrentStore.dir(context)
        when (action) {
            Torrents.ACTION_PAUSE -> id?.let { entries[it] }?.let { e ->
                if (e.phase != TorrentPhase.DOWNLOADING) return@let
                runCatching { File(store, "${e.id}.paused").createNewFile() }
                runCatching {
                    e.handle?.takeIf { it.isValid }?.let {
                        it.unsetFlags(TorrentFlags.AUTO_MANAGED)
                        it.pause()
                    }
                }
                e.phase = TorrentPhase.PAUSED
            }
            Torrents.ACTION_RESUME -> {
                if (id != null) File(store, "$id.paused").delete()
                id?.let { entries[it] }?.let { e ->
                    val hasFile = File(store, "${e.id}.magnet").exists() || File(store, "${e.id}.torrent").exists()
                    if ((e.phase == TorrentPhase.PAUSED || e.phase == TorrentPhase.FAILED) && hasFile) {
                        e.phase = TorrentPhase.DOWNLOADING
                        e.message = null
                        e.problem = null
                        val handle = e.handle?.takeIf { runCatching { it.isValid }.getOrDefault(false) }
                        if (handle != null) {
                            runCatching {
                                handle.setFlags(TorrentFlags.AUTO_MANAGED)
                                handle.resume()
                            }
                        } else {
                            e.handle = null
                            e.requested = false
                        }
                    }
                }
            }
            Torrents.ACTION_REMOVE -> if (id != null) {
                val e = entries.remove(id)
                File(store, "$id.magnet").delete()
                File(store, "$id.torrent").delete()
                File(store, "$id.paused").delete()
                val handle = e?.handle
                if (handle != null && e.phase != TorrentPhase.DONE && !e.finishing) {
                    runCatching { if (handle.isValid) session?.remove(handle, SessionHandle.DELETE_FILES) }
                }
            }
            Torrents.ACTION_CLEAR ->
                entries.values.filter { it.phase == TorrentPhase.DONE }.forEach { entries.remove(it.id) }
        }
        publish(context)
    }

    // ---- Progress loop (driven by the service once a second) ------------------------------------------

    fun tick(context: Context) {
        val s = session ?: run {
            publish(context)
            return
        }
        // Wi-Fi only: hold everything while on mobile data.
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val hold = wifiOnly && (cm == null || cm.activeNetwork == null || cm.isActiveNetworkMetered)
        if (hold != sessionPaused) {
            runCatching { if (hold) s.pause() else s.resume() }
            sessionPaused = hold
        }
        syncStore(context)
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
        publish(context)
    }

    private fun finish(context: Context, e: Entry, handle: TorrentHandle) {
        val g = MemcApp.graph
        val save = handle.savePath()
        val storage = handle.torrentFile()?.files()
        val files = if (storage != null) {
            (0 until storage.numFiles()).map { File(storage.filePath(it, save)) }.filter { it.exists() }
        } else emptyList()
        // Take the torrent out of the engine before touching its files.
        runCatching { session?.remove(handle) }
        e.handle = null
        e.playPath = files.filter { NameParser.isVideo(it.name) }.maxByOrNull { it.length() }?.absolutePath

        val targetId = moveSource
        val folder = moveFolder.trim('/', ' ')
        g.scope.launch {
            val source = if (targetId > 0) runCatching { g.db.sources().get(targetId) }.getOrNull() else null
            if (source == null) {
                forget(context, e.id)
                e.phase = TorrentPhase.DONE
                e.message = "Saved on this phone"
            } else {
                e.phase = TorrentPhase.MOVING
                publish(context)
                try {
                    val total = files.sumOf { it.length() }.coerceAtLeast(1)
                    var before = 0L
                    for (f in files) {
                        val relative = f.absolutePath.removePrefix(save).trim('/')
                        val path = listOf(source.path.trim('/'), folder, relative).filter { it.isNotBlank() }.joinToString("/")
                        g.smb.upload(g.smb.location(source, path), f) { done -> e.moveProgress = (before + done).toFloat() / total }
                        before += f.length()
                    }
                    files.forEach { it.delete() }
                    // Remove the (now empty) folder the torrent made.
                    files.mapNotNull { it.parentFile }.distinct()
                        .filter { it.absolutePath != File(save).absolutePath && it.list()?.isEmpty() == true }
                        .forEach { it.delete() }
                    forget(context, e.id)
                    e.playPath = null
                    e.phase = TorrentPhase.DONE
                    e.message = "Moved to ${source.name}" + if (folder.isNotBlank()) "/$folder" else ""
                } catch (err: Throwable) {
                    e.phase = TorrentPhase.FAILED
                    e.message = "Downloaded, but moving to ${source.name} failed: ${err.message}. The files are still on the phone."
                    forget(context, e.id)
                }
            }
            finishedEvent = true
            publish(context)
        }
    }

    private fun forget(context: Context, id: String) {
        val store = TorrentStore.dir(context)
        File(store, "$id.magnet").delete()
        File(store, "$id.torrent").delete()
        File(store, "$id.paused").delete()
    }

    /** Current list, for the notification. */
    @Volatile var snapshot: List<TorrentItem> = emptyList()
        private set

    /** Sends the current list to the app. */
    fun publish(context: Context) {
        val onHold = sessionPaused
        val list = entries.values.sortedBy { it.name.lowercase() }.map { e ->
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
                    onHold -> "Waiting for Wi-Fi (change in Settings → Torrents)"
                    st == null -> "Starting…"
                    state == "DOWNLOADING_METADATA" -> "Getting torrent details…"
                    state == "CHECKING_FILES" || state == "CHECKING_RESUME_DATA" -> "Checking files…"
                    st.numPeers() == 0 -> "Looking for peers…"
                    else -> "Downloading"
                } + (e.problem?.let { " · Problem: ${it.take(140)}" } ?: "")
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
        snapshot = list
        val finished = finishedEvent
        finishedEvent = false
        runCatching {
            val json = JSONArray()
            list.forEach { json.put(it.toJson()) }
            context.sendBroadcast(
                Intent(Torrents.ACTION_STATE).setPackage(context.packageName)
                    .putExtra("items", json.toString())
                    .putExtra("finished", finished)
            )
        }
    }
}
