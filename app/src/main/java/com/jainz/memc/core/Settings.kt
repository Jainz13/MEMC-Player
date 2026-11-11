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

    // ---- Phone storage ----
    /** Last seen storage access level (LibraryScanner.StorageAccess ordinal). */
    val lastAccessLevel = int("last_access_level", 0)
    /** Result of the last phone scan, shown in Library and Settings. */
    val deviceScanInfo = string("device_scan_info", "")

    // ---- Video ----
    /** 0 = fit, 1 = crop to fill, 2 = stretch */
    val defaultZoom = int("default_zoom", 0)
    /** Switch the display to the video's frame rate when it can do so without a black flash. */
    val matchFrameRate = bool("match_frame_rate", true)

    // ---- Audio ----
    /** ISO language code, "" = device default */
    val audioLanguage = string("audio_language", "")
    /** Decode every audio track with FFmpeg instead of the phone's decoders. */
    val ffmpegAudio = bool("ffmpeg_audio", false)

    // ---- Subtitles ----
    val subtitlesOn = bool("subtitles_on", false)
    /** ISO language code, "" = device language */
    val subtitleLanguage = string("subtitle_language", "")

    // ---- Torrents ----
    /** Only download on Wi-Fi / unmetered networks. */
    val torrentWifiOnly = bool("torrent_wifi_only", true)
    /** Share to move finished torrents to (source id), 0 = keep them on the phone. */
    val torrentMoveSource = long("torrent_move_source", 0L)
    /** Folder inside that share. */
    val torrentMoveFolder = string("torrent_move_folder", "Downloads")

    // ---- Picture ----
    /** 0..100: brightness boost (colour-preserving) */
    val hdrBoost = int("hdr_boost", 0)
    /** Convert SDR videos to HDR10 so the screen runs in HDR mode */
    val autoHdr = bool("auto_hdr", false)

    // ---- MEMC (used by the frame interpolation engine, stage 2) ----
    /** Use the AI model (RIFE on the NPU) for 60 fps exports. */
    val npuExport = bool("npu_export", true)
    /** Live load figures (CPU, GPU, NPU, temperature) at the bottom of the player. */
    val showStats = bool("show_stats", false)
    val memcEnabled = bool("memc_enabled", false)
    val memcTargetFps = int("memc_target_fps", 60)
    /** 0 = battery saver, 1 = balanced, 2 = best quality */
    val memcQuality = int("memc_quality", 1)
}
