package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
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
 * shown along the eraser's [path] (page points), so the ink goes as the eraser passes. The
 * engine still makes the edit once, on lift; this goes when that edit's page is drawn.
 */
internal class LiveErase(val region: EraseRegion, val radius: Float, private val startedAt: Long) {
    var image by mutableStateOf<ImageBitmap?>(null)
    /** Page points, as the eraser went; drawn as one round-capped line [radius] wide on each side. */
    val path = androidx.compose.runtime.mutableStateListOf<Offset>()
    /** The page's generation when the eraser lifted; null while it is down. */
    var liftedGen: Long? = null
    var liftedAt = 0L
    var renderMs = -1L
    var readyBeforeLift = false

    fun rendered(img: ImageBitmap?, now: Long) {
        image = img
        renderMs = now - startedAt
        readyBeforeLift = img != null && liftedGen == null
    }

    fun add(p: Offset) {
        if (path.size < 20_000) path.add(p)
    }

    /** One `live_erase` line: whether the page under the ink was ready while erasing, and how long the real page took. */
    fun log(outcome: String, now: Long) {
        Diag.event(
            DiagArea.Ink, "live_erase",
            "outcome" to outcome, "render_under_ms" to renderMs, "ready_before_lift" to readyBeforeLift,
            "lift_to_page_ms" to if (liftedAt > 0L) now - liftedAt else -1L, "points" to path.size, "pixels" to region.w.toLong() * region.h,
        )
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
