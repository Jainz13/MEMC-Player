package com.jainz.memc.data.scan

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.jainz.memc.AppGraph
import com.jainz.memc.data.db.Kind
import com.jainz.memc.data.db.ScrapeState
import com.jainz.memc.data.db.SourceEntity
import com.jainz.memc.data.db.VideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

private data class FoundFile(
    val uri: String,
    val folder: String,
    val fileName: String,
    val size: Long,
    val modified: Long,
    val durationMs: Long = 0,
)

/** How much of the phone's storage the app may read. */
enum class StorageAccess(val label: String) {
    NONE("Not allowed"),
    LIMITED("Only videos you picked"),
    MEDIA("Photos and videos"),
    FULL("All files"),
}

/** Finds video files on the phone and on network shares and keeps the library table in sync. */
class LibraryScanner(private val context: Context, private val g: AppGraph) {

    fun storageAccess(): StorageAccess {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return StorageAccess.FULL
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_VIDEO) -> StorageAccess.MEDIA
            Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> StorageAccess.MEDIA
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> StorageAccess.LIMITED
            else -> StorageAccess.NONE
        }
    }

    fun hasDevicePermission(): Boolean = storageAccess() != StorageAccess.NONE

    /**
     * Videos in the phone's storage. Two passes, like Nova:
     *  1. the system media index (fast, has durations);
     *  2. a walk through every folder on internal storage and SD cards, which finds files the
     *     media index skipped (e.g. some .mkv/.ts files or folders with a .nomedia file).
     * Files are stored by their real path (file://) whenever access allows, so subtitle files
     * sitting next to a video can be found at playback.
     * Returns a short summary for the UI.
     */
    suspend fun scanDevice(): String = withContext(Dispatchers.IO) {
        val access = storageAccess()
        if (access == StorageAccess.NONE) return@withContext "No access to phone storage"
        val usePaths = access == StorageAccess.MEDIA || access == StorageAccess.FULL
        val found = ArrayList<FoundFile>()
        val seen = HashSet<String>()

        // ---- 1. Media index ----
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        @Suppress("DEPRECATION")
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATA,
        )
        var indexed = 0
        context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val iRel = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val iMod = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            @Suppress("DEPRECATION")
            val iData = c.getColumnIndex(MediaStore.Video.Media.DATA)
            while (c.moveToNext()) {
                val name = c.getString(iName) ?: continue
                val path = if (iData >= 0) c.getString(iData) else null
                if (path != null && !seen.add(path)) continue
                val uri = if (usePaths && path != null) Uri.fromFile(File(path)).toString()
                else ContentUris.withAppendedId(collection, c.getLong(iId)).toString()
                found += FoundFile(
                    uri = uri,
                    folder = c.getString(iRel) ?: "",
                    fileName = name,
                    size = c.getLong(iSize),
                    modified = c.getLong(iMod) * 1000,
                    durationMs = c.getLong(iDur),
                )
                indexed++
            }
        }

        // ---- 2. Folder walk ----
        var extra = 0
        if (usePaths) {
            for (root in storageRoots()) {
                val stack = ArrayDeque<Pair<File, Int>>()
                stack.addLast(root to 0)
                while (stack.isNotEmpty()) {
                    ensureActive()
                    val (dir, depth) = stack.removeLast()
                    val children = dir.listFiles() ?: continue
                    for (f in children) {
                        val name = f.name
                        if (f.isDirectory) {
                            val skip = name.startsWith(".") || (depth == 0 && name == "Android") || depth >= 12
                            if (!skip) stack.addLast(f to depth + 1)
                        } else if (NameParser.isVideo(name) && seen.add(f.absolutePath)) {
                            val rel = dir.absolutePath.removePrefix(root.absolutePath).trim('/')
                            found += FoundFile(
                                uri = Uri.fromFile(f).toString(),
                                folder = if (rel.isEmpty()) "" else "$rel/",
                                fileName = name,
                                size = f.length(),
                                modified = f.lastModified(),
                            )
                            extra++
                        }
                    }
                }
            }
        }

        merge(DEVICE_SOURCE, found)
        when {
            found.isEmpty() && access == StorageAccess.LIMITED ->
                "No videos - you allowed only selected videos. Allow all files to scan everything."
            found.isEmpty() -> "No videos found on this phone"
            extra > 0 -> "${found.size} videos found ($extra outside the media index)"
            else -> "${found.size} videos found"
        }
    }

    private fun storageRoots(): List<File> {
        val roots = LinkedHashSet<File>()
        roots += Environment.getExternalStorageDirectory()
        if (Build.VERSION.SDK_INT >= 30) {
            val sm = context.getSystemService(StorageManager::class.java)
            sm?.storageVolumes?.forEach { v -> v.directory?.let { roots += it } }
        }
        return roots.filter { it.canRead() }
    }

    /** Walks an SMB share (or its configured sub-folder) for video files. */
    suspend fun scanSource(src: SourceEntity): Int = withContext(Dispatchers.IO) {
        val found = ArrayList<FoundFile>()
        val failedDirs = ArrayList<String>()
        val stack = ArrayDeque<Pair<String, Int>>()
        stack.addLast(src.path.trim('/') to 0)
        while (stack.isNotEmpty()) {
            ensureActive()
            val (dir, depth) = stack.removeLast()
            val entries = try {
                g.smb.list(g.smb.location(src, dir))
            } catch (e: Exception) {
                // The share root failing means the server is unreachable: abort and keep the library as is.
                // A single unreadable sub-folder is skipped, and its existing entries are kept.
                if (depth == 0) throw e
                failedDirs += "/$dir"
                continue
            }
            for (e in entries) {
                if (e.isDir) {
                    if (depth < 12 && !skipDir(e.name)) stack.addLast(e.path to depth + 1)
                } else if (NameParser.isVideo(e.name)) {
                    if (e.name.contains("sample", ignoreCase = true) && e.size < 300L * MB) continue
                    found += FoundFile(
                        uri = g.smb.uriFor(src, e.path),
                        folder = "/" + dir,
                        fileName = e.name,
                        size = e.size,
                        modified = e.modified,
                    )
                }
            }
        }
        merge(src.id, found, keepUnder = failedDirs)
        found.size
    }

    private fun skipDir(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith(".") || n.startsWith("@") || n.startsWith("#") || n.startsWith("$") ||
            n == "system volume information" || n == "lost+found" || n == "recycle bin" || n == "thumbs"
    }

    /** Removes the phone's videos from the library (when "Include videos on this phone" is off). */
    suspend fun clearDevice() {
        g.db.videos().deleteSource(DEVICE_SOURCE)
        g.db.meta().pruneMovies()
        g.db.meta().pruneShows()
    }

    private suspend fun merge(sourceId: Long, found: List<FoundFile>, keepUnder: List<String> = emptyList()) {
        val videos = g.db.videos()
        // Match files by their path, not the full link: a share's server address can change
        // and the file (with its watch progress and match) must stay the same entry.
        val existing = videos.bySource(sourceId).associateBy { identity(it.uri) }
        val now = System.currentTimeMillis()
        val inserts = ArrayList<VideoEntity>()
        val updates = ArrayList<VideoEntity>()

        for (f in found) {
            val old = existing[identity(f.uri)]
            if (old == null) {
                inserts += newVideo(sourceId, f, now)
            } else if (old.size != f.size || old.fileName != f.fileName || old.uri != f.uri) {
                updates += old.copy(uri = f.uri, size = f.size, modified = f.modified, fileName = f.fileName)
            }
        }
        val foundKeys = found.mapTo(HashSet()) { identity(it.uri) }
        val removed = existing.values
            .filter { identity(it.uri) !in foundKeys }
            .filter { v -> keepUnder.none { dir -> v.folder == dir || v.folder.startsWith("$dir/") } }
            .map { it.id }

        if (inserts.isNotEmpty()) videos.insertAll(inserts)
        if (updates.isNotEmpty()) videos.updateAll(updates)
        if (removed.isNotEmpty()) removed.chunked(500).forEach { videos.deleteIds(it) }
        if (removed.isNotEmpty()) {
            g.db.meta().pruneMovies()
            g.db.meta().pruneShows()
        }
    }

    private fun newVideo(sourceId: Long, f: FoundFile, now: Long): VideoEntity {
        // One odd file name must never stop the whole scan.
        val p = try {
            NameParser.parse(f.fileName, f.folder)
        } catch (e: Exception) {
            ParsedName(Kind.MOVIE, f.fileName.substringBeforeLast('.'), null, null, null, "")
        }
        val personal = PERSONAL_FOLDERS.containsMatchIn(f.folder)
        val tooSmall = f.size in 1 until 40L * MB && p.kind != Kind.EPISODE
        val skip = personal || tooSmall
        return VideoEntity(
            uri = f.uri,
            sourceId = sourceId,
            folder = f.folder,
            fileName = f.fileName,
            size = f.size,
            modified = f.modified,
            addedAt = now,
            kind = if (skip) Kind.UNKNOWN else p.kind,
            parsedTitle = p.title,
            parsedYear = p.year,
            season = p.season,
            episode = p.episode,
            tech = p.tech,
            scrapeState = if (skip) ScrapeState.SKIPPED else ScrapeState.PENDING,
            durationMs = f.durationMs,
        )
    }

    companion object {
        const val DEVICE_SOURCE = 0L
        private const val MB = 1024L * 1024L
        private val PERSONAL_FOLDERS = Regex(
            """(?i)(^|/)(DCIM|Screenshots|Screen ?recordings?|WhatsApp|Snapchat|Instagram|Recordings|Camera)(/|$)"""
        )

        fun isSmb(uri: String) = uri.startsWith("smb://")

        /** smb://srcN@host/share/path -> "/share/path" (lower-case, SMB is case-insensitive). */
        fun identity(uri: String): String = when {
            isSmb(uri) -> (Uri.parse(uri).path ?: uri).lowercase()
            uri.startsWith("file://") -> Uri.parse(uri).path ?: uri
            else -> uri
        }

        fun devicePermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
