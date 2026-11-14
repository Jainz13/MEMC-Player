package com.jainz.memc.data

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.jainz.memc.MemcApp
import com.jainz.memc.data.db.SourceEntity
import com.jainz.memc.data.db.VideoEntity
import com.jainz.memc.data.smb.SmbPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Permanently deletes video files, on the phone or on a network share. */
object VideoFiles {

    /** "the phone" or the share's name, for confirmation texts. */
    suspend fun place(video: VideoEntity): String =
        if (video.uri.startsWith("smb://")) {
            MemcApp.graph.db.sources().get(video.sourceId)?.name?.let { "the network share \"$it\"" } ?: "the network share"
        } else "this phone"

    /**
     * Deletes the file behind a library entry and removes the entry.
     * Returns null when done, otherwise a message saying why it could not be deleted.
     */
    suspend fun delete(context: Context, video: VideoEntity): String? = withContext(Dispatchers.IO) {
        val g = MemcApp.graph
        val problem = try {
            val uri = Uri.parse(video.uri)
            when (uri.scheme) {
                "smb" -> {
                    g.smb.delete(g.smb.resolve(uri))
                    null
                }
                "file" -> deleteLocal(context, File(uri.path ?: ""))
                "content" -> deleteContent(context, uri)
                else -> "This kind of file can't be deleted from here"
            }
        } catch (e: Exception) {
            SmbPool.friendlyError(e)
        }
        if (problem == null) {
            g.db.videos().deleteIds(listOf(video.id))
            g.db.meta().pruneMovies()
            g.db.meta().pruneShows()
        }
        problem
    }

    /** Deletes a file on a share that may not be in the library (the share browser). */
    suspend fun deleteOnShare(source: SourceEntity, pathInShare: String): String? = withContext(Dispatchers.IO) {
        val g = MemcApp.graph
        try {
            g.smb.delete(g.smb.location(source, pathInShare))
            g.db.videos().byUri(g.smb.uriFor(source, pathInShare))?.let {
                g.db.videos().deleteIds(listOf(it.id))
                g.db.meta().pruneMovies()
                g.db.meta().pruneShows()
            }
            null
        } catch (e: Exception) {
            SmbPool.friendlyError(e)
        }
    }

    private fun deleteLocal(context: Context, file: File): String? {
        if (!file.exists()) return null // already gone: just drop the entry
        if (file.delete()) {
            // Tell Android's media index the file is gone.
            runCatching { MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null) }
            return null
        }
        return NEEDS_ACCESS
    }

    private fun deleteContent(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        // With full file access, delete the real file; Android then drops its index entry.
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            val path = runCatching {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
            if (path != null) return deleteLocal(context, File(path))
        }
        return try {
            if (resolver.delete(uri, null, null) > 0) null else NEEDS_ACCESS
        } catch (e: SecurityException) {
            NEEDS_ACCESS
        }
    }

    private const val NEEDS_ACCESS =
        "Android didn't allow deleting this file. Give the app \"All files\" access under Library → Phone storage, then try again."
}
