package io.github.artcraftandroid.pdfcraft.ui.scan

import kotlin.math.abs
import kotlin.math.sqrt

// Keeps the live page outline steady and chooses the hint (issue #295, S09 sections 3 and 4).
// Pure state with no Android imports. The caller passes the time of each frame: nothing here
// reads a clock.

// Every number of the spec is named here. Values the spec does not give are marked as chosen.

/** Share of the way each corner moves toward a new detection (S09 section 3). */
private const val SMOOTHING_ALPHA: Float = 0.55f

/** A move of any corner beyond this share of the frame diagonal is a new page, taken at once. */
private const val JUMP_SHARE_OF_DIAGONAL: Float = 0.15f

/** If the last detection is older than this, the next one is taken at once, not smoothed. */
private const val MAX_SMOOTHING_AGE_MS: Long = 500L

/** How long the last quad is shown solid after the last detection (a dropout, S09 section 3). */
private const val HOLD_MS: Long = 350L

/** How long the quad takes to fade out after the hold. Chosen here: the spec gives no value. */
private const val FADE_MS: Long = 200L

/** Number of recent detections the stability check looks at. */
private const val STABLE_WINDOW_DETECTIONS: Int = 6

/** The stability window must span at least this long. */
private const val STABLE_MIN_SPAN_MS: Long = 400L

/** Every detection in the stability window needs at least this confidence. */
private const val STABLE_MIN_CONFIDENCE: Float = 0.6f

/** Largest move of a corner between consecutive detections for a page to count as still. */
private const val STABLE_MAX_MOVE_SHARE: Float = 0.012f

/** With no page for this long, the hint asks the user to point the camera at a document. */
private const val LOST_HINT_MS: Long = 1500L

/** Mean luma of the frame (0 to 255) below which the hint asks for more light. */
private const val DARK_LUMA: Float = 55f

/** A corner this close to the frame edge (a share) means the page is cut off. */
private const val EDGE_MARGIN_SHARE: Float = 0.015f

/** A quad covering less of the frame than this asks the user to move closer. */
private const val SMALL_PAGE_AREA_SHARE: Float = 0.22f

/** A longer side over a shorter opposite side above this means the phone is too steep. */
private const val FLAT_SIDE_RATIO: Float = 1.7f

/** A new hint replaces the shown one only after its condition has held this long. */
private const val HINT_HOLD_MS: Long = 400L

/** Frame width over height assumed when the caller gives none: the 4:3 viewfinder. */
private const val DEFAULT_FRAME_ASPECT: Float = 4f / 3f

/** A side shorter than this (in frame heights) is treated as degenerate. */
private const val DEGENERATE_SIDE: Float = 1e-6f

/** The hint under the viewfinder (S09 section 4). An enum with no text: the screen maps each value. */
enum class ScanHint {
    /** Nothing to say yet. Also shown until the first condition has held for the hint hold time. */
    NONE,
    /** No page for 1.5 s. */
    POINT_AT_DOCUMENT,
    /** Mean luma of the frame is below 55. */
    MORE_LIGHT,
    /** A corner of the page is at the edge of the frame. */
    FIT_WHOLE_PAGE,
    /** The page covers less than 22% of the frame. */
    MOVE_CLOSER,
    /** Opposite sides of the page differ by more than 1.7 times. */
    HOLD_FLATTER,
    /** A page is seen but has not held still yet. */
    HOLD_STILL,
    /** The page is steady and the shot can be taken. */
    READY
}

/**
 * What the viewfinder draws for one frame. [corners] are the smoothed corners as eight shares of
 * the upright frame (x, y pairs: top left, top right, bottom right, bottom left), or null when
 * nothing is drawn. [alpha] is the opacity from 0 to 1: 1 while the page is seen, fading after a
 * lost detection. [stable] is true when the page has held still, for auto-capture. [hint] is the
 * line to show under the viewfinder.
 */
