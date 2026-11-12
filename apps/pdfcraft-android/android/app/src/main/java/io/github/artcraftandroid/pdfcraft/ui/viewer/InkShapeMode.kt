package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Explicit one-gesture vector shapes, separate from freehand's optional shape recognition. */
internal enum class InkShapeMode {
    Freehand, Line, Rectangle, Oval,

    /** A real PDF arrow (`Line` with an open-arrow ending), placed when the drag ends. */
    Arrow,

    /** A revision cloud (`Polygon` with a cloudy border) around the dragged rectangle. */
    Cloud,

    /** A closed `Polygon`: tap its corners, then the first one again (#133). */
    Polygon,

    /** Connected lines (`PolyLine`): tap its points, then the last one again (#133). */
    Polyline,
}

/**
 * Shapes saved as their own PDF annotation (a real Line, Square, Circle, Polygon or PolyLine that
 * other readers can edit, #170), not as ink drafts. Freehand strokes that snap to a shape stay ink.
 */
internal val InkShapeMode.isAnnotation: Boolean get() = this != InkShapeMode.Freehand

/** Shapes placed by tapping their points one by one rather than with one drag. */
internal val InkShapeMode.isTapped: Boolean get() = this == InkShapeMode.Polygon || this == InkShapeMode.Polyline

/** What a tap does to a polygon or connected lines being placed. */
internal enum class PolyTap { Add, Finish, Ignore }

/**
 * A tap at [at] while [points] (page points) are placed: on the first point (three or more
 * placed) or on the last one (two or more, three for a polygon) it finishes the shape; a tap on
 * another existing point is ignored; anywhere else adds a point. [radius] is how near counts.
 */
internal fun polyTap(points: List<Offset>, at: Offset, radius: Float, closed: Boolean): PolyTap {
    if (!at.x.isFinite() || !at.y.isFinite()) return PolyTap.Ignore
    val min = if (closed) 3 else 2
    if (points.size >= 3 && (points.first() - at).getDistance() <= radius) return PolyTap.Finish
    if (points.size >= min && (points.last() - at).getDistance() <= radius) return PolyTap.Finish
    if (points.any { (it - at).getDistance() <= radius }) return PolyTap.Ignore
    return PolyTap.Add
}

/** `comment_add` arguments for a tapped polygon or connected lines, or null with too few points. */
internal fun polyAnnotationArgs(closed: Boolean, points: List<Offset>, page: Int, color: String, opacity: Float, width: Float, author: String?): Map<String, Any?>? {
    if (points.size < (if (closed) 3 else 2)) return null
    return mapOf(
        "page" to page + 1, "type" to if (closed) "polygon" else "polyline",
        "points" to points.map { listOf(it.x.toDouble(), it.y.toDouble()) },
        "color" to color, "opacity" to opacity.toDouble(), "width" to width.toDouble(), "author" to author,
    )
}

/**
 * Computes an exact shape within its source page, from the down and current/up points.
 * The preview uses the geometry directly, without allocating dense PDF ink samples on each move.
 */
internal fun inkShapeGeometry(
    mode: InkShapeMode,
    from: Offset,
    to: Offset,
    pageWidth: Float,
    pageHeight: Float,
): SnappedShape? {
    if (mode == InkShapeMode.Freehand ||
        !pageWidth.isFinite() || !pageHeight.isFinite() || pageWidth <= 0f || pageHeight <= 0f ||
        !from.x.isFinite() || !from.y.isFinite() || !to.x.isFinite() || !to.y.isFinite()
    ) return null

    val a = Offset(from.x.coerceIn(0f, pageWidth), from.y.coerceIn(0f, pageHeight))
    val b = Offset(to.x.coerceIn(0f, pageWidth), to.y.coerceIn(0f, pageHeight))
    val left = min(a.x, b.x)
    val top = min(a.y, b.y)
    val right = max(a.x, b.x)
    val bottom = max(a.y, b.y)
    val width = abs(a.x - b.x)
    val height = abs(a.y - b.y)
    return when (mode) {
        InkShapeMode.Freehand, InkShapeMode.Polygon, InkShapeMode.Polyline -> null
        InkShapeMode.Line, InkShapeMode.Arrow -> if ((a - b).getDistance() >= 2f) SnappedShape.Line(a, b) else null
        InkShapeMode.Rectangle, InkShapeMode.Cloud -> if (width >= 2f && height >= 2f) SnappedShape.Rectangle(left, top, right, bottom) else null
        InkShapeMode.Oval -> if (width >= 2f && height >= 2f) SnappedShape.Oval(left, top, right, bottom) else null
    }
}

