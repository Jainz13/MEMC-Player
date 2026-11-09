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
)

/** Queue of 60 fps exports; the ExportService works through it one video at a time. */
object ExportManager {
    private val ids = AtomicLong(System.currentTimeMillis())
    private val _jobs = MutableStateFlow<List<ExportJob>>(emptyList())
    val jobs: StateFlow<List<ExportJob>> = _jobs.asStateFlow()

    fun enqueue(context: Context, sourceUri: String, title: String) {
        val already = _jobs.value.any {
            it.sourceUri == sourceUri && (it.status == ExportStatus.QUEUED || it.status == ExportStatus.RUNNING)
        }
        if (!already) _jobs.update { it + ExportJob(ids.incrementAndGet(), sourceUri, title) }
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