class ScanOutline(
    val corners: FloatArray?,
    val alpha: Float,
    val stable: Boolean,
    val hint: ScanHint,
) {
    companion object {
        /** Nothing drawn and no hint. */
        val NONE: ScanOutline = ScanOutline(null, 0f, false, ScanHint.NONE)
    }
}

/**
 * Turns the detector's quads, one per analysed frame, into a steady outline and a hint (issue
 * #295, S09 sections 3 and 4). [frameAspect] is the upright analysed frame's width over its height;
 * a screen that changes orientation makes a new tracker. Times are in milliseconds and must not go
 * backwards. Create one tracker per camera session and call [onFrame] for every analysed frame.
 */
class ScanOutlineTracker(private val frameAspect: Float = DEFAULT_FRAME_ASPECT) {

    /** One accepted detection, kept for the stability check. [move] is its largest corner move from the one before. */
    private class Detection(val atMs: Long, val corners: FloatArray, val confidence: Float, val move: Float)

    private val history: MutableList<Detection> = mutableListOf()
    private var shown: FloatArray? = null
    private var started: Boolean = false
    private var lastFoundMs: Long = 0L
    private var shownHint: ScanHint = ScanHint.NONE
    private var pendingHint: ScanHint = ScanHint.NONE
    private var pendingSinceMs: Long = 0L

    /**
     * Takes one analysed frame. [found] is the page's quad as eight shares of the upright frame
     * (TL, TR, BR, BL), or null when no page was found. [confidence] is the detector's confidence
     * from 0 to 1, [meanLuma] the frame's mean luma from 0 to 255, [atMs] the frame's time.
     * Returns what to draw now.
     */
    fun onFrame(found: FloatArray?, confidence: Float, meanLuma: Float, atMs: Long): ScanOutline {
        if (!started) {
            started = true
            lastFoundMs = atMs
            pendingSinceMs = atMs
        }
        val detection: FloatArray? = usableQuad(found)
        if (detection != null) accept(detection, confidence, atMs)

        val age: Long = atMs - lastFoundMs
        val alpha: Float = outlineAlpha(age)
        if (alpha <= 0f) shown = null
        val drawn: FloatArray? = if (alpha > 0f) shown?.copyOf() else null
        val stable: Boolean = alpha > 0f && age <= HOLD_MS && isStable()
        val quadForHint: FloatArray? = if (alpha > 0f) shown else null
        val desired: ScanHint = desiredHint(atMs, quadForHint, stable, meanLuma)
        val hint: ScanHint = settleHint(desired, atMs)
        return ScanOutline(drawn, alpha, stable, hint)
    }

    /** Takes a detection: smoothed into the outline, or taken at once when it is a new page or the old one is stale. */
    private fun accept(det: FloatArray, confidence: Float, atMs: Long) {
        val previous: FloatArray? = shown
        val recent: Boolean = previous != null && atMs - lastFoundMs <= MAX_SMOOTHING_AGE_MS
        if (previous == null || !recent) {
            shown = det.copyOf()
            restartHistory(det, confidence, atMs)
            lastFoundMs = atMs
            return
        }
        val aligned: FloatArray = alignToPrevious(det, previous, frameAspect)
        val moved: Float = maxCornerMove(aligned, previous, frameAspect)
        if (moved > JUMP_SHARE_OF_DIAGONAL) {
            shown = aligned
            restartHistory(aligned, confidence, atMs)
        } else {
            shown = smoothCorners(previous, aligned)
            addToHistory(aligned, confidence, atMs)
        }
        lastFoundMs = atMs
    }

    /** Starts the stability history again, after a new page or a long gap. */
    private fun restartHistory(corners: FloatArray, confidence: Float, atMs: Long) {
        history.clear()
        history.add(Detection(atMs, corners.copyOf(), confidence, 0f))
    }

