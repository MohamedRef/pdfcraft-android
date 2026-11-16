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
    /** Where the line drawn ends while it is written: the pen, beyond the last point (#611). */
    val end: Offset? = null,
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

/** Radius per point, as the engine's `pressure_ink_strokes` clamps it. */
internal fun pressureInkRadii(widths: List<Float>): List<Float> = widths.map { (it / 2f).coerceIn(0.01f, 400f) }

/** How far a ribbon's straight edges may be from the curved outline they stand for, in points (the engine's `INK_FLATNESS_PT`). */
internal const val INK_FLATNESS_PT = 0.03f

/**
 * How many samples a segment of the smoothed curve is drawn with, 1 to 8, as the engine's
 * `ink_steps`: enough that neither the middle of the line nor its edge, [radius] from the middle,
 * is further than [INK_FLATNESS_PT] from the straight pieces.
 */
internal fun inkSteps(p0: Offset, seg: InkCubic, radius: Float): Int {
    val legs = listOf(seg.c1 - p0, seg.c2 - seg.c1, seg.end - seg.c2).filter { it.getDistance() >= 1e-9f }
    var turn = 0f
    for (i in 1 until legs.size) {
        val a = legs[i - 1]
        val b = legs[i]
        turn += kotlin.math.abs(kotlin.math.atan2(a.x * b.y - a.y * b.x, a.x * b.x + a.y * b.y))
    }
    val bend = maxOf((p0 - seg.c1 * 2f + seg.c2).getDistance(), (seg.c1 - seg.c2 * 2f + seg.end).getDistance())
    return kotlin.math.ceil(kotlin.math.sqrt((0.75f * bend + radius * turn * turn / 8f) / INK_FLATNESS_PT)).coerceIn(1f, 8f).toInt()
}

/**
 * Where the edges of a line pass a sample, from the sample: to its left edge and to its right, as
 * the engine's `ink_edges`. The line is every disc along its middle, so where its radius changes
 * its edges touch a disc a little behind or ahead of straight across. [d] is the curve's direction,
 * [slope] how much the radius grows for each point along it; `null` where the curve has no direction.
 */
internal fun inkEdges(d: Offset, radius: Float, slope: Float): Pair<Offset, Offset>? {
    val len = d.getDistance()
    if (!(len >= 1e-9f) || !slope.isFinite()) return null
    val tx = d.x / len
    val ty = d.y / len
    val k = slope.coerceIn(-0.95f, 0.95f)
    val s = kotlin.math.sqrt(1f - k * k)
    return Offset(radius * (-k * tx - s * ty), radius * (-k * ty + s * tx)) to Offset(radius * (-k * tx + s * ty), radius * (-k * ty - s * tx))
}

/**
 * The quadrilateral between two neighbouring samples of the outline, wound with positive area.
 * [shared]: its two ends lie across the curve at its samples ([inkEdges]), so it shares each end
 * with the quad beyond it; otherwise its ends are square to its own direction and a disc joins it on.
 */
internal class InkQuad(val corners: List<Offset>, val shared: Boolean)

/** Whether a quadrilateral is convex and wound with positive area, as the engine's `convex_ccw`. */
internal fun inkConvex(q: List<Offset>): Boolean = (0 until 4).all { i ->
    val a = q[i]
    val b = q[(i + 1) % 4]
    val c = q[(i + 2) % 4]
    (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) > 1e-12f
}

/**
 * One segment of a stroke's outline (#609), as the engine builds it (`ink_samples`): its samples
 * along the Catmull-Rom cubic ([inkSteps] of them after the point it starts at) with their radii,
 * and the quadrilateral before each sample after the first (`null` between two samples at one place).
 */
internal class InkSegment(val centers: List<Offset>, val radii: List<Float>, val quads: List<InkQuad?>) {
    val startShared get() = quads.first()?.shared == true
    val endShared get() = quads.last()?.shared == true

