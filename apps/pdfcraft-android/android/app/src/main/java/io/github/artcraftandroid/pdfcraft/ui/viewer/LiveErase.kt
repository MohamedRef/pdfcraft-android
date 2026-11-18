package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The part of a page the live eraser renders without its erasable ink (#521): [x], [y], [w], [h]
 * in device pixels of [page] at [scale] pixels per point.
 */
internal data class EraseRegion(val page: Int, val scale: Float, val x: Int, val y: Int, val w: Int, val h: Int)

/** The engine's largest tile (`MAX_MOBILE_TILE_PIXELS`) and side. */
internal const val ERASE_REGION_MAX_PIXELS = 1024L * 1024L
private const val ERASE_REGION_MAX_SIDE = 4096

/**
 * The visible part of [page] (on screen at [pageOnScreen], [pxPerPt] screen pixels per point,
 * the view being [view]) as a region to render. Larger than the engine's tile limit, it is drawn
 * at a lower scale: the image only stands in for the page under the eraser until the real edit's
 * page arrives. Null when none of the page is in view.
 */
internal fun eraseRegion(page: Int, pageOnScreen: Rect, view: Rect, pxPerPt: Float, maxPixels: Long = ERASE_REGION_MAX_PIXELS): EraseRegion? {
    if (!(pxPerPt.isFinite() && pxPerPt > 0f)) return null
    val vis = pageOnScreen.intersect(view)
    if (vis.width < 1f || vis.height < 1f) return null
    val area = vis.width.toDouble() * vis.height
    var k = if (area <= maxPixels) 1.0 else sqrt(maxPixels / area)
    // a side past the engine's limit lowers the scale too
    k = minOf(k, ERASE_REGION_MAX_SIDE / vis.width.toDouble(), ERASE_REGION_MAX_SIDE / vis.height.toDouble())
    val x = floor((vis.left - pageOnScreen.left) * k).toInt().coerceAtLeast(0)
    val y = floor((vis.top - pageOnScreen.top) * k).toInt().coerceAtLeast(0)
    var w = ceil(vis.width * k).toInt().coerceIn(1, ERASE_REGION_MAX_SIDE)
    var h = ceil(vis.height * k).toInt().coerceIn(1, ERASE_REGION_MAX_SIDE)
    // rounding up must not take it past the limit
    while (w.toLong() * h > maxPixels && (w > 1 || h > 1)) { if (w >= h) w-- else h-- }
    return EraseRegion(page, (pxPerPt * k).toFloat(), x, y, w, h)
}

/** The comments a Draw eraser on [page] can change (`comment_erase`): unlocked ink that is not a Fill & Sign mark. */
internal fun erasableInk(comments: List<io.github.artcraftandroid.pdfcraft.core.CommentInfo>, page: Int): List<Int> =
    comments.filter { it.page.toInt() == page && it.subtype == "Ink" && !it.locked && !it.fillSign }.map { it.index.toInt() }

/**
 * One live-erase gesture (#521): the page without its erasable ink ([image], once rendered) is
 * shown along the eraser's [path] (page points), so the ink goes as the eraser passes. With
 * [whole] (the "Erase whole strokes" eraser, #655) it is shown instead over each saved stroke the
 * eraser has touched ([hits]), so the whole stroke goes at once. The engine still makes the edit
 * once, on lift; this goes when that edit's page is drawn.
 */
internal class LiveErase(val region: EraseRegion, val radius: Float, private val startedAt: Long, val whole: Boolean = false) {
    var image by mutableStateOf<ImageBitmap?>(null)
    /** Page points, as the eraser went; drawn as one round-capped line [radius] wide on each side. */
    val path = androidx.compose.runtime.mutableStateListOf<Offset>()
    /** The page's generation when the eraser lifted; null while it is down. */
    var liftedGen: Long? = null
    var liftedAt = 0L
    var renderMs = -1L
    var readyBeforeLift = false
    /** The whole-stroke eraser's saved strokes and those it touched (#655); null for the other eraser. */
    val hits: WholeStrokeHits? = if (whole) WholeStrokeHits(radius) else null
    /** How many strokes [hits] hides: read where they are drawn, so each one goes on the next frame. */
    var hiddenCount by mutableIntStateOf(0)
        private set
    private var strokesReadMs = -1L
    private var strokesBeforeLift = false

    fun rendered(img: ImageBitmap?, now: Long) {
        image = img
        renderMs = now - startedAt
        readyBeforeLift = img != null && liftedGen == null
    }

    fun add(p: Offset) {
        if (path.size < 20_000) path.add(p)
    }

    /** [p] (page points) joins the path the edit gets, at [now]: the whole-stroke eraser tests that step (#655). */
    fun passed(p: Offset, now: Long) {
        val h = hits ?: return
        if (h.passed(p, now)) hiddenCount = h.hidden.size
    }

    /** The page's saved strokes, read at [now] (null: they could not be; those strokes then go when the edit lands). */
    fun strokesRead(strokes: List<ErasableInkStroke>?, now: Long) {
        val h = hits ?: return
        if (strokes == null) return
        strokesReadMs = now - startedAt
        strokesBeforeLift = liftedGen == null
        if (h.read(strokes, now)) hiddenCount = h.hidden.size
    }

    /** One `live_erase` line: whether the page under the ink was ready while erasing, and how long the real page took. */
    fun log(outcome: String, now: Long) {
        val fields = arrayListOf<Pair<String, Any?>>(
            "outcome" to outcome, "whole" to whole, "render_under_ms" to renderMs,
            "ready_before_lift" to (readyBeforeLift && (hits == null || strokesBeforeLift)),
            "lift_to_page_ms" to if (liftedAt > 0L) now - liftedAt else -1L, "points" to path.size, "pixels" to region.w.toLong() * region.h,
        )
        hits?.let { h ->
            // the strokes go once both the page under them and the strokes themselves are in
            val readyAt = if (image != null && h.readAt >= 0L) maxOf(startedAt + renderMs, h.readAt) else -1L
            fields += "strokes_read_ms" to strokesReadMs
            fields += "strokes" to (h.strokes?.size ?: -1)
            fields += "strokes_hidden" to h.hidden.size
            fields += "longest_wait_ms" to if (readyAt >= 0L) h.longestWait(readyAt) else -1L
        }
        Diag.event(DiagArea.Ink, "live_erase", *fields.toTypedArray())
    }
}

/**
 * A saved stroke the Draw eraser can take (#655): the drawing's [index] on its page, the stroke's
 * in the drawing, its [points] (page points) and half its width at each, as the engine's
 * `erase_ink_strokes` measures it.
 */
internal class ErasableInkStroke(val index: Int, val stroke: Int, val points: List<Offset>, val halfWidths: List<Float>) {
    /** Half its widest width: how far past its points it is painted at most. */
    val reach: Float = halfWidths.maxOfOrNull { it.coerceAtLeast(0f) } ?: 0f
    private val left = points.minOf { it.x }
    private val top = points.minOf { it.y }
    private val right = points.maxOf { it.x }
    private val bottom = points.maxOf { it.y }

    /** Its line as the engine draws ink (page points), built when it is first drawn. */
    val line: Path by lazy { inkLine(points) }

    /** Whether it may come within [margin] of the box [l], [t], [r], [b] (page points). */
    fun near(l: Float, t: Float, r: Float, b: Float, margin: Float): Boolean {
        val m = reach + margin
        return left - m <= r && right + m >= l && top - m <= b && bottom + m >= t
    }

    /** Whether its painted area, grown by [margin] (page points), may meet [other]'s. */
    fun meets(other: ErasableInkStroke, margin: Float): Boolean =
        other.near(left - reach - margin, top - reach - margin, right + reach + margin, bottom + reach + margin, margin)
}

/** A stroke as the engine gave it ([flat] is `x0, y0, x1, y1, …`); null when it has no points or they do not fit. */
internal fun erasableInkStroke(index: Int, stroke: Int, flat: List<Float>, halfWidths: List<Float>): ErasableInkStroke? {
    if (halfWidths.isEmpty() || flat.size != 2 * halfWidths.size) return null
    if (!flat.all { it.isFinite() } || !halfWidths.all { it.isFinite() }) return null
    return ErasableInkStroke(index, stroke, List(halfWidths.size) { Offset(flat[2 * it], flat[2 * it + 1]) }, halfWidths)
}

/**
 * Whether the eraser moving from [a] to [b] (page points, [radius] wide on each side) touches
 * [s]: the whole-stroke eraser's test (#655), the cut's own ([eraseInkRun]), which is the
 * engine's `erase_ink_strokes`: the radius plus half the stroke's width at each point.
 */
internal fun eraserTouchesStroke(s: ErasableInkStroke, a: Offset, b: Offset, radius: Float): Boolean =
    eraseInkRun(s.points, s.halfWidths, s.halfWidths, a, b, radius) != null

/**
 * Which of a page's saved strokes the whole-stroke eraser has touched so far (#655), by the edit's
 * own test along the edit's own path ([passed] gets each point the edit gets), so each is hidden
 * while the eraser is still down and the strokes hidden are the strokes the edit removes. The path
 * may start before the strokes are [read]; they are then tested along all of it.
 */
internal class WholeStrokeHits(private val radius: Float) {
    private val path = ArrayList<Offset>()
    private val times = ArrayList<Long>()
    var strokes: List<ErasableInkStroke>? = null
        private set
    /** When [strokes] were read; -1 until then. */
    var readAt = -1L
        private set
    private var touched = BooleanArray(0)
    /** The strokes touched, in the order the eraser reached them. */
    val hidden = ArrayList<ErasableInkStroke>()
    private val reachedAt = ArrayList<Long>()

    /** [p] joins the path at [now] (its first point is a step of no length); whether that step touched a stroke not touched before. */
    fun passed(p: Offset, now: Long): Boolean {
        val from = path.lastOrNull() ?: p
        path.add(p)
        times.add(now)
        return test(from, p, now)
    }

    /** The page's strokes, read at [now]: tested along the path so far. Whether any was touched. */
    fun read(list: List<ErasableInkStroke>, now: Long): Boolean {
        if (strokes != null) return false
        strokes = list
        readAt = now
        touched = BooleanArray(list.size)
        var any = false
        for (k in path.indices) if (test(path[maxOf(k - 1, 0)], path[k], times[k])) any = true
        return any
    }

    /** Whether the stroke at [i] in [strokes] is hidden. */
    fun isHidden(i: Int): Boolean = touched.getOrElse(i) { false }

    private fun test(a: Offset, b: Offset, at: Long): Boolean {
        val list = strokes ?: return false
        val l = minOf(a.x, b.x)
        val t = minOf(a.y, b.y)
        val r = maxOf(a.x, b.x)
        val bt = maxOf(a.y, b.y)
        var any = false
        for (i in list.indices) {
            if (touched[i] || !list[i].near(l, t, r, bt, radius)) continue
            if (eraserTouchesStroke(list[i], a, b, radius)) {
                touched[i] = true
                hidden.add(list[i])
                reachedAt.add(at)
                any = true
            }
        }
        return any
    }

    /** The longest a hidden stroke was still shown after the eraser reached it, the view being ready at [readyAt]: 0 when none waited. */
    fun longestWait(readyAt: Long): Long = reachedAt.maxOfOrNull { (readyAt - it).coerceAtLeast(0L) } ?: 0L
}

/** [pts] (page points) as the engine draws a stroke: a dot, a line, or a smooth curve through each point (Catmull-Rom, `smooth_segments`). */
internal fun inkLine(pts: List<Offset>): Path {
    val path = Path()
    val first = pts.firstOrNull() ?: return path
    path.moveTo(first.x, first.y)
    when (pts.size) {
        1 -> path.lineTo(first.x + 0.01f, first.y)
        2 -> path.lineTo(pts[1].x, pts[1].y)
        else -> for (i in 0 until pts.size - 1) {
            val p0 = pts[maxOf(i - 1, 0)]
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val p3 = pts[minOf(i + 2, pts.size - 1)]
            path.cubicTo(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f, p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f, p2.x, p2.y)
        }
    }
    return path
}

/** Screen pixels a hidden or kept stroke's area reaches past its painted edge: its anti-aliased rim. */
private const val WHOLE_ERASE_RIM_PX = 1.5f

/**
 * The whole-stroke eraser's live view (#655): each saved stroke it touched is covered by the page
 * without its erasable ink ([LiveErase.image]), along the stroke's own line and width; where a
 * stroke it did not touch is drawn too, the page is left as it is, so a stroke that crosses an
 * erased one stays whole. [pageOrigin] is the page's top-left on screen, [ppp] its pixels per point.
 */
internal fun DrawScope.drawWholeStrokeErase(le: LiveErase, pageOrigin: Offset, ppp: Float, colorFilter: ColorFilter?) {
    val h = le.hits ?: return
    val img = le.image ?: return
    val all = h.strokes ?: return
    if (le.hiddenCount == 0 || h.hidden.isEmpty() || !(ppp.isFinite() && ppp > 0f)) return
    val rim = WHOLE_ERASE_RIM_PX / ppp
    fun stroke(s: ErasableInkStroke) = Stroke(width = 2f * (s.reach + rim), cap = StrokeCap.Square, join = StrokeJoin.Round)
    val kept = all.filterIndexed { i, s -> !h.isHidden(i) && h.hidden.any { it.meets(s, rim) } }
    val r = le.region
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), Paint())
        withTransform({
            translate(pageOrigin.x, pageOrigin.y)
            scale(ppp, ppp, Offset.Zero)
        }) {
            for (s in h.hidden) drawPath(s.line, Color.Black, style = stroke(s))
            for (s in kept) drawPath(s.line, Color.Black, style = stroke(s), blendMode = BlendMode.Clear)
        }
        drawImage(
            img,
            dstOffset = IntOffset(Math.round(pageOrigin.x + r.x / r.scale * ppp), Math.round(pageOrigin.y + r.y / r.scale * ppp)),
            dstSize = IntSize(Math.round(r.w / r.scale * ppp), Math.round(r.h / r.scale * ppp)),
            blendMode = BlendMode.SrcIn, colorFilter = colorFilter,
        )
        canvas.restore()
    }
}

