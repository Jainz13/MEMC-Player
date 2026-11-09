package com.jainz.memc.data.scan

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jainz.memc.MemcApp
import com.jainz.memc.data.smb.SmbPool
import com.jainz.memc.data.tmdb.TmdbAuthException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Background job: scan the phone and network shares, then fetch artwork and details. */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val g = MemcApp.graph
        val scanner = LibraryScanner(applicationContext, g)
        val onlyScrape = inputData.getBoolean(KEY_SCRAPE_ONLY, false)
        var message: String? = null

        if (!onlyScrape) {
            if (g.settings.indexDevice.value) {
                setProgress(workDataOf(KEY_STAGE to "Scanning this phone"))
                runCatching { scanner.scanDevice() }
            } else {
                scanner.clearDevice()
            }
            for (src in g.db.sources().all().filter { it.indexed }) {
                setProgress(workDataOf(KEY_STAGE to "Scanning ${src.name}"))
                try {
                    scanner.scanSource(src)
                    g.db.sources().setScanResult(src.id, System.currentTimeMillis(), null)
                } catch (e: Exception) {
                    // A share that is offline keeps its existing library entries.
                    g.db.sources().setScanResult(src.id, src.lastScanAt, SmbPool.friendlyError(e))
                }
            }
        }

        if (g.tmdb.hasKey) {
            try {
                Scraper(g).run { done, total ->
                    setProgress(workDataOf(KEY_STAGE to "Downloading artwork $done / $total", KEY_DONE to done, KEY_TOTAL to total))
                }
            } catch (e: TmdbAuthException) {
                message = e.message
            } catch (e: Exception) {
                message = "Artwork download paused: ${e.message}"
            }
        }

        g.settings.lastScanAt.set(System.currentTimeMillis())
        return Result.success(workDataOf(KEY_MESSAGE to message))
    }

    companion object {
        const val NAME = "library-scan"
        const val KEY_STAGE = "stage"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_MESSAGE = "message"
        private const val KEY_SCRAPE_ONLY = "scrape_only"
        private const val STALE_MS = 30L * 60 * 1000

        fun enqueue(context: Context, onlyIfStale: Boolean = false, scrapeOnly: Boolean = false) {
            if (onlyIfStale && System.currentTimeMillis() - MemcApp.graph.settings.lastScanAt.value < STALE_MS) return
            val req = OneTimeWorkRequestBuilder<ScanWorker>()
                .setInputData(workDataOf(KEY_SCRAPE_ONLY to scrapeOnly))
                .build()
            // A full scan already running also fetches artwork at its end, so a scrape-only
            // request must not cancel it.
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME,
                if (onlyIfStale || scrapeOnly) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,
                req,
            )
        }

        fun observe(context: Context): Flow<WorkInfo?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NAME).map { it.firstOrNull() }
    }
}
