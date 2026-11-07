package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

/** Selection geometry lives in PDF page points, independent of zoom, pan and screen pixels. */
internal data class InkBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(point: Offset, margin: Float = 0f): Boolean =
        point.x.isFinite() && point.y.isFinite() && margin.isFinite() && margin >= 0f &&
            point.x >= left - margin && point.x <= right + margin &&
            point.y >= top - margin && point.y <= bottom + margin
}

internal fun inkSelectionBounds(paths: List<List<Offset>>): InkBounds? {
    val points = paths.asSequence().flatMap { it.asSequence() }.toList()
    if (points.isEmpty() || points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
    return InkBounds(
        points.minOf { it.x }, points.minOf { it.y },
        points.maxOf { it.x }, points.maxOf { it.y },
    )
}

/** Closed polygon lasso: include only complete strokes, never edit a partially enclosed stroke. */
internal fun lassoEnclosesStroke(polygon: List<Offset>, stroke: List<Offset>): Boolean {
    if (polygon.size < 3 || stroke.size < 2 || polygon.any { !it.x.isFinite() || !it.y.isFinite() }) return false
    val bounds = inkSelectionBounds(listOf(polygon)) ?: return false
    if ((bounds.right - bounds.left) * (bounds.bottom - bounds.top) < 1f) return false
    if (stroke.any { !it.x.isFinite() || !it.y.isFinite() }) return false
    return stroke.all { point ->
        if (!bounds.contains(point)) return@all false
        var inside = false
        var prev = polygon.last()
        for (next in polygon) {
            // Include points on the border; use Double to avoid unstable Float products.
            val cross = (point.x - prev.x).toDouble() * (next.y - prev.y) -
                (point.y - prev.y).toDouble() * (next.x - prev.x)
            val segmentLengthSq = (next.x - prev.x).toDouble() * (next.x - prev.x) +
                (next.y - prev.y).toDouble() * (next.y - prev.y)
            val along = (point.x - prev.x).toDouble() * (next.x - prev.x) +
                (point.y - prev.y).toDouble() * (next.y - prev.y)
            if (abs(cross) <= 1e-5 && along >= 0.0 && along <= segmentLengthSq) return@all true
            if ((prev.y > point.y) != (next.y > point.y)) {
                val crossing = (next.x - prev.x).toDouble() * (point.y - prev.y) /
                    (next.y - prev.y) + prev.x
                if (point.x < crossing) inside = !inside
            }
            prev = next
        }
        inside
    }
}

/** Reject invalid or extreme deltas rather than injecting NaN/Infinity into saved PDF Ink. */
internal fun translateInkPoints(points: List<Offset>, delta: Offset): List<Offset>? {
    if (!delta.x.isFinite() || !delta.y.isFinite() || abs(delta.x) > 10_000f || abs(delta.y) > 10_000f) return null
    val changed = points.map { it + delta }
    return changed.takeIf { it.all { p -> p.x.isFinite() && p.y.isFinite() } }
}
