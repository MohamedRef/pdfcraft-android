package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** How long after the last pen sample (hover or touch) the writing-hand guard stays on. */
internal const val PALM_GUARD_WINDOW_MS = 1500L

/**
 * The writing-hand guard region (#156) for a pen tip at [pen]: it reaches [size] px towards the
 * writing hand (right for right-handers, left for left-handers) and further below the tip, where
 * a resting palm or wrist lands, plus a small margin around the tip itself.
 */
internal fun palmGuardRegion(pen: Offset, leftHanded: Boolean, size: Float): Rect {
    val margin = size * 0.25f
    val reach = size * 1.5f
    val left = if (leftHanded) pen.x - reach else pen.x - margin
    val right = if (leftHanded) pen.x + margin else pen.x + reach
    return Rect(left, pen.y - margin, right, pen.y + size * 2f)
}

/** Whether a finger touch at [touch] is the writing hand resting near a recently active pen. */
internal fun palmGuardRejects(
    pen: Offset?,
    penTimeMs: Long,
    touch: Offset,
    touchTimeMs: Long,
    leftHanded: Boolean,
    size: Float,
): Boolean {
    if (pen == null || size <= 0f || touchTimeMs - penTimeMs !in 0..PALM_GUARD_WINDOW_MS) return false
    return palmGuardRegion(pen, leftHanded, size).contains(touch)
}

/** The last pen position the Draw layer saw, hovering or writing. */
internal class PenTracker {
    var position: Offset? = null
        private set
    var timeMs: Long = 0L
        private set

    fun seen(p: Offset, t: Long) {
        if (p.x.isFinite() && p.y.isFinite()) {
            position = p
            timeMs = t
        }
    }
}
