package com.jainz.memc.torrent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jainz.memc.MainActivity
import com.jainz.memc.MemcApp
import com.jainz.memc.R
import com.jainz.memc.ui.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps torrents downloading (and moving to the share) while the app is in the background. */
class TorrentService : Service() {

    private var worker: Job? = null
    private var isForeground = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isForeground) {
            try {
                startForeground(NOTIFICATION_ID, notification("Starting…", -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                isForeground = true
            } catch (e: Exception) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (worker?.isActive != true) run()
        return START_NOT_STICKY
    }

    private fun run() {
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "memc:torrent").apply { acquire(12 * 60 * 60 * 1000L) }
        }
        val context = applicationContext
        worker = MemcApp.graph.scope.launch {
            runCatching { TorrentEngine.ensureStarted(context) }.onFailure { TorrentEngine.startFailed(it) }
            var idleTicks = 0
            while (idleTicks < 3) {
                runCatching { TorrentEngine.tick(context) }
                val items = TorrentEngine.items.value
                val active = items.filter { it.phase == TorrentPhase.DOWNLOADING || it.phase == TorrentPhase.MOVING }
                idleTicks = if (TorrentEngine.hasActive()) 0 else idleTicks + 1
                if (active.isNotEmpty()) {
                    val speed = active.sumOf { it.bytesPerSecond }
                    val first = active.first()
                    val text = if (active.size == 1) {
                        "${first.name} · ${(first.progress * 100).toInt()}% · ${Format.size(speed)}/s"
                    } else {
                        "${active.size} torrents · ${Format.size(speed)}/s"
                    }
                    runCatching {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, notification(text, (first.progress * 100).toInt()))
                    }
                }
                delay(1000)
            }
            TorrentEngine.stopIfIdle()
            withContext(Dispatchers.Main) {
                worker = null
                if (TorrentEngine.hasActive()) {
                    run()
                } else {
                    wakeLock?.takeIf { it.isHeld }?.release()
                    wakeLock = null
                    ServiceCompat.stopForeground(this@TorrentService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    isForeground = false
                    stopSelf()
                }
            }
        }
    }

    private fun notification(text: String, percent: Int): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Torrents", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_memc)
            .setContentTitle("Torrents")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .build()
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "torrents"
        private const val NOTIFICATION_ID = 4444
    }
}
