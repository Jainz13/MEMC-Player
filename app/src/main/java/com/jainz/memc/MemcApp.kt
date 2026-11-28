package com.jainz.memc

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.jainz.memc.core.CrashLog
import com.jainz.memc.core.DebugRecorder
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
        // The NPU compile process (NpuCompileService) needs none of the app's services.
        if (android.os.Build.VERSION.SDK_INT >= 28 && getProcessName().endsWith(":npucompile")) {
            instance = this
            return
        }
        CrashLog.install(this)
        instance = this
        graph = AppGraph(this)
        CrashLog.collect(this)
        DebugRecorder.resume(this)
        // Automatic backups to network shares (no-op when none are set up).
        runCatching { com.jainz.memc.data.transfer.SyncWorker.schedule(this) }
        if (graph.settings.scanOnStart.value) {
            ScanWorker.enqueue(this, onlyIfStale = true)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            // Posters share the app's HTTP connections.
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { com.jainz.memc.core.Http.client })) }
            .crossfade(true)
            .build()

    companion object {
        lateinit var instance: MemcApp
            private set
        val graph: AppGraph get() = instance.graph
    }
}
