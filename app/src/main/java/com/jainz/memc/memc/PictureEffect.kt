package com.jainz.memc.memc

/**
 * Live picture settings shared between the UI and the GL thread. The picture shader runs as
 * the first step of [MemcShaderProgram].
 */
class PictureControl(boost: Float, autoHdr: Boolean) {
    /** 0..1 */
    @Volatile var boost: Float = boost
    @Volatile var autoHdr: Boolean = autoHdr
    /**
     * Auto HDR: run SDR videos through the HDR path so the screen enters HDR mode.
     * Decided when playback starts (the video must be re-read to change it).
     */
    @Volatile var convertSdr: Boolean = autoHdr
    /** Set by the extractor when it flagged the current SDR video as HDR. */
    @Volatile var fakedHdr: Boolean = false

    // ---- Status, written by the engine (shown in the player for troubleshooting) ----
    /** null until the pipeline exists; then whether it runs in HDR. */
    @Volatile var pipelineHdr: Boolean? = null
    /** Shader mode of the last frame: 0 SDR, 1 HDR, 2 Auto HDR, -1 none yet. */
    @Volatile var lastMode: Int = -1

    /** Where SDR white lands in Auto HDR: 300 nits, up to 600 with full boost. */
    val sdrWhiteNits: Float get() = 300f + 300f * boost.coerceIn(0f, 1f)
    val sdrPeakNits: Float get() = 1000f + 600f * boost.coerceIn(0f, 1f)

    internal fun needsShader(useHdr: Boolean): Boolean = boost > 0.001f || (useHdr && fakedHdr)
}
