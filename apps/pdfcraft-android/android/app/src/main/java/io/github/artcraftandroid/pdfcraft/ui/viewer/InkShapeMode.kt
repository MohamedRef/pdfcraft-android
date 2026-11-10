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
}

/** Shapes saved as their own PDF annotation, not as ink drafts. */
internal val InkShapeMode.isAnnotation: Boolean get() = this == InkShapeMode.Arrow || this == InkShapeMode.Cloud

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
        InkShapeMode.Freehand -> null
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
