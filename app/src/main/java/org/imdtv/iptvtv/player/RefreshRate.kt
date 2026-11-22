package org.imdtv.iptvtv.player

import android.view.Display
import android.view.Window

/**
 * Playback › Match TV refresh rate: switches the display mode to one whose refresh rate carries the stream's frame
 * rate without pulldown (23.976/24 → 24 or 48/120 Hz, 25 → 25/50/100 Hz, 29.97/30 → 30/60/120 Hz). Only modes at the
 * display's current resolution are considered, and only when the match is close (±0.35 Hz); otherwise nothing changes.
 * Device-dependent: boxes that expose one mode simply keep it.
 */
object RefreshRate {

    /** One display mode candidate: [id] with its resolution and refresh rate. */
    data class Mode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

    /**
     * The mode to switch to for [frameRate], or null to keep the current one. [current] is the active mode's id;
     * a mode already within tolerance of a whole multiple of the frame rate wins (no switch when it already fits).
     */
    fun pick(modes: List<Mode>, current: Mode?, frameRate: Float?): Mode? {
        if (frameRate == null || frameRate !in 20f..61f || current == null) return null
        val same = modes.filter { it.width == current.width && it.height == current.height }
        if (matches(current.refreshRate, frameRate)) return null // already smooth
        return same.filter { it.id != current.id && matches(it.refreshRate, frameRate) }
            // The closest match first (23.976 for a 23.976 film, 24 for a 24 one), then the slowest (24, not 120):
            // less HDMI re-negotiation surface.
            .minWithOrNull(compareBy({ kotlin.math.abs(it.refreshRate - Math.round(it.refreshRate / frameRate) * frameRate) }, { it.refreshRate }))
    }

    /** True when [refresh] is (within tolerance) a whole multiple of [frameRate] — 24, 48, 60 for 23.976… */
    fun matches(refresh: Float, frameRate: Float): Boolean {
        val multiple = Math.round(refresh / frameRate)
        return multiple >= 1 && kotlin.math.abs(refresh - multiple * frameRate) <= TOLERANCE_HZ
    }

    /** Applies [frameRate] to [window]; returns the previous preferredDisplayModeId to restore, or null when unchanged. */
    fun apply(window: Window, frameRate: Float?, enabled: Boolean): Int? {
        val attrs = window.attributes
        val previous = attrs.preferredDisplayModeId
        if (!enabled || frameRate == null) {
            if (previous != 0) { attrs.preferredDisplayModeId = 0; window.attributes = attrs }
            return null
        }
        @Suppress("DEPRECATION")
        val display: Display = window.windowManager.defaultDisplay
        val current = display.mode?.let { Mode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val modes = display.supportedModes.map { Mode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val target = pick(modes, current, frameRate) ?: run {
            if (previous != 0) { attrs.preferredDisplayModeId = 0; window.attributes = attrs }
            return null
        }
        attrs.preferredDisplayModeId = target.id
        window.attributes = attrs
        android.util.Log.i("IptvPlayer", "refresh rate: mode ${target.id} ${target.refreshRate} Hz for $frameRate fps")
        return previous
    }

    /** Back to the box's own mode choice (the player left the screen or the setting went off). */
    fun restore(window: Window) {
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId != 0) { attrs.preferredDisplayModeId = 0; window.attributes = attrs }
    }

    private const val TOLERANCE_HZ = 0.35f
}