package io.github.artcraftandroid.pdfcraft.ui.scan

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * The live page outline's decisions (#295), pure so they are tested on the JVM: copying a camera
 * frame, placing the outline on the viewfinder, smoothing it from frame to frame, deciding when
 * it is steady, and the one line of advice under the viewfinder.
 */

/**
 * Copy an RGBA frame of [width] × [height] whose rows are [rowStride] bytes apart in [src] into
 * [dst] packed (width × 4 bytes a row). Returns false, copying nothing, when the sizes do not fit.
 * [src]'s position is left as it was.
 */
internal fun scanCopyRgbaRows(src: ByteBuffer, rowStride: Int, width: Int, height: Int, dst: ByteArray): Boolean {
    val row = width * 4
    if (width <= 0 || height <= 0 || rowStride < row || dst.size < row * height) return false
    val needed = rowStride.toLong() * (height - 1) + row
    if (src.remaining() < needed) return false
    val start = src.position()
    if (rowStride == row) {
        src.duplicate().get(dst, 0, row * height)
    } else {
        val view = src.duplicate()
        for (y in 0 until height) {
            view.position(start + y * rowStride)
            view.get(dst, y * row, row)
        }
    }
    return true
}

/** A point on the viewfinder, in pixels. */
internal data class ViewPoint(val x: Float, val y: Float)

/**
 * Where a point given as shares of the upright frame lands on a viewfinder of [viewWidth] ×
 * [viewHeight] pixels that shows the whole frame (aspect [frameAspect] = width / height),
 * letterboxed and centred.
 */
internal fun scanFrameShareToView(shareX: Float, shareY: Float, frameAspect: Float, viewWidth: Float, viewHeight: Float): ViewPoint {
    if (!(frameAspect > 0f) || viewWidth <= 0f || viewHeight <= 0f) return ViewPoint(0f, 0f)
    val viewAspect = viewWidth / viewHeight
    val (w, h) = if (viewAspect > frameAspect) viewHeight * frameAspect to viewHeight else viewWidth to viewWidth / frameAspect
    val left = (viewWidth - w) / 2f
    val top = (viewHeight - h) / 2f
    return ViewPoint(left + shareX.coerceIn(0f, 1f) * w, top + shareY.coerceIn(0f, 1f) * h)
}

/** One answer of the frame detector, its points in shares of the upright frame. */
internal data class ScanLiveDetection(val points: FloatArray, val confidence: Float, val meanLuma: Float, val found: Boolean, val cutOff: Boolean, val at: Long)

/** The outline shown, and what the tracker makes of it. */
internal data class ScanLiveOutline(
    /** 8 shares of the upright frame (top-left, top-right, bottom-right, bottom-left), or null when none is shown. */
    val points: FloatArray?,
    /** The outline has held still long enough to shoot. */
    val stable: Boolean,
    /** Whether the outline is fading out (no page seen for a while). */
    val fading: Boolean,
)

/**
 * Smooths the outline between frames and decides when it is steady (#295, section 3). Each corner
 * moves [ALPHA] of the way to the new detection; a jump of more than [JUMP_SHARE] of the frame's
 * diagonal, or an outline older than [STALE_MS], is taken at once (a new page, not jitter).
 * Without a detection the last outline is kept for [DROPOUT_MS], then fades. Steady when the last
 * [STABLE_COUNT] detections, over at least [STABLE_MS], were sure ([STABLE_CONFIDENCE]) and no
 * corner moved more than [STABLE_MOVE_SHARE] of the diagonal between two of them. Corners keep
 * their order between frames even when the detector names them from another corner.
 */
internal class ScanLiveTracker(private val frameAspect: Float = 3f / 4f) {
    private var smoothed: FloatArray? = null
    private var lastSeen = Long.MIN_VALUE
    private val history = ArrayDeque<Pair<Long, FloatArray>>()
    private val sure = ArrayDeque<Boolean>()

