package com.jainz.memc.memc

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Live picture settings shared between the UI and the GL thread.
 * [autoHdr] is set when playback starts (switching it needs a restart);
 * [fakedHdr] is set by the extractor when it flagged an SDR video as HDR for Auto HDR.
 */
class PictureControl(boost: Float, autoHdr: Boolean) {
    /** 0..1 */
    @Volatile var boost: Float = boost
    @Volatile var autoHdr: Boolean = autoHdr
    /**
     * Run SDR videos through the HDR path so the screen enters HDR mode. Needed for Auto HDR,
     * and for boost on SDR videos: only HDR mode lets the panel go brighter than SDR white.
     * Decided when playback starts (the video must be re-read to change it).
     */
    @Volatile var convertSdr: Boolean = autoHdr || boost > 0f
    /** Set by the extractor when it flagged the current SDR video as HDR. */
    @Volatile var fakedHdr: Boolean = false

    /** Where SDR white lands in HDR mode: 300 nits, up to 600 with full boost. */
    val sdrWhiteNits: Float get() = 300f + 300f * boost.coerceIn(0f, 1f)
    val sdrPeakNits: Float get() = 1000f + 600f * boost.coerceIn(0f, 1f)
}

/** HDR boost and Auto HDR (SDR -> HDR10) as a Media3 video effect. */
@UnstableApi
class PictureEffect(private val control: PictureControl) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        try {
            PictureShaderProgram(context, useHdr, control)
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
}

@UnstableApi
private class PictureShaderProgram(
    context: Context,
    private val useHdr: Boolean,
    private val control: PictureControl,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ useHdr, /* texturePoolCapacity = */ 1) {

    private val program = GlProgram(context, "memc/fullscreen.vert", "memc/picture.frag").apply {
        setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val mode = when {
                !useHdr -> 0
                control.fakedHdr -> 2
                else -> 1
            }
            program.use()
            program.setSamplerTexIdUniform("uTex", inputTexId, 0)
            program.setIntUniform("uMode", mode)
            program.setFloatUniform("uBoost", control.boost.coerceIn(0f, 1f))
            program.setFloatUniform("uSdrWhiteNits", control.sdrWhiteNits)
            program.setFloatUniform("uPeakNits", if (mode == 2) control.sdrPeakNits else BOOST_PEAK_NITS)
            program.bindAttributesAndUniforms()
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        private const val BOOST_PEAK_NITS = 1600f
    }
}
