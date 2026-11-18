package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
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
 * Each segment the eraser reaches is cut exactly where the eraser's circle meets that edge
 * ([erasedSpan]); the rest keep their points, and the only points added are the two at the cut.
 * Returns the pieces left (a piece needs two points and [MIN_ERASED_PIECE_PT] of length), or
 * null when the eraser misses the stroke.
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
    // a dot goes when the eraser reaches it
    if (points.size == 1) return if (distanceToSegment(points[0], a, b) <= reach(0)) emptyList() else null
    // the erased span of each segment the eraser reaches, from 0 at its start to 1 at its end
    val spans = arrayOfNulls<DoubleArray>(points.size - 1)
    var touched = false
    for (i in 0 until points.size - 1) {
        val r0 = reach(i)
        val r1 = reach(i + 1)
        if (segmentDistance(points[i], points[i + 1], a, b) > maxOf(r0, r1)) continue
        spans[i] = erasedSpan(points[i], points[i + 1], r0, r1, a, b) ?: continue
        touched = true
    }
    if (!touched) return null

    val runs = ArrayList<ErasedInkRun>()
    var runPoints = ArrayList<Offset>()
    var runScales = ArrayList<Float>()
    fun close() {
        var length = 0f
        for (k in 1 until runPoints.size) length += (runPoints[k] - runPoints[k - 1]).getDistance()
        if (runPoints.size >= 2 && length >= MIN_ERASED_PIECE_PT) runs.add(ErasedInkRun(runPoints, runScales))
        runPoints = ArrayList()
        runScales = ArrayList()
    }
    fun addAt(i: Int, t: Double) {
        val p = points[i]
        val q = points[i + 1]
        runPoints.add(Offset((p.x + (q.x - p.x) * t).toFloat(), (p.y + (q.y - p.y) * t).toFloat()))
        runScales.add((scales[i] + (scales[i + 1] - scales[i]) * t).toFloat())
    }
    // the first point starts a piece unless the eraser takes it
    if (spans[0]?.let { it[0] > 0.0 } != false) {
        runPoints.add(points[0])
        runScales.add(scales[0])
    }
    for (i in 0 until points.size - 1) {
        val span = spans[i]
        if (span != null) {
            if (span[0] > 0.0) addAt(i, span[0])
            close()
            if (span[1] < 1.0) addAt(i, span[1])
        }
        // the segment's end point stays unless the eraser's span reaches it
        if (span == null || span[1] < 1.0) {
            runPoints.add(points[i + 1])
            runScales.add(scales[i + 1])
        }
    }
    close()
    return runs
}

/** A piece the eraser leaves shorter than this (page points) is dropped: no crumb drawn as a dot of the stroke's width. */
internal const val MIN_ERASED_PIECE_PT = 0.05f

/** Search steps for an exact cut: about 1e-10 of the segment for its lowest point, 4e-15 for its ends. */
private const val ERASE_SEARCH_STEPS = 48

/**
 * The eraser cuts a segment only when it reaches this far (page points) into it. A piece the eraser
 * just cut ends on its reach, and the eraser's next step starts where this one ended: without it
 * that step would cut the piece again at the same place, every move.
 */
private const val ERASE_TOUCH_PT = 1e-3

/**
 * The part of the segment [p]–[q] (`[t0, t1]`, 0 at [p], 1 at [q]) within the eraser's reach of its
 * segment [a]–[b], the reach going from [r0] at [p] to [r1] at [q]; null when the eraser misses it.
 * The distance from a point moving along [p]–[q] to [a]–[b] is convex in `t`, and so is that
 * distance less the reach (which changes linearly), so the part is one span: its lowest point is
 * found by golden-section search and each end by bisection. The ends lie just outside the reach,
 * so a piece left by the cut has no point under the eraser. The engine's `erased_span` is the same.
 */
internal fun erasedSpan(p: Offset, q: Offset, r0: Float, r1: Float, a: Offset, b: Offset): DoubleArray? {
    val px = p.x.toDouble(); val py = p.y.toDouble(); val qx = q.x.toDouble(); val qy = q.y.toDouble()
    val ax = a.x.toDouble(); val ay = a.y.toDouble(); val bx = b.x.toDouble(); val by = b.y.toDouble()
    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
    fun gap(t: Double): Double {
        val x = if (t <= 0.0) px else if (t >= 1.0) qx else px + (qx - px) * t
        val y = if (t <= 0.0) py else if (t >= 1.0) qy else py + (qy - py) * t
        val u = if (len2 > 0.0) (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0.0, 1.0) else 0.0
        return hypot(x - (ax + u * dx), y - (ay + u * dy)) - (r0 + (r1 - r0) * t)
    }
    val golden = 0.6180339887498949
    var lo = 0.0
    var hi = 1.0
    var x1 = hi - golden * (hi - lo)
    var x2 = lo + golden * (hi - lo)
    var g1 = gap(x1)
    var g2 = gap(x2)
    repeat(ERASE_SEARCH_STEPS) {
        if (g1 <= g2) {
            hi = x2; x2 = x1; g2 = g1
            x1 = hi - golden * (hi - lo); g1 = gap(x1)
        } else {
            lo = x1; x1 = x2; g1 = g2
            x2 = lo + golden * (hi - lo); g2 = gap(x2)
        }
    }
    val g0 = gap(0.0)
    val gEnd = gap(1.0)
    var deepT = if (g1 <= g2) x1 else x2
    var deepG = if (g1 <= g2) g1 else g2
    if (g0 < deepG) { deepT = 0.0; deepG = g0 }
    if (gEnd < deepG) { deepT = 1.0; deepG = gEnd }
    // a bad number (NaN) reaches nothing; an eraser that only touches the reach leaves the segment
    if (deepG.isNaN() || deepG > -ERASE_TOUCH_PT) return null
    fun edge(outsideStart: Double, insideStart: Double): Double {
        var outside = outsideStart
        var inside = insideStart
        repeat(ERASE_SEARCH_STEPS) {
            val m = (outside + inside) / 2.0
            if (gap(m) <= 0.0) inside = m else outside = m
        }
        return outside
    }
    val t0 = if (g0 <= 0.0) 0.0 else edge(0.0, deepT)
    val t1 = if (gEnd <= 0.0) 1.0 else edge(1.0, deepT)
    return doubleArrayOf(t0, t1)
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
