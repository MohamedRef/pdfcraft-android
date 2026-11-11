package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset

/** One connected piece of a stroke, with its per-point pressures. */
internal data class InkRun(val points: List<Offset>, val pressures: List<Float>)

/**
 * The parts of a stroke that lie on its page (`0..width` × `0..height`, in page points).
 *
 * Ink is drawn with unclamped coordinates, so a pen that leaves the page keeps its real path
 * instead of being pinned to the edge (#75). On lift the stroke is cut at the page box: each
 * crossing gets an exact edge point (pressure interpolated), the parts outside are dropped and a
 * stroke that leaves and comes back becomes separate runs, so no chord joins the exit and the
 * re-entry. A stroke that stays on the page is returned unchanged.
 */
internal fun clipInkToPage(points: List<Offset>, pressures: List<Float>, width: Float, height: Float): List<InkRun> {
    fun inside(p: Offset) = p.x in 0f..width && p.y in 0f..height
    if (points.all(::inside)) return listOf(InkRun(points, pressures))
    if (!(width > 0f && height > 0f) || points.size < 2) return emptyList()
    fun pressure(i: Int) = pressures.getOrElse(i) { pressures.lastOrNull() ?: 1f }
    val runs = mutableListOf<InkRun>()
    var runPoints = mutableListOf<Offset>()
    var runPressures = mutableListOf<Float>()
    fun flush() {
        // A run must have length: a segment that only grazes a corner leaves no dot.
        if (runPoints.size >= 2 && runPoints.any { it != runPoints.first() }) runs.add(InkRun(runPoints, runPressures))
        runPoints = mutableListOf()
        runPressures = mutableListOf()
    }
    for (i in 0 until points.lastIndex) {
        val a = points[i]
        val b = points[i + 1]
        val span = clipSegmentToBox(a, b, width, height)
        if (span == null) {
            flush()
            continue
        }
        val t0 = span.t0
        val t1 = span.t1
        // A crossing lies exactly on the edge it crosses (float rounding would leave it a hair off).
        fun onEdge(p: Offset, edge: Int) = when (edge) {
            0 -> Offset(0f, p.y.coerceIn(0f, height))
            1 -> Offset(width, p.y.coerceIn(0f, height))
            2 -> Offset(p.x.coerceIn(0f, width), 0f)
            else -> Offset(p.x.coerceIn(0f, width), height)
        }
        val start = if (t0 <= 0f) a else onEdge(a + (b - a) * t0, span.enter)
        val end = if (t1 >= 1f) b else onEdge(a + (b - a) * t1, span.exit)
        val fa = pressure(i)
        val fb = pressure(i + 1)
        if (runPoints.isNotEmpty() && runPoints.last() != start) flush()
        if (runPoints.isEmpty()) {
            runPoints.add(start)
            runPressures.add(fa + (fb - fa) * t0)
        }
        runPoints.add(end)
        runPressures.add(fa + (fb - fa) * t1)
        if (t1 < 1f) flush()
    }
    flush()
    return runs
}

/** The part of a segment inside the box: parameters, and the edges (0 left, 1 right, 2 top, 3 bottom) it enters and leaves by. */
private class Span(val t0: Float, val t1: Float, val enter: Int, val exit: Int)

/** Liang–Barsky: the parameter range of segment [a]–[b] inside the box, or null when it misses. */
private fun clipSegmentToBox(a: Offset, b: Offset, width: Float, height: Float): Span? {
    var t0 = 0f
    var t1 = 1f
    var enter = -1
    var exit = -1
    val dx = b.x - a.x
    val dy = b.y - a.y
    val p = floatArrayOf(-dx, dx, -dy, dy)
    val q = floatArrayOf(a.x, width - a.x, a.y, height - a.y)
    for (k in 0 until 4) {
        if (p[k] == 0f) {
            if (q[k] < 0f) return null
        } else {
            val r = q[k] / p[k]
            if (p[k] < 0f) {
                if (r > t1) return null
                if (r > t0) { t0 = r; enter = k }
            } else {
                if (r < t0) return null
                if (r < t1) { t1 = r; exit = k }
            }
        }
    }
    return Span(t0, t1, enter, exit)
}
