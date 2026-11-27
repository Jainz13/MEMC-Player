package com.jainz.memc.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One HTTP client for the whole app (TMDB, posters, Explore sources). Callers that need other
 * timeouts derive from it with newBuilder(), which keeps the shared connection pool and
 * threads: connections to the same server are reused instead of each part opening its own.
 */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
