package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import kotlin.math.ceil
import kotlin.math.hypot

/** One piece of a stroke: page points and each point's width factor ([InkStroke.scales]). */
internal data class ErasedInkRun(val points: List<Offset>, val scales: List<Float>)

/** How far [p] is from the segment [a]–[b]. */
internal fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    val t = if (len2 > 0f) (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f) else 0f
    return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}

/**
 * The eraser moving from [a] to [b] (page points, [radius] wide on each side) over a draft
 * stroke that the engine does not have yet: the same cut as the engine's `comment_erase`
 * (`erase_ink`), reaching the stroke's painted edge ([halfWidths], each point's half width).
 * Segments the eraser can reach are cut where it crosses them; the rest keep their points.
 * Returns the pieces left (a piece needs two points), or null when the eraser misses the stroke.
 *
 * Draft ink is erased here, at once, instead of being saved first and erased by the engine:
 * on a big document each saved stroke took most of a second, so the first erase after
 * writing showed nothing for many seconds (0.0.33 log, 869 pages).
 */
internal fun eraseInkRun(points: List<Offset>, scales: List<Float>, halfWidths: List<Float>, a: Offset, b: Offset, radius: Float): List<ErasedInkRun>? {
    if (points.isEmpty() || points.size != scales.size || points.size != halfWidths.size) return null
    if (!(radius.isFinite() && radius > 0f) || !a.isFinite || !b.isFinite) return null
    val widest = halfWidths.maxOf { it.coerceAtLeast(0f) }
    val reachMax = radius + widest
    // a quick miss: the eraser's segment never comes near the stroke's box
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    for (p in points) { minX = minOf(minX, p.x); minY = minOf(minY, p.y); maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y) }
    if (minOf(a.x, b.x) > maxX + reachMax || maxOf(a.x, b.x) < minX - reachMax || minOf(a.y, b.y) > maxY + reachMax || maxOf(a.y, b.y) < minY - reachMax) return null

    fun reach(i: Int) = radius + halfWidths[i].coerceAtLeast(0f)
    val outPoints = ArrayList<Offset>(points.size)
    val outScales = ArrayList<Float>(points.size)
    val outHalf = ArrayList<Float>(points.size)
    if (points.size == 1) {
        outPoints.add(points[0]); outScales.add(scales[0]); outHalf.add(reach(0))
    }
    for (i in 0 until points.size - 1) {
        val p = points[i]
        val q = points[i + 1]
        val r = maxOf(reach(i), reach(i + 1))
        // a segment the eraser cannot reach keeps its own vertex
        val near = segmentDistance(p, q, a, b) <= r
        val n = if (!near) 1 else ceil(hypot(q.x - p.x, q.y - p.y) / (radius / 2f)).toInt().coerceIn(1, 2000)
        for (k in 0 until n) {
            val t = k.toFloat() / n
            outPoints.add(Offset(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t))
            outScales.add(scales[i] + (scales[i + 1] - scales[i]) * t)
            outHalf.add(reach(i) + (reach(i + 1) - reach(i)) * t)
        }
        if (i == points.size - 2) {
            outPoints.add(q); outScales.add(scales[i + 1]); outHalf.add(reach(i + 1))
        }
    }
    val gone = BooleanArray(outPoints.size) { distanceToSegment(outPoints[it], a, b) <= outHalf[it] }
    if (gone.none { it }) return null
    val runs = ArrayList<ErasedInkRun>()
    var start = -1
    for (i in 0..outPoints.size) {
        val keep = i < outPoints.size && !gone[i]
        if (keep && start < 0) start = i
        if (!keep && start >= 0) {
            if (i - start >= 2) runs.add(ErasedInkRun(outPoints.subList(start, i).toList(), outScales.subList(start, i).toList()))
            start = -1
        }
    }
    return runs
}

/** The shortest distance between the segments [p]–[q] and [a]–[b]. */
private fun segmentDistance(p: Offset, q: Offset, a: Offset, b: Offset): Float {
    if (segmentsCross(p, q, a, b)) return 0f
    return minOf(distanceToSegment(p, a, b), distanceToSegment(q, a, b), distanceToSegment(a, p, q), distanceToSegment(b, p, q))
}

private fun segmentsCross(p: Offset, q: Offset, a: Offset, b: Offset): Boolean {
    fun cross(o: Offset, u: Offset, v: Offset) = (u.x - o.x) * (v.y - o.y) - (u.y - o.y) * (v.x - o.x)
    val d1 = cross(a, b, p)
    val d2 = cross(a, b, q)
    val d3 = cross(p, q, a)
    val d4 = cross(p, q, b)
    return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) && ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
}

private val Offset.isFinite: Boolean get() = x.isFinite() && y.isFinite()
