package io.github.artcraftandroid.pdfcraft.ui.scan

import kotlin.math.hypot

/*
 * The pull during a drag on Adjust borders (#514): while a corner or a side is held, the paper's
 * edges near it pull it on from a catch distance and let go of it past a larger release distance,
 * so it neither flickers nor traps the finger. Distances are in pixels of the view (the app turns
 * dp into them), so the pull feels the same at any zoom and on any photo. What can be worked out
 * without a phone is here, so it can be tested on the desktop.
 */

/** The catch distance, in dp, when the testing values do not say otherwise. */
internal const val PULL_CATCH_DP = 20

/** The release distance, in dp: larger than the catch, so a caught handle does not flicker. */
internal const val PULL_RELEASE_DP = 32

/** A handle caught by an edge: the corners it shows, the finger's place on the photo when it was caught (shares), and what caught it. */
internal class ScanPullCaught(val quad: FloatArray, val atX: Float, val atY: Float, val target: String, val distancePx: Float, val since: Long)

/** How far apart two points given in shares of the photo are on the view ([viewW] × [viewH] pixels). */
internal fun pullDistancePx(ax: Float, ay: Float, bx: Float, by: Float, viewW: Float, viewH: Float): Float =
    hypot((ax - bx) * viewW, (ay - by) * viewH)

/**
 * Whether the edge finder's answer [candidate] for handle [h] is taken while the finger holds the
 * handle where [free] has it: the handle moves no further than [catchPx] to get there, and it
 * moves at all (a candidate equal to [free] is no catch).
 */
internal fun pullCatches(free: FloatArray, candidate: FloatArray, h: QuadHandle, catchPx: Float, viewW: Float, viewH: Float): Float? {
    val (fx, fy) = handlePoint(free, h)
    val (cx, cy) = handlePoint(candidate, h)
    val d = pullDistancePx(fx, fy, cx, cy, viewW, viewH)
    // a side caught at one end only moves its middle a little: the farther corner says how far it went
    val far = (0..3).maxOf { k -> pullDistancePx(free[2 * k], free[2 * k + 1], candidate[2 * k], candidate[2 * k + 1], viewW, viewH) }
    if (far < 0.5f) return null
    return d.takeIf { it <= catchPx && far <= catchPx * 2f }
}

/** Whether the finger, now holding the handle where [free] has it, has gone past [releasePx] from where [caught] took it. */
internal fun pullLetsGo(free: FloatArray, caught: ScanPullCaught, h: QuadHandle, releasePx: Float, viewW: Float, viewH: Float): Boolean {
    val (fx, fy) = handlePoint(free, h)
    return pullDistancePx(fx, fy, caught.atX, caught.atY, viewW, viewH) > releasePx
}

/** What caught a handle: a corner held whose two sides were both found ends on their meeting point. */
internal fun pullTarget(h: QuadHandle, refined: List<Boolean>): String =
    if (h is QuadHandle.Corner && refined.getOrElse(h.index) { false } && refined.getOrElse((h.index + 3) % 4) { false }) "corner" else "edge"

/** The log's fields for a pull caught or let go (#514). */
internal fun pullDiag(`when`: String, target: String, distanceDp: Float, heldMs: Long, letGo: String, ms: Long): Array<Pair<String, Any?>> =
    arrayOf("when" to `when`, "target" to target, "distance_dp" to Math.round(distanceDp * 10f) / 10.0, "held_ms" to heldMs, "let_go" to letGo, "ms" to ms)

/**
 * One request to the edge finder during a drag: drag [serial], the corners where the finger holds
 * them ([free]), the handle held, the catch distance in view pixels, the search distance in photo
 * pixels, and the view's size. The latest request wins.
 */
internal class ScanPullAsk(val serial: Int, val free: FloatArray, val handle: QuadHandle, val catchPx: Float, val searchPx: Float, val viewW: Float, val viewH: Float)