    /** Adds a detection and keeps only the last [STABLE_WINDOW_DETECTIONS]. */
    private fun addToHistory(corners: FloatArray, confidence: Float, atMs: Long) {
        val last: Detection? = history.lastOrNull()
        val move: Float = if (last == null) 0f else maxCornerMove(corners, last.corners, frameAspect)
        history.add(Detection(atMs, corners.copyOf(), confidence, move))
        while (history.size > STABLE_WINDOW_DETECTIONS) history.removeAt(0)
    }

    /**
     * True when the last detections show a page that is still: the window is full, spans at least
     * [STABLE_MIN_SPAN_MS], every confidence is high enough, and no corner moved too far between
     * consecutive detections inside the window.
     */
    private fun isStable(): Boolean {
        if (history.size < STABLE_WINDOW_DETECTIONS) return false
        val first: Detection = history[0]
        val last: Detection = history[history.size - 1]
        if (last.atMs - first.atMs < STABLE_MIN_SPAN_MS) return false
        for (i in history.indices) {
            val d: Detection = history[i]
            if (d.confidence < STABLE_MIN_CONFIDENCE) return false
            // the first entry's move is from a detection outside the window, so it is not counted
            if (i > 0 && d.move >= STABLE_MAX_MOVE_SHARE) return false
        }
        return true
    }

    /** Opacity of the outline [ageMs] after the last detection: solid for the hold time, then a linear fade. */
    private fun outlineAlpha(ageMs: Long): Float {
        if (shown == null) return 0f
        if (ageMs <= HOLD_MS) return 1f
        val fading: Float = (ageMs - HOLD_MS).toFloat() / FADE_MS.toFloat()
        return (1f - fading).coerceIn(0f, 1f)
    }

    /**
     * The hint the frame calls for, by priority (S09 section 4). [quad] is the outline being drawn,
     * or null. The first condition that holds wins.
     */
    private fun desiredHint(atMs: Long, quad: FloatArray?, stable: Boolean, meanLuma: Float): ScanHint {
        if (atMs - lastFoundMs >= LOST_HINT_MS) return ScanHint.POINT_AT_DOCUMENT
        if (meanLuma < DARK_LUMA) return ScanHint.MORE_LIGHT
        if (quad == null) return ScanHint.NONE
        if (touchesFrameEdge(quad)) return ScanHint.FIT_WHOLE_PAGE
        if (quadArea(quad) < SMALL_PAGE_AREA_SHARE) return ScanHint.MOVE_CLOSER
        if (isSkewed(quad, frameAspect)) return ScanHint.HOLD_FLATTER
        return if (stable) ScanHint.READY else ScanHint.HOLD_STILL
    }

    /**
     * Shows a new hint only after the desired one has been the same for [HINT_HOLD_MS] in a row.
     * A condition that goes away before then resets the timer, so a flicker never shows.
     */
    private fun settleHint(desired: ScanHint, atMs: Long): ScanHint {
        if (desired == shownHint) {
            pendingHint = shownHint
            pendingSinceMs = atMs
            return shownHint
        }
        if (desired != pendingHint) {
            pendingHint = desired
            pendingSinceMs = atMs
        }
        if (atMs - pendingSinceMs >= HINT_HOLD_MS) shownHint = desired
        return shownHint
    }

    private companion object {
        /** Returns the eight values as shares of 0 to 1, or null when the input is missing or not finite. */
        fun usableQuad(found: FloatArray?): FloatArray? {
            if (found == null || found.size < 8) return null
            val out = FloatArray(8)
            for (i in 0 until 8) {
                val v: Float = found[i]
                if (v.isNaN() || v.isInfinite()) return null
                out[i] = v.coerceIn(0f, 1f)
            }
            return out
        }
    }
}

/** Distance between corner [i] of [a] and of [b], in frame heights, with the x side scaled by [aspect]. */
private fun cornerDistance(a: FloatArray, b: FloatArray, i: Int, aspect: Float): Float {
    val dx: Float = (a[2 * i] - b[2 * i]) * aspect
    val dy: Float = a[2 * i + 1] - b[2 * i + 1]
    return sqrt(dx * dx + dy * dy)
}

