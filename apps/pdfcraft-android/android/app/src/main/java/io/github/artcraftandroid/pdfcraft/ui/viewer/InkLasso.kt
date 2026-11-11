package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset

/** Lasso selection: uncommitted Draw strokes, and saved drawings and shapes ([savedLassoTarget]). */
internal const val MAX_INK_LASSO_POINTS = 128

internal data class InkLassoBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(p: Offset, tolerance: Float = 0f): Boolean =
        p.x in (left - tolerance)..(right + tolerance) && p.y in (top - tolerance)..(bottom + tolerance)

    fun overlaps(other: InkLassoBounds): Boolean =
        left <= other.right && other.left <= right && top <= other.bottom && other.top <= bottom
}

/** Reject invalid points before touching drawing state or running geometric comparisons. */
private fun valid(p: Offset) = p.x.isFinite() && p.y.isFinite()

internal fun inkLassoBounds(paths: Iterable<List<Offset>>): InkLassoBounds? {
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    for (points in paths) for (p in points) {
        if (!valid(p)) return null
        left = minOf(left, p.x)
        top = minOf(top, p.y)
        right = maxOf(right, p.x)
        bottom = maxOf(bottom, p.y)
    }
    return if (left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        InkLassoBounds(left, top, right, bottom) else null
}

/** Keep the interactive lasso bounded, while retaining both its start and newest endpoint. */
internal fun addInkLassoPoint(points: MutableList<Offset>, p: Offset) {
    if (!valid(p) || points.lastOrNull() == p) return
    if (points.size >= MAX_INK_LASSO_POINTS) {
        val last = points.lastIndex
        val reduced = points.filterIndexed { index, _ -> index % 2 == 0 || index == last }
        points.clear()
        points.addAll(reduced)
    }
    points.add(p)
}

private fun cross(a: Offset, b: Offset, c: Offset): Double =
    (b.x - a.x).toDouble() * (c.y - a.y) - (b.y - a.y).toDouble() * (c.x - a.x)

private fun onSegment(p: Offset, a: Offset, b: Offset): Boolean =
    kotlin.math.abs(cross(a, b, p)) <= 0.0001 &&
        p.x >= minOf(a.x, b.x) - 0.0001f && p.x <= maxOf(a.x, b.x) + 0.0001f &&
        p.y >= minOf(a.y, b.y) - 0.0001f && p.y <= maxOf(a.y, b.y) + 0.0001f

private fun segmentsMeet(a: Offset, b: Offset, c: Offset, d: Offset): Boolean {
    val abC = cross(a, b, c)
    val abD = cross(a, b, d)
    val cdA = cross(c, d, a)
    val cdB = cross(c, d, b)
    if (abC * abD < 0.0 && cdA * cdB < 0.0) return true
    return (abC == 0.0 && onSegment(c, a, b)) ||
        (abD == 0.0 && onSegment(d, a, b)) ||
        (cdA == 0.0 && onSegment(a, c, d)) ||
        (cdB == 0.0 && onSegment(b, c, d))
}

private fun insideLasso(p: Offset, polygon: List<Offset>): Boolean {
    var inside = false
    for (i in polygon.indices) {
        val a = polygon[i]
        val b = polygon[(i + 1) % polygon.size]
        if (onSegment(p, a, b)) return true
        if ((a.y > p.y) != (b.y > p.y)) {
            val atX = a.x + (b.x - a.x) * (p.y - a.y) / (b.y - a.y)
            if (p.x < atX) inside = !inside
        }
    }
    return inside
}

/** Includes a stroke that crosses the lasso even when neither endpoint is inside it. */
internal fun inkLassoHitsStroke(polygon: List<Offset>, stroke: List<Offset>): Boolean {
    if (polygon.size < 3 || stroke.size < 2) return false
    val loopBounds = inkLassoBounds(listOf(polygon)) ?: return false
    val pathBounds = inkLassoBounds(listOf(stroke)) ?: return false
    if (!loopBounds.overlaps(pathBounds)) return false
    if (stroke.any { insideLasso(it, polygon) }) return true
    for (i in 0 until stroke.lastIndex) {
        for (j in polygon.indices) {
            if (segmentsMeet(stroke[i], stroke[i + 1], polygon[j], polygon[(j + 1) % polygon.size]))
                return true
        }
    }
    return false
}

/** Clamp one rigid translation for the entire selected group, so it stays on its source page. */
internal fun boundedInkLassoMove(
    paths: Iterable<List<Offset>>,
    requested: Offset,
    pageWidth: Float,
    pageHeight: Float,
): Offset {
    return boundedInkLassoMove(inkLassoBounds(paths), requested, pageWidth, pageHeight)
}

/** [boundedInkLassoMove] for a selection whose bounds [b] are already known (#85: no per-frame copy). */
internal fun boundedInkLassoMove(
    b: InkLassoBounds?,
    requested: Offset,
    pageWidth: Float,
    pageHeight: Float,
): Offset {
    if (b == null) return Offset.Zero
    if (!valid(requested) || !pageWidth.isFinite() || !pageHeight.isFinite() || pageWidth <= 0f || pageHeight <= 0f)
        return Offset.Zero
    if (b.left < 0f || b.top < 0f || b.right > pageWidth || b.bottom > pageHeight) return Offset.Zero
    return Offset(
        requested.x.coerceIn(-b.left, pageWidth - b.right),
        requested.y.coerceIn(-b.top, pageHeight - b.bottom),
    )
}

/** Saved comments the lasso may select: drawings and shapes the user placed, not text markup or replies. */
private val LASSO_SUBTYPES = setOf("Ink", "Line", "Square", "Circle", "Polygon", "PolyLine", "FreeText", "Stamp")

/** Whether a saved comment can be lasso-selected (and moved, recoloured or deleted together). */
internal fun savedLassoTarget(subtype: String, locked: Boolean, fillSign: Boolean, replyTo: String?): Boolean =
    subtype in LASSO_SUBTYPES && !locked && !fillSign && replyTo == null

/** A saved comment's rectangle [x0, y0, x1, y1] (page points) as a closed outline the lasso can test. */
internal fun lassoRectOutline(rect: List<Float>): List<Offset>? {
    if (rect.size != 4 || rect.any { !it.isFinite() }) return null
    val l = minOf(rect[0], rect[2]); val r = maxOf(rect[0], rect[2])
    val t = minOf(rect[1], rect[3]); val b = maxOf(rect[1], rect[3])
    return listOf(Offset(l, t), Offset(r, t), Offset(r, b), Offset(l, b), Offset(l, t))
}

/** A lasso selects a saved comment when it crosses or contains its rectangle, or lies inside it. */
internal fun inkLassoHitsRect(polygon: List<Offset>, rect: List<Float>): Boolean {
    val outline = lassoRectOutline(rect) ?: return false
    if (inkLassoHitsStroke(polygon, outline)) return true
    val b = inkLassoBounds(listOf(outline)) ?: return false
    return polygon.size >= 3 && polygon.all { b.contains(it) }
}
