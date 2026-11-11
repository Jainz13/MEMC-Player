package com.jainz.memc

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import com.jainz.memc.core.CrashLog
import com.jainz.memc.torrent.Torrents
import com.jainz.memc.core.Settings
import com.jainz.memc.data.db.AppDatabase
import com.jainz.memc.data.scan.ScanWorker
import com.jainz.memc.data.smb.SmbPool
import com.jainz.memc.data.tmdb.TmdbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Simple manual dependency container: one instance of each service for the whole app. */
class AppGraph(context: Context) {
    val settings = Settings(context)
    val db = AppDatabase.create(context)
    val smb = SmbPool(db)
    val tmdb = TmdbClient(settings)
    /** Background scope for small writes that must outlive a screen (e.g. saving playback position). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

class MemcApp : Application(), SingletonImageLoader.Factory {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        instance = this
        graph = AppGraph(this)
        // The torrent engine has a process of its own; it needs none of what follows.
        if (getProcessName().endsWith(":torrent")) return
        CrashLog.collect(this)
        Torrents.init(this)
        if (graph.settings.scanOnStart.value) {
            ScanWorker.enqueue(this, onlyIfStale = true)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .crossfade(true)
            .build()

    companion object {
        lateinit var instance: MemcApp
            private set
        val graph: AppGraph get() = instance.graph
    }
}
