package com.jainz.memc.player

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.jainz.memc.core.DebugRecorder

/**
 * Video on an external display (AR glasses over USB-C, a monitor) while the phone shows only
 * a black touch pad. Uses Android's Presentation window, which works in normal screen
 * mirroring; Samsung DeX desktop mode limits phones to 1080p, so 3D is best with DeX off.
 *
 * The picture fills the whole external display: in the glasses' 3D mode (3840x1080) each eye
 * gets its half 1:1, and if the glasses still report 1920x1080 the frame becomes half
 * side-by-side, which their 3D mode stretches back.
 */
class GlassesOutput(
    private val activity: Activity,
    /** Called on the main thread with the surface to draw on, or null when the display went away. */
    private val onSurface: (SurfaceView?) -> Unit,
    /** Display description changed ("3840x1080 at 60 Hz"). */
    private val onInfo: (String?) -> Unit,
) {
    private val dm = activity.getSystemService(DisplayManager::class.java)
    private val fallBack = Runnable {
        if (!active) {
            onSurface(null)
            onInfo(null)
        }
    }
    private var cues: List<androidx.media3.common.text.Cue> = emptyList()
    var subtitleScale = 1f

    /** Subtitles for the glasses (drawn in both eyes' halves). */
    fun setCues(list: List<androidx.media3.common.text.Cue>) {
        cues = list
        presentation?.setCues(list)
    }
    private val handler = Handler(Looper.getMainLooper())
    private var presentation: VideoPresentation? = null
    private var listening = false

    val active: Boolean get() = presentation?.isShowing == true

    fun externalDisplay(): Display? =
        dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (!active) externalDisplay()?.let { show(it) }
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (presentation?.display?.displayId == displayId) {
                dismiss()
                // Switching the glasses between 2D and 3D disconnects and reconnects them for a
                // moment. Only fall back to the phone if they don't come back.
                handler.removeCallbacks(fallBack)
                handler.postDelayed(fallBack, 4000)
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            val p = presentation ?: return
            if (p.display.displayId == displayId) {
                // The glasses switched between 2D (1920x1080) and 3D (3840x1080): ask for the widest mode.
                pickMode(p)
                onInfo(describe(p.display))
            }
        }
    }

    /** Starts using an external display (now, or as soon as one is connected). */
    fun start() {
        if (!listening) {
            dm?.registerDisplayListener(listener, handler)
            listening = true
        }
        if (!active) externalDisplay()?.let { show(it) }
    }

    fun stop() {
        handler.removeCallbacks(fallBack)
        if (listening) {
            dm?.unregisterDisplayListener(listener)
            listening = false
        }
        dismiss()
    }

    private fun show(display: Display) {
        dismiss()
        handler.removeCallbacks(fallBack)
        val p = VideoPresentation(activity, display, subtitleScale) { surface -> onSurface(surface) }
        try {
            p.show()
        } catch (e: WindowManager.InvalidDisplayException) {
            DebugRecorder.event("glasses", "Couldn't show on display ${display.displayId}: ${e.message}")
            return
        }
        presentation = p
        p.setCues(cues)
        pickMode(p)
        onInfo(describe(display))
        DebugRecorder.event("glasses", "Video on external display: ${describe(display)}")
    }

    private fun dismiss() {
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null
    }

    /** Prefers the widest mode (3840x1080 in the glasses' 3D mode), at 60 Hz if offered. */
    private fun pickMode(p: VideoPresentation) {
        val d = p.display
        val modes = d.supportedModes
        val best = modes.sortedWith(
            compareByDescending<Display.Mode> { it.physicalWidth }
                .thenBy { kotlin.math.abs(it.refreshRate - 60f) },
        ).firstOrNull() ?: return
        val w = p.window ?: return
        if (w.attributes.preferredDisplayModeId != best.modeId) {
            w.attributes = w.attributes.apply { preferredDisplayModeId = best.modeId }
        }
    }

    private fun describe(d: Display): String {
        val m = d.mode
        val threeD = m.physicalWidth >= 3000 && m.physicalWidth >= m.physicalHeight * 3
        return "${d.name}: ${m.physicalWidth}×${m.physicalHeight} at ${m.refreshRate.toInt()} Hz" +
            if (threeD) " (3D mode)" else " - switch the glasses to 3D mode"
    }

    private class VideoPresentation(
        context: Context,
        display: Display,
        private val subtitleScale: Float,
        private val onSurfaceReady: (SurfaceView) -> Unit,
    ) : Presentation(context, display) {
        private val subtitles = mutableListOf<androidx.media3.ui.SubtitleView>()
        private var pending: List<androidx.media3.common.text.Cue> = emptyList()

        fun setCues(list: List<androidx.media3.common.text.Cue>) {
            pending = list
            subtitles.forEach { it.setCues(list) }
        }

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val surface = SurfaceView(context)
            val root = FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            // One subtitle layer per eye (left and right half), at screen depth.
            val eyes = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            repeat(2) {
                val sv = androidx.media3.ui.SubtitleView(context).apply {
                    setApplyEmbeddedFontSizes(false)
                    setStyle(
                        androidx.media3.ui.CaptionStyleCompat(
                            Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null,
                        ),
                    )
                    setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleScale)
                    setBottomPaddingFraction(0.06f)
                    setCues(pending)
                }
                subtitles += sv
                eyes.addView(sv, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
            root.addView(eyes, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setContentView(root)
            onSurfaceReady(surface)
        }
    }
}