    /**
     * The segment's pieces: its quads joined into ribbons while they share their ends, and a disc
     * at each sample inside it where two neighbours do not (the points at its ends are the caller's).
     */
    fun emit(sink: InkOutlineSink) {
        for (k in 1 until quads.size) if (!(quads[k - 1]?.shared == true && quads[k]?.shared == true)) sink.disc(centers[k], radii[k])
        // one side of a ribbon out, the other side back
        var right = ArrayList<Offset>()
        var left = ArrayList<Offset>()
        fun close() {
            if (right.isEmpty()) return
            sink.polygon(right + left.asReversed())
            right = ArrayList()
            left = ArrayList()
        }
        var open = false
        for (q in quads) {
            if (q == null) {
                close()
                open = false
                continue
            }
            if (!(q.shared && open)) {
                close()
                left += q.corners[0]
                right += q.corners[1]
            }
            right += q.corners[2]
            left += q.corners[3]
            open = q.shared
        }
        close()
    }
}

/** Segment [i] of the outline of the line with points [point] and radii [radius], 0…[last]. */
internal fun inkSegment(point: (Int) -> Offset, radius: (Int) -> Float, i: Int, last: Int): InkSegment {
    // at a point the curve runs along the chord between its neighbours, and the radius changes as between them
    fun atPoint(j: Int): Pair<Offset, Offset>? {
        val a = (j - 1).coerceAtLeast(0)
        val b = (j + 1).coerceAtMost(last)
        val d = point(b) - point(a)
        return inkEdges(d, radius(j), (radius(b) - radius(a)) / d.getDistance())
    }
    val p0 = point(i)
    val seg = inkSmoothSegment(point, i, last)
    val (c1, c2, end) = seg
    val r0 = radius(i)
    val r1 = radius(i + 1)
    val steps = inkSteps(p0, seg, maxOf(r0, r1))
    val centers = ArrayList<Offset>(steps + 1)
    val radii = ArrayList<Float>(steps + 1)
    val quads = ArrayList<InkQuad?>(steps)
    centers += p0
    radii += r0
    var prevEdges = atPoint(i)
    for (k in 1..steps) {
        val t = k.toFloat() / steps
        val u = 1f - t
        val b0 = u * u * u; val b1 = 3f * u * u * t; val b2 = 3f * u * t * t; val b3 = t * t * t
        val at = Offset(b0 * p0.x + b1 * c1.x + b2 * c2.x + b3 * end.x, b0 * p0.y + b1 * c1.y + b2 * c2.y + b3 * end.y)
        val r = r0 + (r1 - r0) * t
        val edges = if (k == steps) atPoint(i + 1) else {
            // between two points: straight across the curve's direction here
            inkEdges((c1 - p0) * (u * u) + (c2 - c1) * (2f * u * t) + (end - c2) * (t * t), r, 0f)
        }
        val prev = centers.last()
        val prevR = radii.last()
        val dx = at.x - prev.x
        val dy = at.y - prev.y
        val l = kotlin.math.hypot(dx, dy)
        quads += if (l < 1e-6f) null else {
            val across = if (prevEdges != null && edges != null) {
                listOf(prev + prevEdges.first, prev + prevEdges.second, at + edges.second, at + edges.first).takeIf(::inkConvex)
            } else null
            val nx = -dy / l
            val ny = dx / l
            InkQuad(
                across ?: listOf(
                    Offset(prev.x + nx * prevR, prev.y + ny * prevR), Offset(prev.x - nx * prevR, prev.y - ny * prevR),
                    Offset(at.x - nx * r, at.y - ny * r), Offset(at.x + nx * r, at.y + ny * r),
                ),
                across != null,
            )
        }
        centers += at
        radii += r
        prevEdges = edges
    }
    return InkSegment(centers, radii, quads)
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

private fun Path.addInkPolygon(q: List<Offset>) {
    moveTo(q[0].x, q[0].y)
    for (j in 1 until q.size) lineTo(q[j].x, q[j].y)
    close()
}

/** Whether a draft stroke saves as one pressure outline (see `inkSaveCommands`), so its preview should too. */
internal fun inkPreviewIsPressureOutline(pressureSensitive: Boolean, style: InkStyle, points: Int, pressures: Int, shapePreview: Boolean): Boolean =
    pressureSensitive && style != InkStyle.Highlighter && !shapePreview && points >= 2 && pressures == points

/** One segment of [inkSmoothSegments] over points given by index ([at], 0…[last]), without a list of them all. */
internal fun inkSmoothSegment(at: (Int) -> Offset, i: Int, last: Int): InkCubic {
    val p0 = at((i - 1).coerceAtLeast(0))
    val p1 = at(i)
    val p2 = at(i + 1)
    val p3 = at((i + 2).coerceAtMost(last))
    return InkCubic(
        Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f),
        Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f),
        p2,
    )
}

