package com.jainz.memc.ui

import android.content.Context
import android.content.Intent
import com.jainz.memc.data.db.VideoEntity
import com.jainz.memc.player.PlayerActivity
import java.util.Locale

object Format {
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    fun runtime(minutes: Int?): String? {
        if (minutes == null || minutes <= 0) return null
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    fun left(v: VideoEntity): String? {
        if (v.durationMs <= 0 || v.positionMs <= 0) return null
        val left = ((v.durationMs - v.positionMs) / 60000).coerceAtLeast(1)
        return if (left >= 60) "${left / 60}h ${left % 60}m left" else "${left}m left"
    }

    fun progress(v: VideoEntity): Float =
        if (v.durationMs > 0) (v.positionMs.toFloat() / v.durationMs).coerceIn(0f, 1f) else 0f

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
        else -> "${bytes / 1024} KB"
    }

    fun episodeCode(season: Int?, episode: Int?): String =
        if (season != null && episode != null) "S$season · E$episode" else ""

    fun rating(r: Double): String? = if (r > 0) String.format(Locale.US, "★ %.1f", r) else null
}

object Play {
    fun video(context: Context, videoId: Long) {
        context.startActivity(
            Intent(context, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_VIDEO_ID, videoId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    fun videoFromStart(context: Context, videoId: Long) {
        context.startActivity(
            Intent(context, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_VIDEO_ID, videoId)
                .putExtra(PlayerActivity.EXTRA_FROM_START, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    fun uri(context: Context, uri: String, title: String) {
        context.startActivity(
            Intent(context, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URI, uri)
                .putExtra(PlayerActivity.EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }
}
