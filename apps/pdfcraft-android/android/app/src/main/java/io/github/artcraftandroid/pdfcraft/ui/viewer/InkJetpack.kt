package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch

/** The pens Jetpack Ink draws in the Pen lab trial (#569); the others stay PdfCraft's own. */
internal fun jetpackInkDraws(style: InkStyle): Boolean =
    style == InkStyle.Ballpoint || style == InkStyle.Fountain || style == InkStyle.Brush

/**
 * One stroke drawn by Jetpack Ink (androidx.ink, Apache-2.0), the Pen lab trial (#569): its own
 * smoothing and pressure response, drawn live from its outline. On lift the outline becomes an
 * ordinary PdfCraft pen stroke ([tubeCentreline]), so saving, the eraser and the lasso work as
 * for every other stroke.
 *
 * Positions go in as dp on the glass ([dpPerPt] per page point), the units Jetpack Ink's stock
 * brushes are tuned for, and outlines come back in page points.
 */
internal class JetpackInkLine(style: InkStyle, argb: Int, widthPt: Float, private val dpPerPt: Float, stylus: Boolean) {
    private val stroke = InProgressStroke()
    private val batch = MutableStrokeInputBatch()
    private val tool = if (stylus) InputToolType.STYLUS else InputToolType.TOUCH
    private var startMs = Long.MIN_VALUE
    private var lastMs = 0L

    init {
        val family = if (style == InkStyle.Ballpoint) StockBrushes.marker() else StockBrushes.pressurePen()
        stroke.start(Brush.createWithColorIntArgb(family, argb or 0xFF000000.toInt(), (widthPt * dpPerPt).coerceIn(0.5f, 400f), EPSILON_DP))
    }

    /** A sample at [p] (page points) at [timeMs], with the pen's [pressure] (0…1; a finger has none). */
    fun add(p: Offset, timeMs: Long, pressure: Float) {
        if (startMs == Long.MIN_VALUE) startMs = timeMs
        val t = (timeMs - startMs).coerceAtLeast(lastMs)
        lastMs = t
        batch.clear()
        val pr = if (tool == InputToolType.STYLUS && pressure.isFinite()) pressure.coerceIn(0f, 1f) else -1f
        batch.add(tool, p.x * dpPerPt, p.y * dpPerPt, t, pr)
        stroke.enqueueInputs(batch, EMPTY)
        stroke.updateShape(t)
    }

    /** The pen is lifted: the stroke takes its final shape. */
    fun finish() {
        stroke.finishInput()
        stroke.updateShape(lastMs)
    }

    /** The outlines of the stroke so far, in page points. */
    fun outlines(): List<List<Offset>> {
        val out = ArrayList<List<Offset>>()
        val v = MutableVec()
        for (coat in 0 until stroke.getBrushCoatCount()) {
            for (o in 0 until stroke.getOutlineCount(coat)) {
                val n = stroke.getOutlineVertexCount(coat, o)
                if (n < 3) continue
                out.add(List(n) { i -> stroke.populateOutlinePosition(coat, o, i, v).let { Offset(it.x / dpPerPt, it.y / dpPerPt) } })
            }
        }
        return out
    }

    /** [outlines] as one filled path (page points) to draw. */
    fun path(): Path = Path().apply {
        fillType = PathFillType.NonZero
        for (o in outlines()) {
            moveTo(o[0].x, o[0].y)
            for (i in 1 until o.size) lineTo(o[i].x, o[i].y)
            close()
        }
    }

    private companion object {
        val EMPTY = MutableStrokeInputBatch()
        /** Two positions closer than this (dp) are one, as Jetpack Ink's samples suggest for screen units. */
        const val EPSILON_DP = 0.1f
    }
}

/**
 * Draws [s] from Jetpack Ink's live outline when it has one ([InkStroke.jetpackPath]), at
 * [origin] with [scale] pixels per point; false when it has none, for the caller to draw the
 * stroke as usual.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawJetpackLive(s: InkStroke, origin: Offset, scale: Float): Boolean {
    val path = s.jetpackPath ?: return false
    withTransform({
        translate(origin.x, origin.y)
        scale(scale, scale, Offset.Zero)
    }) {
        drawPath(path, androidx.compose.ui.graphics.Color(s.appearance.argb or 0xFF000000.toInt()).copy(alpha = s.appearance.opacity.coerceIn(0f, 1f)))
    }
    return true
}
