package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset

/**
 * Native eraser opt-in is required for the dedicated freehand highlighter.
 * Built-in text highlighting and Fill & Sign remain outside the Ink eraser target set.
 */
internal fun inkEraseCommand(page: Int, points: List<Offset>, radius: Double = 8.0): Map<String, Any> {
    require(page >= 0) { "Negative PDF page" }
    require(points.isNotEmpty() && points.size <= 512) { "Invalid eraser gesture" }
    require(radius.isFinite() && radius in 0.1..100.0) { "Invalid eraser radius" }
    require(points.all { it.x.isFinite() && it.y.isFinite() }) { "Non-finite eraser coordinates" }
    return mapOf(
        "page" to page + 1,
        "path" to points.map { listOf(it.x.toDouble(), it.y.toDouble()) },
        "radius" to radius,
        "erase_highlights" to true,
    )
}