/** [points] (page points) on screen, as one path for a round-capped stroke. */
internal fun erasePathOnScreen(points: List<Offset>, toScreen: (Offset) -> Offset): Path {
    val path = Path()
    points.forEachIndexed { i, p ->
        val s = toScreen(p)
        if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y)
    }
    // a single tap still shows a dot: a zero-length line with round caps
    if (points.size == 1) toScreen(points[0]).let { path.lineTo(it.x + 0.01f, it.y) }
    return path
}

/** The PDF subtypes of the text markups (#660): Highlight, Underline, Strikethrough, Squiggly. */
internal val TEXT_MARKUP_SUBTYPES = setOf("Highlight", "Underline", "StrikeOut", "Squiggly")

/** A text markup the Draw eraser can remove: its index on the page and its line boxes (points). */
internal class ErasableMarkup(val index: Int, val boxes: List<FloatArray>, val key: List<Any?> = emptyList())

/** What tells a markup apart if the page's indices moved while the eraser was down (a long gesture erases ink in chunks). */
internal fun markupKey(c: io.github.artcraftandroid.pdfcraft.core.CommentInfo): List<Any?> = listOf(c.subtype, c.name, c.rect.toList(), c.lines.toList())

/** The indices on [page] now of the markups with [keys] (#660). */
internal fun markupIndicesNow(comments: List<io.github.artcraftandroid.pdfcraft.core.CommentInfo>, page: Int, keys: Collection<List<Any?>>): List<Int> =
    comments.filter { it.page.toInt() == page && markupKey(it) in keys }.map { it.index.toInt() }