/** Convert geometry to native PDF Ink points only once, on gesture completion. */
internal fun inkShapeOutline(
    mode: InkShapeMode,
    from: Offset,
    to: Offset,
    pageWidth: Float,
    pageHeight: Float,
): List<Offset>? {
    val shape = inkShapeGeometry(mode, from, to, pageWidth, pageHeight) ?: return null
    return if (mode == InkShapeMode.Arrow && shape is SnappedShape.Line) arrowPreview(shape.from, shape.to) else shape.outline()
}

/** The arrow's shaft and open head as one polyline, for the preview only. */
internal fun arrowPreview(from: Offset, to: Offset): List<Offset> {
    val d = to - from
    val len = d.getDistance()
    if (len < 1e-3f) return listOf(from, to)
    val head = min(12f, len / 3f)
    val ux = d.x / len
    val uy = d.y / len
    // 30° either side of the shaft, back from the tip
    val c = 0.866f
    val s = 0.5f
    val left = Offset(to.x - head * (ux * c - uy * s), to.y - head * (uy * c + ux * s))
    val right = Offset(to.x - head * (ux * c + uy * s), to.y - head * (uy * c - ux * s))
    return listOf(from, to, left, to, right)
}

/**
 * The `comment_add` arguments of an [InkShapeMode.isAnnotation] shape: an arrow from where the
 * drag began to where it ended, or a revision cloud around the dragged rectangle; `null` for
 * any other shape or geometry.
 */
internal fun shapeAnnotationArgs(mode: InkShapeMode, shape: SnappedShape, page: Int, color: String, opacity: Float, width: Float, author: String?): Map<String, Any?>? {
    val common = mapOf("page" to page + 1, "color" to color, "opacity" to opacity.toDouble(), "width" to width.toDouble(), "author" to author)
    return when {
        mode == InkShapeMode.Arrow && shape is SnappedShape.Line -> common + mapOf(
            "type" to "arrow",
            "from" to listOf(shape.from.x.toDouble(), shape.from.y.toDouble()),
            "to" to listOf(shape.to.x.toDouble(), shape.to.y.toDouble()),
        )
        mode == InkShapeMode.Line && shape is SnappedShape.Line -> common + mapOf(
            "type" to "line",
            "from" to listOf(shape.from.x.toDouble(), shape.from.y.toDouble()),
            "to" to listOf(shape.to.x.toDouble(), shape.to.y.toDouble()),
        )
        mode == InkShapeMode.Rectangle && shape is SnappedShape.Rectangle -> common + mapOf(
            "type" to "rectangle", "rect" to listOf(shape.left, shape.top, shape.right, shape.bottom).map { it.toDouble() },
        )
        mode == InkShapeMode.Oval && shape is SnappedShape.Oval -> common + mapOf(
            "type" to "oval", "rect" to listOf(shape.left, shape.top, shape.right, shape.bottom).map { it.toDouble() },
        )
        mode == InkShapeMode.Cloud && shape is SnappedShape.Rectangle -> common + mapOf(
            "type" to "cloud",
            "points" to listOf(
                listOf(shape.left, shape.top), listOf(shape.right, shape.top),
                listOf(shape.right, shape.bottom), listOf(shape.left, shape.bottom),
            ).map { p -> p.map { it.toDouble() } },
        )
        else -> null
    }
}
