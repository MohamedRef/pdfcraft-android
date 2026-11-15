package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
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
    /** Bumped when widths change without the points changing (the end taper on lift, #569). */
    val version: Int = 0,
)

internal fun inkPathKey(points: List<Offset>, preview: List<Offset>?, pressures: Int, pressureSensitive: Boolean, width: Float, version: Int = 0): InkPathKey {
    val shown = preview ?: points
    return InkPathKey(shown.size, shown.firstOrNull(), shown.lastOrNull(), preview, pressures, pressureSensitive, width, version)
}

/**
 * A smoothed run path in page points, with the stroke width (points) it is drawn with, or (when
 * [filled]) the nonzero-filled outline of a whole pressure stroke.
 */
internal class InkRunPath(val path: Path, val width: Float, val filled: Boolean = false, val grainLevel: Int = 0)

/**
 * A pencil stroke's outline split by ink density (#569), as the engine draws the saved comment
 * (`pencil_content`): each segment at the step of its two points' mean density, each point's disc
 * at the lighter step of its two segments. One filled path per step, lightest first.
 */
internal fun pencilInkRuns(points: List<Offset>, radii: List<Float>, densities: List<Float>, width: Float): List<InkRunPath> {
    if (points.size < 2 || radii.size != points.size || densities.size != points.size) return emptyList()
    val (discLevel, segLevel) = pencilGrainLevels(densities)
    val paths = sortedMapOf<Int, Path>()
    for (i in points.indices) paths.getOrPut(discLevel[i]) { Path() }.addInkDisc(points[i], radii[i])
    val curve = inkSmoothSegments(points, 0, points.lastIndex)
    for (i in curve.indices) {
        val path = paths.getOrPut(segLevel[i]) { Path() }
        pressureInkQuads(points[i], curve[i], radii[i], radii[i + 1]).forEach { path.addInkQuad(it) }
    }
    return paths.map { (level, path) -> InkRunPath(path, width, filled = true, grainLevel = if (level >= PencilGrain.LEVELS) 0 else level) }
}

/**
 * The grain step of each point's disc and of each segment of a pencil stroke with [densities]
 * (at least two points), as the engine's `pencil_content` picks them: a segment takes the mean of
 * its ends, a point the lighter of its two segments.
 */
internal fun pencilGrainLevels(densities: List<Float>): Pair<List<Int>, List<Int>> {
    val saved = densities.map(PencilGrain::saved)
    val seg = List(maxOf(saved.size - 1, 0)) { i -> PencilGrain.level((saved[i] + saved[i + 1]) / 2.0) }
    val disc = List(saved.size) { i ->
        when {
            i > 0 && i < seg.size -> minOf(seg[i - 1], seg[i])
            i > 0 -> seg[i - 1]
            seg.isNotEmpty() -> seg[0]
            else -> PencilGrain.level(saved[0])
        }
    }
    return disc to seg
}

/** One stroke point's disc of the pressure-ink outline. */
internal data class InkDisc(val center: Offset, val radius: Float)

/** Radius per point, as the engine's `pressure_ink_outline` clamps it. */
internal fun pressureInkRadii(widths: List<Float>): List<Float> = widths.map { (it / 2f).coerceIn(0.01f, 400f) }

/**
 * The quadrilaterals of segment [i] of the pressure-ink outline, as the engine builds them
 * (`pdfcraft_annot::appearance::pressure_ink_outline`): samples along the Catmull-Rom cubic, at
 * most 8 per segment and about one per smaller radius, with radii interpolated linearly, and a
 * quad between neighbouring samples, every quad wound with positive signed area so the nonzero
 * fill paints the union of discs and quads once (#129).
 */
internal fun pressureInkSegmentQuads(points: List<Offset>, radii: List<Float>, i: Int): List<List<Offset>> {
    if (i < 0 || i >= points.lastIndex || radii.size != points.size) return emptyList()
    val seg = inkSmoothSegments(points, 0, points.lastIndex).getOrNull(i) ?: return emptyList()
    return pressureInkQuads(points[i], seg, radii[i], radii[i + 1])
}

