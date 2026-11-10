package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform

/**
 * One cubic Bézier segment of a smoothed ink run, in page points: first control point, second
 * control point and end point.
 */
internal data class InkCubic(val c1: Offset, val c2: Offset, val end: Offset)

/**
 * The same Catmull-Rom smoothing the engine uses for saved Ink appearances
 * (`pdfcraft_annot::appearance::smooth_segments`): the curve passes every point and leaves it
 * parallel to the chord between its neighbours; a run end uses itself as the missing neighbour.
 * The preview therefore no longer changes shape (straight segments to curves) when it is saved.
 */
internal fun inkSmoothSegments(points: List<Offset>, start: Int = 0, end: Int = points.lastIndex): List<InkCubic> {
    if (points.isEmpty() || start < 0 || end > points.lastIndex || end <= start) return emptyList()
    fun at(i: Int) = points[i.coerceIn(start, end)]
    val out = ArrayList<InkCubic>(end - start)
    for (i in start until end) {
        val p0 = at(i - 1)
        val p1 = at(i)
        val p2 = at(i + 1)
        val p3 = at(i + 2)
        out += InkCubic(
            Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f),
            Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f),
            p2,
        )
    }
    return out
}

/** What a cached draft path was built from; any change in it rebuilds the stroke's paths. */
internal data class InkPathKey(
    val count: Int,
    val first: Offset?,
    val last: Offset?,
    val preview: List<Offset>?,
    val pressureCount: Int,
    val pressureSensitive: Boolean,
    val width: Float,
)

internal fun inkPathKey(points: List<Offset>, preview: List<Offset>?, pressures: Int, pressureSensitive: Boolean, width: Float): InkPathKey {
    val shown = preview ?: points
    return InkPathKey(shown.size, shown.firstOrNull(), shown.lastOrNull(), preview, pressures, pressureSensitive, width)
}

/** A smoothed run path in page points, with the stroke width (points) it is drawn with. */
internal class InkRunPath(val path: Path, val width: Float)

/**
 * Per-stroke cache of smoothed paths in page coordinates. Before this, every frame rebuilt a
 * screen-space polyline for every point of every uncommitted stroke on every page, so writing
 * got slower the more had been written in one Draw session. Now only the stroke whose points
 * changed (normally the one being drawn) is rebuilt; zoom and scroll are a canvas transform.
 */
internal class InkPathCache {
    private var key: InkPathKey? = null
    private var runs: List<InkRunPath> = emptyList()

    fun runs(stroke: InkStroke): List<InkRunPath> {
        val k = inkPathKey(stroke.points, stroke.preview, stroke.pressures.size, stroke.pressureSensitive, stroke.appearance.width)
        if (k == key) return runs
        val pts = stroke.preview ?: stroke.points.toList()
        val widthRuns = if (stroke.preview != null) {
            if (pts.size >= 2) inkStyleWidthRuns(List(pts.size) { 1f }, stroke.appearance.width, false, stroke.appearance.style) else emptyList()
        } else inkStyleWidthRuns(stroke.pressures.toList(), stroke.appearance.width, stroke.pressureSensitive, stroke.appearance.style)
        runs = widthRuns.map { run ->
            val path = Path()
            path.moveTo(pts[run.start].x, pts[run.start].y)
            for (c in inkSmoothSegments(pts, run.start, run.end)) {
                path.cubicTo(c.c1.x, c.c1.y, c.c2.x, c.c2.y, c.end.x, c.end.y)
            }
            InkRunPath(path, run.width)
        }
        key = k
        return runs
    }
}

/**
 * Whether a stroke must be composited as one layer: translucent ink split into pressure-width
 * runs overlaps at every run boundary (round caps), which painted darker beads along the line.
 * A single layer applies the opacity once, as a pen would.
 */
internal fun inkNeedsLayer(runCount: Int, opacity: Float, highlighter: Boolean): Boolean =
    runCount > 1 && (opacity < 1f || highlighter)

/**
 * Draw one draft stroke: page points mapped by [origin] (screen position of the page's top-left
 * plus any lasso move, already scaled) and [scale] (screen pixels per point).
 */
internal fun DrawScope.drawInkStroke(
    runs: List<InkRunPath>,
    color: Color,
    opacity: Float,
    highlighter: Boolean,
    origin: Offset,
    scale: Float,
    pageBoundsOnScreen: Rect,
) {
    if (runs.isEmpty() || !scale.isFinite() || scale <= 0f) return
    val blend = if (highlighter) BlendMode.Multiply else BlendMode.SrcOver
    val layered = inkNeedsLayer(runs.size, opacity, highlighter)
    val paintColor = if (layered) color.copy(alpha = 1f) else color.copy(alpha = opacity)
    fun strokes() = withTransform({
        translate(origin.x, origin.y)
        scale(scale, scale, Offset.Zero)
    }) {
        for (run in runs) {
            drawPath(
                run.path, paintColor,
                style = Stroke(width = run.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                blendMode = if (layered) BlendMode.SrcOver else blend,
            )
        }
    }
    if (!layered) {
        strokes()
        return
    }
    drawIntoCanvas { canvas ->
        // The page's on-screen rectangle bounds the layer: ink is clamped to its page.
        canvas.saveLayer(pageBoundsOnScreen, Paint().apply { alpha = opacity; blendMode = blend })
        strokes()
        canvas.restore()
    }
}
