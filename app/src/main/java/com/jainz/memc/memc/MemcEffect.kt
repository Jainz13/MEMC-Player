package com.jainz.memc.memc

import android.content.Context
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live controls shared between the UI and the GL thread. Changing them takes effect on the
 * next frame, so MEMC can be switched on/off or retuned without restarting playback.
 */
class MemcControl(enabled: Boolean, targetFps: Int, quality: Int) {
    @Volatile var enabled: Boolean = enabled
    @Volatile var targetFps: Int = targetFps
    /** 0 = battery saver, 1 = balanced, 2 = best, 3 = AI (RIFE on the NPU, with "best" as its fallback) */
    @Volatile var quality: Int = quality

    /**
     * Long side of the screen in pixels. Frames are processed no larger than this (a 4K film on
     * a 1440p screen is shown at 1440p anyway). 0 = no limit (export).
     */
    @Volatile var maxLongSide: Int = 0
    /** Let the engine ease its workload when it can't keep up in real time (playback only). */
    @Volatile var adaptive: Boolean = true
    @Volatile var playbackSpeed: Float = 1f

    /**
     * Export only: creates the AI motion model on the NPU (called once, on the render thread).
     * When it is set and works, in-between frames come from RIFE instead of block matching.
     */
    @Volatile var npuFactory: (() -> NpuFlow)? = null
    /**
     * Playback: the AI model, created in the background by the player and handed over when
     * ready. The engine uses it while quality is 3 and it is keeping up.
     */
    @Volatile var npu: NpuFlow? = null
    /** What the NPU path is doing, for the status line and the export report. */
    @Volatile var npuStatus: String = ""
    /**
     * How hot Android says the phone is (PowerManager thermal status: 0 none, 1 light,
     * 2 moderate, 3 severe, 4+ critical). The engine backs off as it rises: no AI from 2,
     * lightest GPU quality from 3, no interpolation at all from 4.
     */
    @Volatile var thermalLevel: Int = 0

    // ---- Status, written by the engine ----
    @Volatile var sourceFps: Float = 0f
    @Volatile var active: Boolean = false
    /** 0 = as configured … 4 = interpolation paused because the phone can't keep up. */
    @Volatile var easeLevel: Int = 0
    @Volatile var procWidth: Int = 0
    @Volatile var procHeight: Int = 0
    /** Frames the engine may render ahead of the screen. */
    @Volatile var bufferFrames: Int = 0
    /** Frames currently rendered and waiting for their turn on screen. */
    @Volatile var bufferedFrames: Int = 0

    private val easeRequests = AtomicInteger()

    /** Called by the player when it had to drop late frames. */
    fun requestEase() {
        easeRequests.incrementAndGet()
    }

    internal fun takeEaseRequest(): Boolean {
        while (true) {
            val n = easeRequests.get()
            if (n <= 0) return false
            if (easeRequests.compareAndSet(n, 0)) return true
        }
    }

    /** Processing-size limit for the current quality and ease level. */
    internal fun longSideCap(): Int {
        val screen = maxLongSide
        if (screen <= 0) return Int.MAX_VALUE
        val base = when (quality.coerceIn(0, 3)) {
            0 -> 1920
            1 -> 2560
            else -> screen
        }
        val eased = when (easeLevel) {
            0 -> Int.MAX_VALUE
            1 -> 1920
            2 -> 1600
            else -> 1280
        }
        return minOf(base, eased, screen).coerceAtLeast(640)
    }

    internal fun effectiveQuality(): Int = if (easeLevel >= 1 || thermalLevel >= 3) 0 else quality.coerceIn(0, 2)

    /** Output rate actually in use (the governor may hold 120 fps down to 60). */
    fun effectiveFps(): Int = effectiveTargetFps()

    internal fun effectiveTargetFps(): Int {
        val t = targetFps.coerceIn(24, 240)
        return if (easeLevel >= 2) minOf(t, 60) else t
    }
}

/**
 * Media3 video effect: picture adjustments (boost / Auto HDR) and motion-compensated frame
 * interpolation in one stage, so a frame is only touched once at full size.
 */
@UnstableApi
class MemcEffect(
    private val control: MemcControl,
    private val picture: PictureControl? = null,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        try {
            MemcShaderProgram(context, useHdr, control, picture)
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
}