private fun pressureInkQuads(p0: Offset, seg: InkCubic, r0: Float, r1: Float): List<List<Offset>> {
    val (c1, c2, end) = seg
    val len = (end - p0).getDistance()
    val steps = kotlin.math.ceil(len / maxOf(minOf(r0, r1), 0.5f)).coerceIn(1f, 8f).toInt()
    var prev = p0
    var prevR = r0
    val out = ArrayList<List<Offset>>(steps)
    for (k in 1..steps) {
        val t = k.toFloat() / steps
        val u = 1f - t
        val b0 = u * u * u; val b1 = 3f * u * u * t; val b2 = 3f * u * t * t; val b3 = t * t * t
        val at = Offset(b0 * p0.x + b1 * c1.x + b2 * c2.x + b3 * end.x, b0 * p0.y + b1 * c1.y + b2 * c2.y + b3 * end.y)
        val r = r0 + (r1 - r0) * t
        val dx = at.x - prev.x
        val dy = at.y - prev.y
        val l = kotlin.math.hypot(dx, dy)
        if (l >= 1e-6f) {
            val nx = -dy / l
            val ny = dx / l
            val quad = mutableListOf(
                Offset(prev.x + nx * prevR, prev.y + ny * prevR), Offset(prev.x - nx * prevR, prev.y - ny * prevR),
                Offset(at.x - nx * r, at.y - ny * r), Offset(at.x + nx * r, at.y + ny * r),
            )
            if (inkSignedArea(quad) < 0f) quad.reverse()
            out += quad
        }
        prev = at
        prevR = r
    }
    return out
}

/** Twice the signed area of a closed polygon. */
internal fun inkSignedArea(p: List<Offset>): Float =
    p.indices.sumOf { i -> val a = p[i]; val b = p[(i + 1) % p.size]; (a.x * b.y - b.x * a.y).toDouble() }.toFloat()

private fun Path.addInkDisc(c: Offset, r: Float) {
    // Same winding as the engine's `disc` (and as the positive-area quads).
    val k = r * 0.55228475f
    moveTo(c.x + r, c.y)
    cubicTo(c.x + r, c.y + k, c.x + k, c.y + r, c.x, c.y + r)
    cubicTo(c.x - k, c.y + r, c.x - r, c.y + k, c.x - r, c.y)
    cubicTo(c.x - r, c.y - k, c.x - k, c.y - r, c.x, c.y - r)
    cubicTo(c.x + k, c.y - r, c.x + r, c.y - k, c.x + r, c.y)
    close()
}

private fun Path.addInkQuad(q: List<Offset>) {
    moveTo(q[0].x, q[0].y)
    for (j in 1 until q.size) lineTo(q[j].x, q[j].y)
    close()
}

/** Whether a draft stroke saves as one pressure outline (see `inkSaveCommands`), so its preview should too. */
internal fun inkPreviewIsPressureOutline(pressureSensitive: Boolean, style: InkStyle, points: Int, pressures: Int, shapePreview: Boolean): Boolean =
    pressureSensitive && style != InkStyle.Highlighter && !shapePreview && points >= 2 && pressures == points

/**
 * Per-stroke cache of smoothed paths in page coordinates. Before this, every frame rebuilt a
 * screen-space polyline for every point of every uncommitted stroke on every page, so writing
 * got slower the more had been written in one Draw session. Now only the stroke whose points
 * changed (normally the one being drawn) is rebuilt; zoom and scroll are a canvas transform.
 */
internal class InkPathCache {
    private var key: InkPathKey? = null
    private var runs: List<InkRunPath> = emptyList()

    // Pressure outline built incrementally: discs and quads of segments that can no longer change
    // (a segment depends on the next two points) stay in [stable]; only the tail is rebuilt.
    private val stable = Path()
    private var stableFirst: Offset? = null
    private var stableLast: Offset? = null
    private var stableDiscs = 0
    private var stableSegments = 0
    private var stableVersion = 0

    private fun resetStable() {
        stable.reset()
        stableFirst = null
        stableLast = null
        stableDiscs = 0
        stableSegments = 0
    }

    private fun outline(stroke: InkStroke): Path {
        val pts = stroke.points.toList()
        val radii = pressureInkRadii(inkStylePointWidths(stroke.scales.toList(), stroke.appearance.width, stroke.appearance.style))
        // Any change other than appending (lasso move, replaced points, new widths) rebuilds from scratch.
        if (stableVersion != stroke.widthVersion) resetStable()
        stableVersion = stroke.widthVersion
        if (stableFirst != pts.first() || stableDiscs > pts.size || (stableDiscs > 0 && stableLast != pts[stableDiscs - 1])) resetStable()
        stableFirst = pts.first()
        while (stableDiscs < pts.size) { stable.addInkDisc(pts[stableDiscs], radii[stableDiscs]); stableDiscs++ }
        stableLast = pts[stableDiscs - 1]
        val segments = inkSmoothSegments(pts, 0, pts.lastIndex)
        val finalSegments = (pts.lastIndex - 1).coerceAtLeast(0)
        while (stableSegments < finalSegments) {
            pressureInkQuads(pts[stableSegments], segments[stableSegments], radii[stableSegments], radii[stableSegments + 1]).forEach { stable.addInkQuad(it) }
            stableSegments++
        }
        val path = Path()
        path.addPath(stable)
        for (i in stableSegments until segments.size) {
            pressureInkQuads(pts[i], segments[i], radii[i], radii[i + 1]).forEach { path.addInkQuad(it) }
        }
        return path
    }