/** Largest corner move between two quads, as a share of the frame diagonal. */
private fun maxCornerMove(a: FloatArray, b: FloatArray, aspect: Float): Float {
    val diagonal: Float = sqrt(aspect * aspect + 1f)
    var largest = 0f
    for (i in 0 until 4) {
        val d: Float = cornerDistance(a, b, i, aspect) / diagonal
        if (d > largest) largest = d
    }
    return largest
}

/** Sum of squared corner distances between two quads (used to pick the corner order). */
private fun squaredCost(a: FloatArray, b: FloatArray, aspect: Float): Float {
    var sum = 0f
    for (i in 0 until 4) {
        val d: Float = cornerDistance(a, b, i, aspect)
        sum += d * d
    }
    return sum
}

/**
 * The detection with its corners in the cyclic order (one of four starts) that moves least from
 * [previous]. A near-square page can come back with its corners one place round; this undoes that.
 */
private fun alignToPrevious(det: FloatArray, previous: FloatArray, aspect: Float): FloatArray {
    var best: FloatArray = det
    var bestCost: Float = Float.MAX_VALUE
    for (shift in 0 until 4) {
        val candidate = FloatArray(8) { j -> det[2 * ((j / 2 + shift) % 4) + j % 2] }
        val cost: Float = squaredCost(candidate, previous, aspect)
        if (cost < bestCost) {
            bestCost = cost
            best = candidate
        }
    }
    return best
}

/** Each corner moves [SMOOTHING_ALPHA] of the way from [previous] toward [target]. */
private fun smoothCorners(previous: FloatArray, target: FloatArray): FloatArray {
    return FloatArray(8) { i -> previous[i] + SMOOTHING_ALPHA * (target[i] - previous[i]) }
}

/** True when a corner is within [EDGE_MARGIN_SHARE] of an edge of the frame. */
private fun touchesFrameEdge(q: FloatArray): Boolean {
    for (i in 0 until 8) {
        val v: Float = q[i]
        if (v < EDGE_MARGIN_SHARE || v > 1f - EDGE_MARGIN_SHARE) return true
    }
    return false
}

/** Area of the quad as a share of the frame (shares are fractions of width and height, so the shoelace area is the share). */
private fun quadArea(q: FloatArray): Float {
    var sum = 0f
    for (i in 0 until 4) {
        val j: Int = (i + 1) % 4
        sum += q[2 * i] * q[2 * j + 1] - q[2 * j] * q[2 * i + 1]
    }
    return abs(sum) / 2f
}

/** Length of the side from corner [from] to corner [to], in frame heights. */
private fun sideLength(q: FloatArray, from: Int, to: Int, aspect: Float): Float {
    val dx: Float = (q[2 * to] - q[2 * from]) * aspect
    val dy: Float = q[2 * to + 1] - q[2 * from + 1]
    return sqrt(dx * dx + dy * dy)
}

/** True when a pair of opposite sides differs by more than [FLAT_SIDE_RATIO]. A degenerate side counts as skewed. */
private fun isSkewed(q: FloatArray, aspect: Float): Boolean {
    val top: Float = sideLength(q, 0, 1, aspect)
    val bottom: Float = sideLength(q, 3, 2, aspect)
    val left: Float = sideLength(q, 0, 3, aspect)
    val right: Float = sideLength(q, 1, 2, aspect)
    return ratioOfSides(top, bottom) > FLAT_SIDE_RATIO || ratioOfSides(left, right) > FLAT_SIDE_RATIO
}

/** The longer of two lengths over the shorter. A zero length gives a very large number. */
private fun ratioOfSides(x: Float, y: Float): Float {
    val shorter: Float = if (x < y) x else y
    val longer: Float = if (x < y) y else x
    if (shorter <= DEGENERATE_SIDE) return Float.MAX_VALUE
    return longer / shorter
}
