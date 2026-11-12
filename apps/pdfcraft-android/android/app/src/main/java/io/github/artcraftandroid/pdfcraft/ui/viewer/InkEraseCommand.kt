package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset

/**
 * Native eraser opt-in is required for the dedicated freehand highlighter.
 * Built-in text highlighting and Fill & Sign remain outside the Ink eraser target set.
 */
/** Points in one `comment_erase` path; the engine accepts up to this many (crates/automation). */
internal const val MAX_ERASER_PATH_POINTS = 4096

/**
 * Adds eraser point [pt] to the connected path [points] of a gesture with eraser [radius].
 *
 * The path is never thinned by dropping points: the engine joins consecutive points with
 * segments, so a dropped point would turn a scrub into chords that cut ink the eraser never
 * touched (#76). A point closer than an eighth of the radius to the last one is skipped (it moves
 * the erased area by less than that). When the path is full, the full chunk is returned for the
 * caller to erase, and [points] continues from the chunk's last point so the path stays connected.
 */
internal fun appendEraserPoint(points: MutableList<Offset>, pt: Offset, radius: Float): List<Offset>? {
    val last = points.lastOrNull()
    if (last != null && (pt - last).getDistance() < radius / 8f) return null
    var flushed: List<Offset>? = null
    if (points.size >= MAX_ERASER_PATH_POINTS) {
        flushed = points.toList()
        points.clear()
        points.add(flushed.last())
    }
    points.add(pt)
    return flushed
}

internal fun inkEraseCommand(page: Int, points: List<Offset>, radius: Double = ERASER_RADIUS_DEFAULT.toDouble()): Map<String, Any> {
    require(page >= 0) { "Negative PDF page" }
    require(points.isNotEmpty() && points.size <= MAX_ERASER_PATH_POINTS) { "Invalid eraser gesture" }
    require(radius.isFinite() && radius in ERASER_RADIUS_MIN.toDouble()..ERASER_RADIUS_MAX.toDouble()) { "Invalid eraser radius" }
    require(points.all { it.x.isFinite() && it.y.isFinite() }) { "Non-finite eraser coordinates" }
    return mapOf(
        "page" to page + 1,
        "path" to points.map { listOf(it.x.toDouble(), it.y.toDouble()) },
        "radius" to radius,
        "erase_highlights" to true,
    )
}