    /** Time of the last detection that found the page, or null. */
    val lastFoundAt: Long? get() = lastSeen.takeIf { it != Long.MIN_VALUE }

    fun reset() {
        smoothed = null
        lastSeen = Long.MIN_VALUE
        history.clear()
        sure.clear()
    }

    fun update(d: ScanLiveDetection): ScanLiveOutline {
        if (!d.found || d.points.size != 8) return at(d.at)
        val prev = smoothed
        val fresh = if (prev == null) d.points.copyOf() else reorder(prev, d.points)
        val next = when {
            prev == null || d.at - lastSeen > STALE_MS || maxMove(prev, fresh) > JUMP_SHARE -> {
                history.clear()
                sure.clear()
                fresh
            }
            else -> FloatArray(8) { i -> prev[i] + ALPHA * (fresh[i] - prev[i]) }
        }
        smoothed = next
        lastSeen = d.at
        history.addLast(d.at to fresh)
        sure.addLast(d.confidence >= STABLE_CONFIDENCE)
        while (history.size > STABLE_COUNT) {
            history.removeFirst()
            sure.removeFirst()
        }
        return ScanLiveOutline(next, steady(), fading = false)
    }

    /** The outline at [now] with no new detection. */
    fun at(now: Long): ScanLiveOutline {
        val s = smoothed ?: return ScanLiveOutline(null, stable = false, fading = false)
        val since = now - lastSeen
        return when {
            since <= DROPOUT_MS -> ScanLiveOutline(s, stable = false, fading = false)
            since <= DROPOUT_MS + FADE_MS -> ScanLiveOutline(s, stable = false, fading = true)
            else -> {
                reset()
                ScanLiveOutline(null, stable = false, fading = false)
            }
        }
    }

    private fun steady(): Boolean {
        if (history.size < STABLE_COUNT || sure.any { !it }) return false
        if (history.last().first - history.first().first < STABLE_MS) return false
        for (i in 1 until history.size) if (maxMove(history.elementAt(i - 1).second, history.elementAt(i).second) > STABLE_MOVE_SHARE) return false
        return true
    }

    /** The largest corner move between [a] and [b], in shares of the frame's diagonal. */
    private fun maxMove(a: FloatArray, b: FloatArray): Float {
        val diag = hypot(frameAspect, 1f)
        var m = 0f
        for (i in 0 until 4) m = max(m, hypot((a[2 * i] - b[2 * i]) * frameAspect, a[2 * i + 1] - b[2 * i + 1]) / diag)
        return m
    }

    /** [points] with its corners renamed (turned by whole corners) to be closest to [prev]. */
    private fun reorder(prev: FloatArray, points: FloatArray): FloatArray {
        var best = points
        var bestCost = Float.MAX_VALUE
        for (k in 0 until 4) {
            val turned = FloatArray(8) { i -> points[(2 * ((i / 2 + k) % 4)) + i % 2] }
            var cost = 0f
            for (c in 0 until 4) cost += hypot((turned[2 * c] - prev[2 * c]) * frameAspect, turned[2 * c + 1] - prev[2 * c + 1])
            if (cost < bestCost) {
                bestCost = cost
                best = turned
            }
        }
        return best
    }

    companion object {
        const val ALPHA = 0.55f
        const val JUMP_SHARE = 0.15f
        const val STALE_MS = 500L
        const val DROPOUT_MS = 350L
        const val FADE_MS = 250L
        const val STABLE_COUNT = 6
        const val STABLE_MS = 400L
        const val STABLE_CONFIDENCE = 0.6f
        const val STABLE_MOVE_SHARE = 0.012f
    }
}

/** The advice under the viewfinder, most important first (#295, section 4). */
internal enum class ScanLiveHint(val id: String) {
    PointAtDocument("point"),
    MoreLight("light"),
    FitPage("fit"),
    MoveCloser("closer"),
    HoldFlatter("flatter"),
    HoldStill("still"),
    Ready("ready"),
}