/** Where the pieces of a pressure outline go: a disc, a closed polygon of ribbon. */
internal interface InkOutlineSink {
    fun disc(center: Offset, radius: Float)
    fun polygon(corners: List<Offset>)
}

/**
 * Builds the outline of a stroke as it grows, doing work for its new points only (#609). A segment
 * that can no longer change is given once, to the sink that is kept ([settledSink]); what can still
 * change (the newest points, the pointed end drawn while writing) is given again each time, to
 * [liveSink]. Before, every frame copied every point and width of the stroke being written, built
 * every smoothed segment again and copied the finished path.
 *
 * The outline is the engine's ([inkSegment]): a ribbon whose pieces share their edges, a disc at
 * the two ends of the line, and one where two neighbours do not share an edge or are drawn at
 * different steps. One more disc lies where the kept part and the live part meet, since they are
 * two paths. A segment goes to a step (`level`): a pencil's grain step, or one step for solid ink;
 * a disc takes the lighter step of the two segments it joins.
 */
internal class InkOutlineBuilder {
    /** How many segments are settled, and how many points have their disc, or none, settled. */
    var segments = 0
        private set
    var discs = 0
        private set
    private var endShared = false

    /** Discs and segments given by the last [update]: the work it did. */
    var touched = 0
        private set

    fun reset() {
        discs = 0
        segments = 0
        endShared = false
    }

    /**
     * The line has [own] points of its own and [total] with the pointed end's ([total] ≥ [own]);
     * the widths of the points before [settled] are final; [closed] when the pen has lifted and
     * everything is. [loneLevel] is the step of a line of one point.
     */
    fun update(
        own: Int, total: Int, settled: Int, closed: Boolean,
        point: (Int) -> Offset, radius: (Int) -> Float,
        segmentLevel: (Int) -> Int, loneLevel: () -> Int,
        settledSink: (level: Int) -> InkOutlineSink, liveSink: (level: Int) -> InkOutlineSink,
    ) {
        touched = 0
        if (total <= 0) return
        val last = total - 1
        fun segment(i: Int): InkSegment {
            touched++
            return inkSegment(point, radius, i, last)
        }
        fun pointLevel(i: Int) = when {
            last == 0 -> loneLevel()
            i == 0 -> segmentLevel(0)
            i == last -> segmentLevel(last - 1)
            else -> minOf(segmentLevel(i - 1), segmentLevel(i))
        }
        fun disc(i: Int, sink: (Int) -> InkOutlineSink) {
            sink(pointLevel(i)).disc(point(i), radius(i))
            touched++
        }
        // A segment is final when the two points after it are the line's own and the widths its
        // edges depend on are settled: its two points' and, for the edge at its end, the next one's.
        val finalSegments = if (closed) last else minOf(own - 2, settled - 2).coerceAtLeast(0)
        while (segments < finalSegments) {
            val i = segments
            val s = segment(i)
            val level = segmentLevel(i)
            if (i == 0 || !endShared || !s.startShared || segmentLevel(i - 1) != level) disc(i, settledSink)
            s.emit(settledSink(level))
            endShared = s.endShared
            segments++
            discs = segments
        }
        if (closed) {
            if (discs < total) disc(last, settledSink)
            discs = total
            return
        }
        // a disc always covers where the kept path and the live one meet
        var shared = false
        for (i in segments until last) {
            val s = segment(i)
            val level = segmentLevel(i)
            if (!shared || !s.startShared || (i > 0 && segmentLevel(i - 1) != level)) disc(i, liveSink)
            s.emit(liveSink(level))
            shared = s.endShared
        }
        disc(last, liveSink)
    }
}

private class PathSink(val path: Path = Path()) : InkOutlineSink {
    var used = false
    override fun disc(center: Offset, radius: Float) { path.addInkDisc(center, radius); used = true }
    override fun polygon(corners: List<Offset>) { path.addInkPolygon(corners); used = true }
}

