package com.jainz.memc.export

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

enum class ExportStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class ExportJob(
    val id: Long,
    val sourceUri: String,
    val title: String,
    val status: ExportStatus = ExportStatus.QUEUED,
    /** 0..100, or -1 while unknown */
    val progress: Int = -1,
    val message: String? = null,
    /** content:// address of the finished file in Movies/MEMC Player */
    val outputUri: String? = null,
    /** When rendering started (ms since epoch), for the speed and time-left readout. */
    val startedAt: Long = 0,
    /** Live figures while rendering (null until the first sample). */
    val stats: ExportStats? = null,
    /** Set for a 3D export (instead of 60 fps). */
    val threeD: ThreeDExport? = null,
) {
    val kindLabel: String get() = threeD?.let { if (it.halfSbs) "3D Half-SBS" else "3D Full-SBS" } ?: "60 fps"
}

/** Settings of a 3D export: side-by-side layout and the 3D look chosen in the player. */
data class ThreeDExport(
    /** Half side-by-side 1920x1080 (3D TVs, projectors) instead of full 3840x1080 (AR glasses). */
    val halfSbs: Boolean,
    val strength: Float,
    val convergence: Float,
    val swapEyes: Boolean,
)

/** What the export is doing right now, refreshed every couple of seconds. */
data class ExportStats(
    /** Frames of the 60 fps output per second of wall time. */
    val fps: Double,
    /** Output frames written so far, and the total expected (0 when the length is unknown). */
    val framesDone: Long,
    val framesTotal: Long,
    val elapsedMs: Long,
    /** -1 when not known yet. */
    val leftMs: Long,
    val cpuPercent: Int,
    val gpuPercent: Int,
    val npuPercent: Int,
    val npuMsPerPass: Int,
    val engine: String,
    val temps: com.jainz.memc.core.SystemTemps.Reading,
) {
    /** How many seconds of 60 fps video are made per second. */
    val speedX: Double get() = fps / 60.0
}

/** Queue of 60 fps and 3D exports; the ExportService works through it one video at a time. */
object ExportManager {
    private val ids = AtomicLong(System.currentTimeMillis())
    private val _jobs = MutableStateFlow<List<ExportJob>>(emptyList())
    val jobs: StateFlow<List<ExportJob>> = _jobs.asStateFlow()

    fun enqueue(context: Context, sourceUri: String, title: String, threeD: ThreeDExport? = null) {
        val already = _jobs.value.any {
            it.sourceUri == sourceUri && it.threeD?.halfSbs == threeD?.halfSbs && (it.threeD == null) == (threeD == null) &&
                (it.status == ExportStatus.QUEUED || it.status == ExportStatus.RUNNING)
        }
        if (!already) _jobs.update { it + ExportJob(ids.incrementAndGet(), sourceUri, title, threeD = threeD) }
        ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java))
    }

    fun cancel(context: Context, id: Long) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        when (job.status) {
            ExportStatus.QUEUED -> update(id) { it.copy(status = ExportStatus.CANCELLED) }
            ExportStatus.RUNNING -> context.startService(
                Intent(context, ExportService::class.java).setAction(ExportService.ACTION_CANCEL)
            )
            else -> Unit
        }
    }

    fun retry(context: Context, id: Long) {
        update(id) { it.copy(status = ExportStatus.QUEUED, progress = -1, message = null) }
        ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java))
    }

    fun clearFinished() {
        _jobs.update { list -> list.filter { it.status == ExportStatus.QUEUED || it.status == ExportStatus.RUNNING } }
    }

    internal fun update(id: Long, change: (ExportJob) -> ExportJob) {
        _jobs.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    internal fun nextQueued(): ExportJob? = _jobs.value.firstOrNull { it.status == ExportStatus.QUEUED }

    internal fun get(id: Long): ExportJob? = _jobs.value.firstOrNull { it.id == id }
}
