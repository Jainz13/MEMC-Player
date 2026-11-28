package com.jainz.memc.data.transfer

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import com.jainz.memc.R
import com.jainz.memc.core.Compat
import com.jainz.memc.ui.Format
import com.jainz.memc.data.db.SourceEntity
import com.jainz.memc.data.smb.SmbPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.io.InputStream

/** Keeps a long copy running as a foreground job; carries on without it if Android refuses. */
internal suspend fun CoroutineWorker.runInForeground(id: Int, n: Notification) {
    runCatching { setForeground(ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)) }
}

/** Shared parts of copying files from the phone to a network share. */
object Transfers {
    private const val CHANNEL = "transfers"

    fun join(folder: String, name: String): String {
        val f = folder.trim('/')
        return if (f.isEmpty()) name else "$f/$name"
    }

    fun open(context: Context, uri: String): InputStream =
        context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("Can't read $uri")

    fun notification(context: Context, title: String, text: String, percent: Int, ongoing: Boolean): Notification =
        NotificationCompat.Builder(context, Compat.channel(context, CHANNEL, "Uploads and backups"))
            .setSmallIcon(R.drawable.ic_stat_memc)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .apply { if (ongoing) setProgress(100, percent.coerceIn(0, 100), percent < 0) }
            .build()

    @SuppressLint("MissingPermission") // Without the permission Android simply doesn't show it.
    fun notify(context: Context, id: Int, n: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    /**
     * Copies one file to the share unless the same file (same size) is already there.
     * Returns true when it uploaded, false when it was already there. Checks the size after.
     */
    suspend fun send(
        smb: SmbPool,
        source: SourceEntity,
        remotePath: String,
        size: Long,
        openInput: () -> InputStream,
        onProgress: (Long) -> Unit,
    ): Boolean = runInterruptible(Dispatchers.IO) {
        val loc = smb.location(source, remotePath)
        val existing = smb.remoteSize(loc)
        if (existing != null && size > 0 && existing == size) return@runInterruptible false
        smb.upload(loc, openInput, onProgress)
        if (size > 0) {
            val now = smb.remoteSize(loc)
            if (now != size) throw IOException("The copy on the share is ${Format.size(now ?: 0)}, expected ${Format.size(size)}")
        }
        true
    }

    /** "3 files, 24.1 GB" */
    fun amount(files: Int, bytes: Long) = "$files file" + (if (files == 1) "" else "s") + ", " + Format.size(bytes)
}
