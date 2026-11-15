package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot

/**
 * A stroke drawn by another ink engine (Jetpack Ink, the Pen lab trial) as filled [outlines]
 * (page points), turned back into what PdfCraft saves and edits: a centre line with a width per
 * point (`/PCWidths`), so the eraser, the lasso and the saved file treat it like any other pen
 * stroke.
 *
 * Each of [points] (the pen's path, page points) is moved across the stroke to the middle of the
 * outline there, and the outline's width across it is that point's width: the nearest outline
 * edge on either side, along the normal to the path. A point the outline does not surround on
 * both sides (a pointed end) keeps its place and the thinnest width. Each width is then the
 * median of it and its two neighbours, so where the stroke crosses itself one point does not
 * take the other line's edge. Widths are returned as multiples of [base] (the pen's width in
 * points), as [InkStroke.scales] holds them. Null when there is nothing to measure.
 */
internal fun tubeCentreline(points: List<Offset>, outlines: List<List<Offset>>, base: Float): Pair<List<Offset>, List<Float>>? {
    if (points.isEmpty() || !(base.isFinite() && base > 0f)) return null
    val edges = ArrayList<Pair<Offset, Offset>>()
    for (o in outlines) {
        if (o.size < 3) continue
        for (i in o.indices) edges.add(o[i] to o[(i + 1) % o.size])
    }
    if (edges.isEmpty()) return null
    val outPoints = ArrayList<Offset>(points.size)
    val widths = FloatArray(points.size)
    for (i in points.indices) {
        val p = points[i]
        val a = points[(i - 1).coerceAtLeast(0)]
        val b = points[(i + 1).coerceAtMost(points.size - 1)]
        var tx = b.x - a.x
        var ty = b.y - a.y
        var len = hypot(tx, ty)
        if (!(len > 1e-6f)) { tx = 1f; ty = 0f; len = 1f }
        val nx = -ty / len
        val ny = tx / len
        var plus = Float.POSITIVE_INFINITY
        var minus = Float.NEGATIVE_INFINITY
        for ((e0, e1) in edges) {
            val s = rayHit(p, nx, ny, e0, e1) ?: continue
            if (s >= 0f && s < plus) plus = s
            if (s <= 0f && s > minus) minus = s
        }
        if (plus.isFinite() && minus.isFinite() && plus - minus > 0f) {
            val mid = (plus + minus) / 2f
            outPoints.add(Offset(p.x + nx * mid, p.y + ny * mid))
            widths[i] = plus - minus
        } else {
            outPoints.add(p)
            widths[i] = Float.NaN
        }
    }
    val known = widths.filter { it.isFinite() }
    if (known.isEmpty()) return null
    val thinnest = known.min()
    for (i in widths.indices) if (!widths[i].isFinite()) widths[i] = thinnest
    val scales = List(widths.size) { i ->
        val w = floatArrayOf(widths[(i - 1).coerceAtLeast(0)], widths[i], widths[(i + 1).coerceAtMost(widths.size - 1)]).sorted()[1]
        (w / base).coerceIn(0.01f, 3f)
    }
    return outPoints to scales
}

/** Where the line through [p] along ([nx], [ny]) meets the segment [e0]–[e1], as a distance along it; null when it does not. */
private fun rayHit(p: Offset, nx: Float, ny: Float, e0: Offset, e1: Offset): Float? {
    val ex = e1.x - e0.x
    val ey = e1.y - e0.y
    val den = nx * ey - ny * ex
    if (kotlin.math.abs(den) < 1e-9f) return null
    val wx = e0.x - p.x
    val wy = e0.y - p.y
    val s = (wx * ey - wy * ex) / den
    val u = (wx * ny - wy * nx) / den
    return if (u in 0f..1f) s else null
}
