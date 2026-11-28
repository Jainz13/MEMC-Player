package com.jainz.memc.stereo

/**
 * 3D playback settings shared between the player and the 3D render stage.
 * The output is always one side-by-side frame (left eye | right eye), which glasses such as
 * the RayNeo Air series show in 3D once switched to their 3D mode.
 */
class StereoControl(val mode: Int) {
    companion object {
        const val OFF = 0
        /** Ordinary 2D video turned into 3D from estimated depth. */
        const val CONVERT = 1
        /** The file is already full side-by-side 3D (3840x1080 style): passed through. */
        const val SBS = 2
        /** The file is half side-by-side (each eye squeezed to half width): stretched back. */
        const val HALF_SBS = 3

        val labels = listOf(OFF to "Off", CONVERT to "2D → 3D", SBS to "3D video (SBS)", HALF_SBS to "3D video (half SBS)")

        /** Largest shift between the eyes at 100% depth, as a share of the picture width. */
        const val MAX_SHIFT = 0.02f

        /** Guesses the 3D layout from a file name ("Avatar.2009.Half-SBS.mkv"). */
        fun fromFileName(name: String): Int {
            val n = name.lowercase()
            return when {
                Regex("h[-_. ]?sbs|half[-_. ]?sbs").containsMatchIn(n) -> HALF_SBS
                Regex("\\bsbs\\b|full[-_. ]?sbs|side[-_. ]?by[-_. ]?side").containsMatchIn(n) -> SBS
                else -> CONVERT
            }
        }
    }

    /** Depth strength 0..1 (how far things pop out). */
    @Volatile var strength: Float = 0.5f
    /** Where the screen plane sits, 0..1 of the depth range (lower = more pops out). */
    @Volatile var convergence: Float = 0.35f
    @Volatile var swapEyes: Boolean = false

    /** The phone is hot: AI depth pauses and the GPU cues are used. */
    @Volatile var hot: Boolean = false

    /** The AI depth model on the NPU, when available (phone, Snapdragon). */
    @Volatile var npu: NpuDepth? = null

    // ---- Written by the render stage, for the status line ----
    @Volatile var status: String = ""
    @Volatile var outWidth: Int = 0
    @Volatile var outHeight: Int = 0
    /** Depth maps per second actually produced. */
    @Volatile var depthRate: Float = 0f
}