    fun runs(stroke: InkStroke): List<InkRunPath> {
        val k = inkPathKey(stroke.points, stroke.preview, stroke.scales.size, stroke.pressureSensitive, stroke.appearance.width, stroke.widthVersion)
        if (k == key) return runs
        if (stroke.densities.size == stroke.points.size && stroke.preview == null &&
            inkPreviewIsPressureOutline(stroke.pressureSensitive, stroke.appearance.style, stroke.points.size, stroke.scales.size, false)
        ) {
            // a pencil (#569): rebuilt whole, one path per grain step
            val pts = stroke.points.toList()
            val radii = pressureInkRadii(inkStylePointWidths(stroke.scales.toList(), stroke.appearance.width, stroke.appearance.style))
            runs = pencilInkRuns(pts, radii, stroke.densities.toList(), stroke.appearance.width)
            key = k
            return runs
        }
        if (inkPreviewIsPressureOutline(stroke.pressureSensitive, stroke.appearance.style, stroke.points.size, stroke.scales.size, stroke.preview != null)) {
            runs = listOf(InkRunPath(outline(stroke), stroke.appearance.width, filled = true))
            key = k
            return runs
        }
        resetStable()
        val pts = stroke.preview ?: stroke.points.toList()
        val widthRuns = if (stroke.preview != null) {
            if (pts.size >= 2) inkStyleWidthRuns(List(pts.size) { 1f }, stroke.appearance.width, false, stroke.appearance.style) else emptyList()
        } else inkStyleWidthRuns(stroke.scales.toList(), stroke.appearance.width, stroke.pressureSensitive, stroke.appearance.style)
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
 * A single layer applies the opacity once, as a pen would. A highlighter needs none: it is drawn
 * opaque with Darken ([inkHighlighterColor]).
 */
internal fun inkNeedsLayer(runCount: Int, opacity: Float, highlighter: Boolean): Boolean =
    runCount > 1 && opacity < 1f && !highlighter

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
    /** The page's height in points, so a pencil's grain meets the saved comment's grid. */
    pageHeightPt: Float = 0f,
) {
    if (runs.isEmpty() || !scale.isFinite() || scale <= 0f) return
    val blend = if (highlighter) BlendMode.Darken else BlendMode.SrcOver
    val layered = inkNeedsLayer(runs.size, opacity, highlighter)
    val paintColor = when {
        highlighter -> inkHighlighterColor(color, opacity)
        layered -> color.copy(alpha = 1f)
        else -> color.copy(alpha = opacity)
    }
    val cap = if (highlighter) StrokeCap.Square else StrokeCap.Round
    fun strokes() = withTransform({
        translate(origin.x, origin.y)
        scale(scale, scale, Offset.Zero)
    }) {
        for (run in runs) {
            if (run.grainLevel > 0) {
                drawPath(
                    run.path, PencilGrain.brush(color.copy(alpha = 1f).toArgb(), run.grainLevel, pageHeightPt),
                    alpha = if (layered) 1f else opacity, style = Fill,
                    blendMode = if (layered) BlendMode.SrcOver else blend,
                )
                continue
            }
            drawPath(
                run.path, paintColor,
                style = if (run.filled) Fill else Stroke(width = run.width, cap = cap, join = StrokeJoin.Round),
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

/**
 * Draw the tail of the stroke being written ([inkTailPoints], in screen pixels) at [widthPx]: one
 * path, so a translucent pen's tail is laid down once. Drawn only, never saved.
 */
internal fun DrawScope.drawInkTail(tail: List<Offset>, color: Color, opacity: Float, widthPx: Float, highlighter: Boolean = false) {
    if (tail.size < 2 || !(widthPx > 0f)) return
    val path = Path()
    path.moveTo(tail[0].x, tail[0].y)
    for (i in 1 until tail.size) path.lineTo(tail[i].x, tail[i].y)
    if (highlighter) {
        // opaque and Darken, as the stroke itself: the tail does not darken where it meets the line
        drawPath(path, inkHighlighterColor(color, opacity), style = Stroke(width = widthPx, cap = StrokeCap.Square, join = StrokeJoin.Round), blendMode = BlendMode.Darken)
        return
    }
    drawPath(
        path, color.copy(alpha = opacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f),
        style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * The highlighter's paint (#569): its colour at [opacity] mixed with white, opaque, drawn with
 * Darken. Over white it is the colour at that opacity, as Multiply gave; text under it stays
 * black; and where two strokes of one colour cross, or one crosses itself, the colour is not laid
 * down twice. The saved comment is drawn the same way (`highlighter_colour` in the engine).
 */
internal fun inkHighlighterColor(color: Color, opacity: Float): Color {
    val a = opacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    fun mix(v: Float) = 1f - a * (1f - v.coerceIn(0f, 1f))
    return Color(mix(color.red), mix(color.green), mix(color.blue), 1f)
}
