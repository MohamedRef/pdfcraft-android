package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** The zigzag points of a squiggly underline from [x0] to [x1] around [y], [amplitude] high. */
internal fun squigglePoints(x0: Float, x1: Float, y: Float, amplitude: Float): List<Offset> {
    if (!(x0.isFinite() && x1.isFinite() && y.isFinite() && amplitude.isFinite()) || x1 <= x0 || amplitude <= 0f) return emptyList()
    val step = amplitude * 2f
    val count = ((x1 - x0) / step).toInt().coerceIn(1, 4000)
    return (0..count).map { i -> Offset(x0 + (x1 - x0) * i / count, if (i % 2 == 0) y else y - amplitude) }
}

/** A squiggly-underline preview, as the engine draws `Squiggly` markup. */
internal fun DrawScope.drawSquiggle(color: Color, x0: Float, x1: Float, y: Float, amplitude: Float) {
    val pts = squigglePoints(x0, x1, y, amplitude)
    if (pts.size < 2) return
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (p in pts.drop(1)) lineTo(p.x, p.y)
    }
    drawPath(path, color, style = Stroke(width = amplitude * 0.8f, cap = StrokeCap.Round))
}
