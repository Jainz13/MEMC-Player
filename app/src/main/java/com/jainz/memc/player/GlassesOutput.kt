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
                onSurface(null)
                onInfo(null)
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
        if (listening) {
            dm?.unregisterDisplayListener(listener)
            listening = false
        }
        dismiss()
    }

    private fun show(display: Display) {
        dismiss()
        val p = VideoPresentation(activity, display) { surface -> onSurface(surface) }
        try {
            p.show()
        } catch (e: WindowManager.InvalidDisplayException) {
            DebugRecorder.event("glasses", "Couldn't show on display ${display.displayId}: ${e.message}")
            return
        }
        presentation = p
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
        private val onSurfaceReady: (SurfaceView) -> Unit,
    ) : Presentation(context, display) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val surface = SurfaceView(context)
            val root = FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            setContentView(root)
            onSurfaceReady(surface)
        }
    }
}
