package com.jainz.memc.memc

import android.content.Context
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Live controls shared between the UI and the GL thread. Changing them takes effect on the
 * next frame, so MEMC can be switched on/off or retuned without restarting playback.
 */
class MemcControl(enabled: Boolean, targetFps: Int, quality: Int) {
    @Volatile var enabled: Boolean = enabled
    @Volatile var targetFps: Int = targetFps
    /** 0 = battery saver, 1 = balanced, 2 = best */
    @Volatile var quality: Int = quality

    /** Updated by the engine: the source frame rate it measured, and whether it is interpolating. */
    @Volatile var sourceFps: Float = 0f
    @Volatile var active: Boolean = false
}

/** Media3 video effect that raises the frame rate with motion-compensated interpolation. */
@UnstableApi
class MemcEffect(private val control: MemcControl) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        try {
            MemcShaderProgram(context, useHdr, control)
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
}
