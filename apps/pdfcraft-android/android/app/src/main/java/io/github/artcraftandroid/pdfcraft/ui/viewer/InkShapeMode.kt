package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Explicit one-gesture vector shapes, separate from freehand's optional shape recognition. */
internal enum class InkShapeMode { Freehand, Line, Rectangle, Oval }

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
        InkShapeMode.Line -> if ((a - b).getDistance() >= 2f) SnappedShape.Line(a, b) else null
        InkShapeMode.Rectangle -> if (width >= 2f && height >= 2f) SnappedShape.Rectangle(left, top, right, bottom) else null
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
): List<Offset>? = inkShapeGeometry(mode, from, to, pageWidth, pageHeight)?.outline()
