package com.jainz.memc.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App settings stored in SharedPreferences and exposed as observable state. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("memc_settings", Context.MODE_PRIVATE)

    inner class Pref<T>(
        private val key: String,
        default: T,
        private val read: (String, T) -> T,
        private val write: (String, T) -> Unit,
    ) {
        private val state = MutableStateFlow(read(key, default))
        val flow: StateFlow<T> = state.asStateFlow()
        val value: T get() = state.value
        fun set(v: T) {
            write(key, v)
            state.value = v
        }
    }

    private fun string(key: String, def: String) = Pref(
        key, def,
        { k, d -> prefs.getString(k, d) ?: d },
        { k, v -> prefs.edit().putString(k, v).apply() },
    )

    private fun bool(key: String, def: Boolean) = Pref(
        key, def,
        { k, d -> prefs.getBoolean(k, d) },
        { k, v -> prefs.edit().putBoolean(k, v).apply() },
    )

    private fun long(key: String, def: Long) = Pref(
        key, def,
        { k, d -> prefs.getLong(k, d) },
        { k, v -> prefs.edit().putLong(k, v).apply() },
    )

    private fun int(key: String, def: Int) = Pref(
        key, def,
        { k, d -> prefs.getInt(k, d) },
        { k, v -> prefs.edit().putInt(k, v).apply() },
    )

    /** TMDB API key (v3 key) or API Read Access Token (v4, starts with "eyJ"). */
    val tmdbKey = string("tmdb_key", "")
    /** Metadata language, e.g. en-US, ar-SA, hi-IN. */
    val language = string("language", "en-US")
    val scanOnStart = bool("scan_on_start", true)
    val indexDevice = bool("index_device", true)
    val lastScanAt = long("last_scan_at", 0L)
    val autoPlayNext = bool("auto_play_next", true)
    val seekStepSec = int("seek_step", 10)
    val subtitleScale = int("subtitle_scale", 100)
}