/**
 * Per-stroke cache of smoothed paths in page coordinates. Before this, every frame rebuilt a
 * screen-space polyline for every point of every uncommitted stroke on every page, so writing
 * got slower the more had been written in one Draw session. Now only the stroke whose points
 * changed (normally the one being drawn) is rebuilt, and of that stroke only what is new
 * ([InkOutlineBuilder], #609); zoom and scroll are a canvas transform.
 */
internal class InkPathCache {
    private var key: InkPathKey? = null
    private var runs: List<InkRunPath> = emptyList()

    // The settled part of a pressure outline, per step (a pencil's grain steps; solid ink has one).
    private val builder = InkOutlineBuilder()
    private val settledPaths = sortedMapOf<Int, PathSink>()
    private var settledFirst: Offset? = null
    private var settledLast: Offset? = null
    private var settledVersion = 0
    private var settledPencil = false

    private fun resetSettled() {
        builder.reset()
        settledPaths.clear()
        settledFirst = null
        settledLast = null
    }

    private fun outline(stroke: InkStroke, end: InkEndPreview?, pencil: Boolean): List<InkRunPath> {
        val line = stroke.points
        val own = line.size
        // While the stroke is written its end is drawn to a point (#611): the last stretch has
        // the widths the lift would give it and runs on to the pen. Those points are not final.
        val settled = end?.from ?: own
        val total = own + (end?.extra?.size ?: 0)
        val base = inkStylePointWidths(listOf(1f), stroke.appearance.width, stroke.appearance.style).first()
        fun point(i: Int) = if (i < own) line[i] else end!!.extra[i - own]
        fun radius(i: Int): Float {
            val scale = if (end != null && i >= settled) end.scales[i - settled] else stroke.scales[i]
            return (base * (scale.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.01f, 3f) ?: 1f) / 2f).coerceIn(0.01f, 400f)
        }
        // a pencil: each segment at the step of its two points' mean density, as the engine's `pencil_content`
        fun segmentLevel(i: Int) = if (!pencil) 0 else
            PencilGrain.level((PencilGrain.saved(stroke.densities[i]) + PencilGrain.saved(stroke.densities[i + 1])) / 2.0)
        // Any change other than appending (lasso move, replaced points, new widths) rebuilds from scratch.
        if (settledVersion != stroke.widthVersion || settledPencil != pencil) resetSettled()
        settledVersion = stroke.widthVersion
        settledPencil = pencil
        if (settledFirst != line.first() || builder.discs > own || (builder.discs > 0 && settledLast != line[builder.discs - 1])) resetSettled()
        settledFirst = line.first()
        val live = sortedMapOf<Int, PathSink>()
        builder.update(
            own, total, settled, closed = stroke.lifted,
            point = ::point, radius = ::radius, segmentLevel = ::segmentLevel,
            loneLevel = { if (pencil) PencilGrain.level(PencilGrain.saved(stroke.densities[0])) else 0 },
            settledSink = { level -> settledPaths.getOrPut(level) { PathSink() } },
            liveSink = { level -> live.getOrPut(level) { PathSink() } },
        )
        if (builder.discs > 0) settledLast = line[builder.discs - 1]
        // lightest first; at each step the settled path, then what is still live
        return (settledPaths.keys + live.keys).toSortedSet().flatMap { level ->
            val grain = if (!pencil || level >= PencilGrain.LEVELS) 0 else level
            listOfNotNull(settledPaths[level], live[level]).filter { it.used }.map { InkRunPath(it.path, stroke.appearance.width, filled = true, grainLevel = grain) }
        }
    }

    fun runs(stroke: InkStroke): List<InkRunPath> {
        val outlined = inkPreviewIsPressureOutline(stroke.pressureSensitive, stroke.appearance.style, stroke.points.size, stroke.scales.size, stroke.preview != null)
        val pencil = outlined && stroke.densities.size == stroke.points.size
        // the pointed end of a line still being written (#611); a pencil has none
        val end = if (outlined && !pencil) stroke.endPreview() else null
        val k = inkPathKey(stroke.points, stroke.preview, stroke.scales.size, stroke.pressureSensitive, stroke.appearance.width, stroke.widthVersion)
            .copy(end = end?.extra?.lastOrNull())
        if (k == key) return runs
        if (outlined) {
            runs = outline(stroke, end, pencil)
            key = k
            return runs
        }
        resetSettled()
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
 * How the page under a draft is shown (#610), so the draft looks as it will once it is saved and
 * drawn as part of the page: [night] is Night Mode (the page's image is drawn as inverted grey),
 * [darkPaper] a stroke written on dark paper ([InkAppearance.onDark]).
 */
internal data class InkLook(val night: Boolean = false, val darkPaper: Boolean = false) {
    companion object {
        val Plain = InkLook()
    }
}

/** [c] as Night Mode shows it: inverted grey, the same numbers as [NIGHT_INVERT_MATRIX]; its alpha is kept. */
internal fun nightColor(c: Color): Color {
    val grey = (1f - (c.red + c.green + c.blue) / 3f).coerceIn(0f, 1f)
    return Color(grey, grey, grey, c.alpha)
}

/**
 * The colour a draft is painted with so that it matches the saved stroke. Night Mode's inversion
 * is the same for every pixel and keeps a mix a mix, so ink of a colour at an opacity over the
 * page, then inverted, is the inverted colour at that opacity over the inverted page.
 */
internal fun inkScreenColor(c: Color, look: InkLook): Color = if (look.night) nightColor(c) else c

/**
 * The marker's paint and blend. On light paper it is [inkHighlighterColor] with Darken; on dark
 * paper [inkHighlighterColorOnDark] with Lighten, as the saved comment (the engine's `backdrop`).
 * Night Mode inverts the page after the marker is on it, which turns the keeping of the darker
 * colour into the keeping of the lighter one and back.
 */
internal fun inkHighlighterPaint(color: Color, opacity: Float, look: InkLook): Pair<Color, BlendMode> {
    val saved = if (look.darkPaper) inkHighlighterColorOnDark(color, opacity) else inkHighlighterColor(color, opacity)
    return inkScreenColor(saved, look) to if (look.darkPaper != look.night) BlendMode.Lighten else BlendMode.Darken
}

/**
 * The marker's paint on dark paper (#610): its colour at [opacity] mixed with black, opaque, drawn
 * with Lighten. Darken keeps the darker colour, so over dark paper it drew nothing; Lighten gives
 * the paper the marker's colour, leaves light ink under it light, and still lays a colour down
 * once where strokes cross. The saved comment is drawn the same way (`highlighter_colour_on_dark`).
 */
internal fun inkHighlighterColorOnDark(color: Color, opacity: Float): Color {
    val a = opacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    return Color(a * color.red.coerceIn(0f, 1f), a * color.green.coerceIn(0f, 1f), a * color.blue.coerceIn(0f, 1f), 1f)
}

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
    /** How the page under the stroke is shown, so the draft matches the saved stroke (#610). */
    look: InkLook = InkLook.Plain,
) {
    if (runs.isEmpty() || !scale.isFinite() || scale <= 0f) return
    val marker = if (highlighter) inkHighlighterPaint(color, opacity, look) else null
    val blend = marker?.second ?: BlendMode.SrcOver
    val layered = inkNeedsLayer(runs.size, opacity, highlighter)
    val color = inkScreenColor(color, look)
    val paintColor = when {
        marker != null -> marker.first
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
internal fun DrawScope.drawInkTail(
    tail: List<Offset>, color: Color, opacity: Float, widthPx: Float, highlighter: Boolean = false, look: InkLook = InkLook.Plain,
) {
    if (tail.size < 2 || !(widthPx > 0f)) return
    val path = Path()
    path.moveTo(tail[0].x, tail[0].y)
    for (i in 1 until tail.size) path.lineTo(tail[i].x, tail[i].y)
    if (highlighter) {
        // opaque and blended as the stroke itself: the tail does not darken where it meets the line
        val (paint, blend) = inkHighlighterPaint(color, opacity, look)
        drawPath(path, paint, style = Stroke(width = widthPx, cap = StrokeCap.Square, join = StrokeJoin.Round), blendMode = blend)
        return
    }
    drawPath(
        path, inkScreenColor(color, look).copy(alpha = opacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f),
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