/** What the hint is chosen from. */
internal data class ScanLiveScene(
    /** The outline shown (8 shares), or null. */
    val points: FloatArray?,
    val stable: Boolean,
    val meanLuma: Float,
    val cutOff: Boolean,
    /** How long no page has been seen, in milliseconds (0 while one is). */
    val missingMs: Long,
)

/** The thresholds of the hints: the issue's starting values, to be set on the owner's phone. */
internal object ScanLiveHintRules {
    const val MISSING_MS = 1_500L
    const val DARK_LUMA = 55f
    const val EDGE_SHARE = 0.015f
    const val MIN_AREA_SHARE = 0.22f
    const val MAX_SIDE_RATIO = 1.7f
    const val HOLD_MS = 400L
}

/** The hint the scene calls for, ignoring how long the current one has shown; null while there is nothing to say yet. */
internal fun scanLiveHintFor(scene: ScanLiveScene, frameAspect: Float = 3f / 4f): ScanLiveHint? {
    val p = scene.points
    if (p == null || p.size != 8) return if (scene.missingMs >= ScanLiveHintRules.MISSING_MS) ScanLiveHint.PointAtDocument else null
    if (scene.meanLuma < ScanLiveHintRules.DARK_LUMA) return ScanLiveHint.MoreLight
    val e = ScanLiveHintRules.EDGE_SHARE
    if (scene.cutOff || (0 until 4).any { i -> p[2 * i] < e || p[2 * i] > 1 - e || p[2 * i + 1] < e || p[2 * i + 1] > 1 - e }) return ScanLiveHint.FitPage
    if (quadArea(p, frameAspect) / frameAspect < ScanLiveHintRules.MIN_AREA_SHARE) return ScanLiveHint.MoveCloser
    if (sideRatio(p, frameAspect) > ScanLiveHintRules.MAX_SIDE_RATIO) return ScanLiveHint.HoldFlatter
    return if (scene.stable) ScanLiveHint.Ready else ScanLiveHint.HoldStill
}

/**
 * Keeps the hint from flickering: a new hint replaces the one shown only after it has been the
 * one called for during [ScanLiveHintRules.HOLD_MS].
 */
internal class ScanLiveHintHolder {
    var shown: ScanLiveHint? = null
        private set
    private var candidate: ScanLiveHint? = null
    private var since = 0L

    /** The hint to show at [now] when the scene calls for [wanted]. */
    fun update(wanted: ScanLiveHint?, now: Long): ScanLiveHint? {
        if (wanted == shown) {
            candidate = null
            return shown
        }
        if (wanted != candidate) {
            candidate = wanted
            since = now
        }
        if (now - since >= ScanLiveHintRules.HOLD_MS) {
            shown = wanted
            candidate = null
        }
        return shown
    }
}

/** The quad's area with x scaled by [aspect], so the frame's own area is [aspect] (shoelace). */
private fun quadArea(p: FloatArray, aspect: Float): Float {
    var s = 0f
    for (i in 0 until 4) {
        val j = (i + 1) % 4
        s += p[2 * i] * aspect * p[2 * j + 1] - p[2 * j] * aspect * p[2 * i + 1]
    }
    return abs(s) / 2f
}

/** The larger of the two ratios of opposite sides (longer to shorter). */
private fun sideRatio(p: FloatArray, aspect: Float): Float {
    fun side(i: Int): Float {
        val j = (i + 1) % 4
        return hypot((p[2 * j] - p[2 * i]) * aspect, p[2 * j + 1] - p[2 * i + 1])
    }
    fun ratio(a: Float, b: Float): Float = if (min(a, b) <= 0f) Float.MAX_VALUE else max(a, b) / min(a, b)
    return max(ratio(side(0), side(2)), ratio(side(1), side(3)))
}
