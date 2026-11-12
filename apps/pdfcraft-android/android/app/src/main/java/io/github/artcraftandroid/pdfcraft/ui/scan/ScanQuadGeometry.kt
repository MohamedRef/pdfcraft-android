package io.github.artcraftandroid.pdfcraft.ui.scan

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * The crop screen's geometry (#292), kept free of Android and Compose so the JVM tests can check
 * it. A quad is 8 shares (0–1) of the upright photo: x1, y1 … x4, y4, top-left, top-right,
 * bottom-right, bottom-left. Side i runs from corner i to corner i + 1, so the sides are top,
 * right, bottom and left: the order `scan_refine_quad` takes them in.
 *
 * Shares are not square: a photo is wider or taller than it is high or wide. Wherever a direction
 * matters (a side's normal, a distance) the work is done in photo proportions, [aspect] being the
 * photo's width over its height.
 */

/** What a finger or the keyboard holds: one of the four corners, or the middle of one side. */
internal sealed interface QuadHandle {
    val index: Int

    data class Corner(override val index: Int) : QuadHandle

    data class Side(override val index: Int) : QuadHandle

    companion object {
        /** Every handle, in the order Tab goes through them: corners and sides round the page. */
        val ALL: List<QuadHandle> = (0..3).flatMap { listOf(Corner(it), Side(it)) }
    }
}

/** Where the photo is drawn: its left and top edge in view pixels, and its drawn size. */
internal data class QuadFit(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun x(share: Float) = left + share * width
    fun y(share: Float) = top + share * height
}

/** The corners can be anywhere on the photo, never off it: the page function refuses those. */
internal const val QUAD_MIN = 0f
internal const val QUAD_MAX = 1f

/** The share point of [h] on [q]: the corner itself, or the middle of the side. */
internal fun handlePoint(q: FloatArray, h: QuadHandle): Pair<Float, Float> = when (h) {
    is QuadHandle.Corner -> q[2 * h.index] to q[2 * h.index + 1]
    is QuadHandle.Side -> {
        val j = (h.index + 1) % 4
        (q[2 * h.index] + q[2 * j]) / 2f to (q[2 * h.index + 1] + q[2 * j + 1]) / 2f
    }
}

/**
 * The handle under ([x], [y]) in view pixels: the nearest corner within [cornerRadius], else the
 * nearest side's middle within [sideRadius]. Corners come first, so a small page's corners stay
 * reachable however close its sides' middles are.
 */
internal fun hitTest(x: Float, y: Float, q: FloatArray, fit: QuadFit, cornerRadius: Float, sideRadius: Float): QuadHandle? {
    fun nearest(handles: List<QuadHandle>, radius: Float): QuadHandle? = handles
        .map { h -> h to handlePoint(q, h).let { (sx, sy) -> hypot(fit.x(sx) - x, fit.y(sy) - y) } }
        .filter { it.second <= radius }
        .minByOrNull { it.second }?.first
    return nearest((0..3).map { QuadHandle.Corner(it) }, cornerRadius)
        ?: nearest((0..3).map { QuadHandle.Side(it) }, sideRadius)
}

/** Whether [q] is a convex quadrilateral, its corners going round one way. */
internal fun convexQuad(q: FloatArray): Boolean {
    var sign = 0
    for (i in 0 until 4) {
        val (ax, ay) = q[2 * i] to q[2 * i + 1]
        val (bx, by) = q[2 * ((i + 1) % 4)] to q[2 * ((i + 1) % 4) + 1]
        val (cx, cy) = q[2 * ((i + 2) % 4)] to q[2 * ((i + 2) % 4) + 1]
        val cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
        val s = if (cross > 1e-6f) 1 else if (cross < -1e-6f) -1 else return false
        if (sign != 0 && s != sign) return false
        sign = s
    }
    return true
}

/** Which way the corners go round: 1 or -1 by the sign of the area, 0 for none. */
private fun turn(q: FloatArray): Int {
    var area = 0.0
    for (i in 0 until 4) {
        val j = (i + 1) % 4
        area += q[2 * i].toDouble() * q[2 * j + 1] - q[2 * j].toDouble() * q[2 * i + 1]
    }
    return if (area > 1e-12) 1 else if (area < -1e-12) -1 else 0
}

/** Convex and going round the same way as [before]: a side pushed past the one across is not a page. */
private fun keepsShape(q: FloatArray, before: FloatArray) = convexQuad(q) && turn(q) == turn(before)

private fun onPhoto(q: FloatArray) = q.all { it.isFinite() && it >= QUAD_MIN - 1e-5f && it <= QUAD_MAX + 1e-5f }

/** Corner [i] moved by ([dx], [dy]) shares and kept on the photo; null when the shape stops being convex. */
internal fun moveCorner(q: FloatArray, i: Int, dx: Float, dy: Float): FloatArray? {
    val out = q.copyOf()
    out[2 * i] = (q[2 * i] + dx).coerceIn(QUAD_MIN, QUAD_MAX)
    out[2 * i + 1] = (q[2 * i + 1] + dy).coerceIn(QUAD_MIN, QUAD_MAX)
    return out.takeIf { keepsShape(it, q) }
}

/**
 * Side [i] moved parallel to itself by the part of ([dx], [dy]) shares across it. Its two corners
 * slide along the neighbouring sides' lines, so those sides keep their direction. Null when the
 * shape stops being convex or a corner would leave the photo.
 */
internal fun moveSide(q: FloatArray, i: Int, dx: Float, dy: Float, aspect: Float): FloatArray? {
    val a = aspect.toDouble()
    // photo proportions: x in heights
    fun px(k: Int) = q[2 * k] * a
    fun py(k: Int) = q[2 * k + 1].toDouble()
    val j = (i + 1) % 4
    val prev = (i + 3) % 4
    val next = (i + 2) % 4
    val sx = px(j) - px(i)
    val sy = py(j) - py(i)
    val len = hypot(sx, sy).takeIf { it > 1e-9 } ?: return null
    val nx = -sy / len
    val ny = sx / len
    val shift = dx * a * nx + dy * ny
    // a point on the moved side
    val ox = px(i) + nx * shift
    val oy = py(i) + ny * shift
    // where the moved side's line meets the line from corner [from] through corner [to]
    fun meet(from: Int, to: Int): Pair<Double, Double>? {
        val ux = px(to) - px(from)
        val uy = py(to) - py(from)
        // ox + t*sx = px(from) + s*ux, oy + t*sy = py(from) + s*uy
        val det = sx * (-uy) - sy * (-ux)
        if (abs(det) < 1e-9) return null
        val rx = px(from) - ox
        val ry = py(from) - oy
        val t = (rx * (-uy) - ry * (-ux)) / det
        return ox + t * sx to oy + t * sy
    }
    val ci = meet(prev, i) ?: return null
    val cj = meet(next, j) ?: return null
    val out = q.copyOf()
    out[2 * i] = (ci.first / a).toFloat()
    out[2 * i + 1] = ci.second.toFloat()
    out[2 * j] = (cj.first / a).toFloat()
    out[2 * j + 1] = cj.second.toFloat()
    if (!onPhoto(out)) return null
    for (k in out.indices) out[k] = out[k].coerceIn(QUAD_MIN, QUAD_MAX)
    return out.takeIf { keepsShape(it, q) }
}

/** [h] moved by ([dx], [dy]) shares, or null where [moveCorner] or [moveSide] refuse. */
internal fun moveHandle(q: FloatArray, h: QuadHandle, dx: Float, dy: Float, aspect: Float): FloatArray? = when (h) {
    is QuadHandle.Corner -> moveCorner(q, h.index, dx, dy)
    is QuadHandle.Side -> moveSide(q, h.index, dx, dy, aspect)
}

/**
 * [h] moved by ([dx], [dy]) shares, or as far towards that as the shape allows: a fast drag into
 * the photo's edge or into a fold of the shape stops at the last good place instead of where the
 * finger was a frame before. [q] itself when nothing of the move is allowed.
 */
internal fun moveTowards(q: FloatArray, h: QuadHandle, dx: Float, dy: Float, aspect: Float): FloatArray {
    moveHandle(q, h, dx, dy, aspect)?.let { return it }
    var good = 0f
    var bad = 1f
    var best = q
    repeat(16) {
        val t = (good + bad) / 2f
        val moved = moveHandle(q, h, dx * t, dy * t, aspect)
        if (moved != null) {
            good = t
            best = moved
        } else {
            bad = t
        }
    }
    return best
}

/** A small step for keyboard and accessibility moves: 0.25 % of the photo. */
internal const val NUDGE_SHARE = 0.0025f

/** A large one, with Shift: 1 % of the photo. */
internal const val NUDGE_SHARE_LARGE = 0.01f

/**
 * [h] moved by ([stepsX], [stepsY]) steps of [step] across the photo. Steps are the same distance
 * across and down: in shares, a horizontal step is shorter on a wide photo.
 */
internal fun nudge(q: FloatArray, h: QuadHandle, stepsX: Int, stepsY: Int, step: Float, aspect: Float): FloatArray {
    // the step is a share of the photo's longer side, so it looks the same both ways
    val unit = step * max(aspect, 1f)
    return moveTowards(q, h, stepsX * unit / aspect, stepsY * unit, aspect)
}

/** Which sides (top, right, bottom, left) a release of [h] asks the edge finder to refine. */
internal fun sidesToSnap(h: QuadHandle): List<Boolean> = List(4) { s ->
    when (h) {
        is QuadHandle.Corner -> s == h.index || s == (h.index + 3) % 4
        is QuadHandle.Side -> s == h.index
    }
}

/** A handle held this long without moving before it is let go is not snapped. */
internal const val SNAP_HOLD_MS = 600L

/** How far either way of a side the edge finder looks, as a share of the photo's diagonal. */
internal const val SNAP_SEARCH_SHARE = 0.012f

/** Whether letting go of a handle, still for [stillMs], snaps it: only with Snap on and not held still. */
internal fun snapOnRelease(snapOn: Boolean, stillMs: Long): Boolean = snapOn && stillMs < SNAP_HOLD_MS

/**
 * What a snap gives back, applied: the edge finder's corners when one of the sides asked for
 * was refined and the shape is still convex and on the photo, else null (the handle stays where
 * it was put).
 */
internal fun snapped(asked: List<Boolean>, refined: List<Boolean>, corners: FloatArray): FloatArray? {
    if (corners.size != 8 || asked.indices.none { asked[it] && refined.getOrElse(it) { false } }) return null
    return corners.takeIf { onPhoto(it) && convexQuad(it) }?.map { it.coerceIn(QUAD_MIN, QUAD_MAX) }?.toFloatArray()
}

/**
 * Where the magnifier goes for a handle at ([hx], [hy]) in a [viewW] × [viewH] view: its top-left
 * corner, for a square of [size]. Above the finger first, then below, left and right; always
 * inside the view, and at least [gap] from the handle so the finger and the point under it stay
 * visible. Near no edge it is centred over the handle.
 */
internal fun loupePlacement(hx: Float, hy: Float, viewW: Float, viewH: Float, size: Float, gap: Float): Pair<Float, Float> {
    fun clamp(x: Float, y: Float) = x.coerceIn(0f, max(0f, viewW - size)) to y.coerceIn(0f, max(0f, viewH - size))
    fun clear(p: Pair<Float, Float>): Boolean {
        val (x, y) = p
        val nx = hx.coerceIn(x, x + size)
        val ny = hy.coerceIn(y, y + size)
        return hypot(nx - hx, ny - hy) >= gap - 1e-3f
    }
    val tries = listOf(
        clamp(hx - size / 2, hy - gap - size), // above
        clamp(hx - size / 2, hy + gap), // below
        clamp(hx - gap - size, hy - size / 2), // left
        clamp(hx + gap, hy - size / 2), // right
    )
    tries.firstOrNull { clear(it) }?.let { return it }
    // a view too small for any of those: the corner of the view farthest from the handle
    return clamp(if (hx < viewW / 2) viewW - size else 0f, if (hy < viewH / 2) viewH - size else 0f)
}

/**
 * Where a share point of the upright photo is in the camera file as stored, for the EXIF
 * orientations `rotateForExif` turns (6: turn 90° clockwise to stand it up, 3: 180°, 8: 270°).
 * Any other orientation is taken as stored upright, as `rotateForExif` takes it.
 */
internal fun uprightToStored(x: Float, y: Float, exif: Int): Pair<Float, Float> = when (exif) {
    6 -> y to 1f - x
    3 -> 1f - x to 1f - y
    8 -> 1f - y to x
    else -> x to y
}

/** The clockwise turn, in degrees, that stands a stored photo with EXIF orientation [exif] up. */
internal fun exifDegrees(exif: Int): Int = when (exif) {
    6 -> 90
    3 -> 180
    8 -> 270
    else -> 0
}

/** A rectangle of photo pixels: left and top inclusive, right and bottom exclusive. */
internal data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * The part of the stored camera file to decode for a magnifier centred on the upright share point
 * ([x], [y]): a square of about [side] stored pixels, kept inside the [storedW] × [storedH] file.
 * Null for an empty file.
 */
internal fun loupeRegion(x: Float, y: Float, exif: Int, storedW: Int, storedH: Int, side: Int): PixelRect? {
    if (storedW <= 0 || storedH <= 0 || side <= 0) return null
    val (sx, sy) = uprightToStored(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), exif)
    val w = min(side, storedW)
    val h = min(side, storedH)
    val l = (sx * storedW - w / 2f).toInt().coerceIn(0, storedW - w)
    val t = (sy * storedH - h / 2f).toInt().coerceIn(0, storedH - h)
    return PixelRect(l, t, l + w, t + h)
}

/**
 * Where [r], a rectangle of the stored file, is in the upright photo once turned as [exif] says:
 * the rectangle the decoded and turned region covers, in upright pixels.
 */
internal fun storedToUprightRect(r: PixelRect, exif: Int, storedW: Int, storedH: Int): PixelRect = when (exif) {
    6 -> PixelRect(storedH - r.bottom, r.left, storedH - r.top, r.right)
    3 -> PixelRect(storedW - r.right, storedH - r.bottom, storedW - r.left, storedH - r.top)
    8 -> PixelRect(r.top, storedW - r.right, r.bottom, storedW - r.left)
    else -> r
}

/**
 * Whether a magnifier region decoded around ([x0], [y0]) is still good for the handle at ([x], [y]),
 * all upright pixels: until the handle has moved a quarter of the region's [side].
 */
internal fun loupeRegionFresh(x0: Float, y0: Float, x: Float, y: Float, side: Int): Boolean =
    abs(x - x0) <= side / 4f && abs(y - y0) <= side / 4f