/**
 * The text markups on [page] the Draw eraser removes whole when it touches one of their lines
 * (#660): unlocked, shown, not Fill & Sign. Each line is its own box ([CommentInfo.lines]), so the
 * blank end of a short line does not count; a markup with none uses its rectangle.
 */
internal fun erasableMarkups(comments: List<io.github.artcraftandroid.pdfcraft.core.CommentInfo>, page: Int): List<ErasableMarkup> =
    comments.filter { it.page.toInt() == page && it.subtype in TEXT_MARKUP_SUBTYPES && !it.locked && !it.fillSign && !it.hidden }
        .mapNotNull { c ->
            val flat = c.lines.takeIf { it.size >= 4 && it.size % 4 == 0 } ?: c.rect.takeIf { it.size == 4 } ?: return@mapNotNull null
            val boxes = flat.chunked(4).map { it.toFloatArray() }.filter { b -> b.all { it.isFinite() } && b[0] <= b[2] && b[1] <= b[3] }
            if (boxes.isEmpty()) null else ErasableMarkup(c.index.toInt(), boxes, markupKey(c))
        }

/**
 * Whether the eraser moving from [a] to [b] (page points), [radius] wide on each side, touches
 * [box] (`x0, y0, x1, y1`): the segment meets the box grown by the radius.
 */
internal fun eraserTouchesBox(a: Offset, b: Offset, radius: Float, box: FloatArray): Boolean {
    if (box.size < 4 || !radius.isFinite() || radius < 0f) return false
    val x0 = box[0] - radius
    val y0 = box[1] - radius
    val x1 = box[2] + radius
    val y1 = box[3] + radius
    // Liang-Barsky: clip the segment to the grown box
    var t0 = 0f
    var t1 = 1f
    val dx = b.x - a.x
    val dy = b.y - a.y
    for ((p, q) in listOf(-dx to a.x - x0, dx to x1 - a.x, -dy to a.y - y0, dy to y1 - a.y)) {
        if (p == 0f) {
            if (q < 0f) return false
        } else {
            val r = q / p
            if (p < 0f) { if (r > t1) return false; if (r > t0) t0 = r }
            else { if (r < t0) return false; if (r < t1) t1 = r }
        }
    }
    return t0 <= t1
}
