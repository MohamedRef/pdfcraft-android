package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset

/**
 * PDF Ink cannot contain a path with only one coordinate. A real pen tap is a dot,
 * represented as a tiny two-point round-capped path, not a vanished gesture.
 */
internal fun persistedInkTapPoints(points: List<Offset>): List<Offset> {
    if (points.size != 1) return points
    val point = points.first()
    if (!point.x.isFinite() || !point.y.isFinite()) return emptyList()
    return listOf(point, Offset(point.x + 0.01f, point.y + 0.01f))
}
