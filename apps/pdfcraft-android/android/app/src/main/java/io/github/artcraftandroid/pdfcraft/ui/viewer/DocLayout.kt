package io.github.artcraftandroid.pdfcraft.ui.viewer

import kotlin.math.max
import kotlin.math.min

/** A page's displayed size in PDF points. */
data class PageSize(val width: Float, val height: Float)

/** A rectangle in layout pixels (the document at zoom 1). */
data class LRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** How pages follow each other. */
enum class PageMode {
    /** One column, scrolled vertically; every page as wide as the widest fits the screen. */
    Continuous,

    /** One page at a time, side by side, each fitted to the screen; swipes go page to page. */
    SinglePage,

    /** One row, scrolled sideways; every page as tall as the tallest fits the screen (#152). */
    Horizontal,
}

/** How many pages sit side by side (#152). */
enum class Spread {
    /** One page per row (continuous) or per screen (single page). */
    Single,

    /** Pages two by two: 1-2, 3-4, … */
    TwoUp,

    /** The first page alone, as a book's cover, then 2-3, 4-5, … */
    TwoUpCover,
}

/** Two pages side by side need a window this wide (dp): tablets, landscape phones, foldables, desktops. */
const val SPREAD_MIN_WIDTH_DP = 600

/** Whether a window [widthDp] wide shows two pages side by side. */
fun spreadFits(widthDp: Int): Boolean = widthDp >= SPREAD_MIN_WIDTH_DP

/** The pages of each spread, in order. */
fun spreadGroups(count: Int, spread: Spread): List<IntRange> {
    if (count <= 0) return emptyList()
    return when (spread) {
        Spread.Single -> (0 until count).map { it..it }
        Spread.TwoUp -> (0 until count step 2).map { it..min(it + 1, count - 1) }
        Spread.TwoUpCover -> listOf(0..0) + (1 until count step 2).map { it..min(it + 1, count - 1) }
    }
}

/**
 * Where each page is at zoom 1, in layout pixels, for a viewport of [viewW] × [viewH] pixels.
 * Pure geometry, so it can be tested off the device.
 *
 * Continuous: the rows of pages ([spread]: one page, or two side by side) stack in one column
 * with [gap] between them, scaled together so the widest row fills the width less [margin] on
 * each side; [padTop] and [padBottom] keep the first and last pages clear of the bars.
 * SinglePage: each spread is fitted into the viewport (less the margins and paddings) and
 * centred in a slot one viewport wide.
 * Horizontal: the spreads follow each other left to right with [gap] between them, scaled
 * together so the tallest fits the height less the paddings and [margin] above and below.
 */
class DocLayout(
    val pages: List<PageSize>,
    val mode: PageMode,
    val viewW: Float,
    val viewH: Float,
    val gap: Float,
    val margin: Float,
    val padTop: Float,
    val padBottom: Float,
    val spread: Spread = Spread.Single,
) {
    val rects: List<LRect>
    val width: Float
    val height: Float

    /** The spreads: one row (continuous) or one slot (single page) each. */
    val groups: List<IntRange> = spreadGroups(pages.size, spread)

    /** The spread each page is in. */
    private val groupOf = IntArray(pages.size).also { a -> groups.forEachIndexed { g, r -> for (i in r) a[i] = g } }

    /** Each row's top and bottom (continuous), or each column's left and right (horizontal). */
    private val rowTop = FloatArray(groups.size)
    private val rowBottom = FloatArray(groups.size)

    init {
        val out = ArrayList<LRect>(pages.size)
        when (mode) {
            PageMode.Continuous -> {
                val widestRow = groups.maxOfOrNull { r -> r.sumOf { pages[it].width.coerceAtLeast(1f).toDouble() }.toFloat() }?.coerceAtLeast(1f) ?: 1f
                val most = groups.maxOfOrNull { it.count() } ?: 1
                val scale = max(1f, viewW - 2 * margin - (most - 1) * gap) / widestRow
                var y = padTop
                groups.forEachIndexed { g, r ->
                    val total = r.sumOf { (pages[it].width.coerceAtLeast(1f) * scale).toDouble() }.toFloat() + (r.count() - 1) * gap
                    var x = (viewW - total) / 2f
                    var rowH = 0f
                    for (i in r) {
                        val p = pages[i]
                        val w = p.width.coerceAtLeast(1f) * scale
                        val h = p.height.coerceAtLeast(1f) * scale
                        out.add(LRect(x, y, x + w, y + h))
                        x += w + gap
                        rowH = max(rowH, h)
                    }
                    rowTop[g] = y
                    rowBottom[g] = y + rowH
                    y += rowH + gap
                }
                width = viewW
                height = if (pages.isEmpty()) viewH else y - gap + padBottom
            }
            PageMode.SinglePage -> {
                val availH = max(1f, viewH - padTop - padBottom - 2 * margin)
                groups.forEachIndexed { g, r ->
                    val availW = max(1f, viewW - 2 * margin - (r.count() - 1) * gap)
                    val sumW = r.sumOf { pages[it].width.coerceAtLeast(1f).toDouble() }.toFloat()
                    val maxH = r.maxOf { pages[it].height.coerceAtLeast(1f) }
                    val s = min(availW / sumW, availH / maxH)
                    var x = g * viewW + (viewW - (sumW * s + (r.count() - 1) * gap)) / 2f
                    for (i in r) {
                        val p = pages[i]
                        val w = p.width.coerceAtLeast(1f) * s
                        val h = p.height.coerceAtLeast(1f) * s
                        val y = padTop + margin + (availH - h) / 2f
                        out.add(LRect(x, y, x + w, y + h))
                        x += w + gap
                    }
                }
                width = max(viewW, groups.size * viewW)
                height = viewH
            }
            PageMode.Horizontal -> {
                val availH = max(1f, viewH - padTop - padBottom - 2 * margin)
                val tallest = pages.maxOfOrNull { it.height.coerceAtLeast(1f) } ?: 1f
                val scale = availH / tallest
                var x = margin
                groups.forEachIndexed { g, r ->
                    rowTop[g] = x
                    for (i in r) {
                        val p = pages[i]
                        val w = p.width.coerceAtLeast(1f) * scale
                        val h = p.height.coerceAtLeast(1f) * scale
                        val y = padTop + margin + (availH - h) / 2f
                        out.add(LRect(x, y, x + w, y + h))
                        x += w + gap
                    }
                    rowBottom[g] = x - gap
                }
                width = if (pages.isEmpty()) viewW else x - gap + margin
                height = viewH
            }
        }
        rects = out
    }

    /** The spread page [i] is in. */
    fun groupOf(i: Int): Int = if (groupOf.isEmpty()) 0 else groupOf[i.coerceIn(0, groupOf.lastIndex)]

    /** Pixels per PDF point of page [i] at zoom 1. */
    fun scaleOf(i: Int): Float = rects[i].width / pages[i].width.coerceAtLeast(1f)

    /** The pages that overlap layout rows [top]..[bottom] (and columns [left]..[right]). */
    fun visible(left: Float, top: Float, right: Float, bottom: Float): IntRange {
        if (rects.isEmpty()) return IntRange.EMPTY
        return when (mode) {
            PageMode.Continuous -> {
                val first = firstRowAtOrBelow(top)
                var last = first
                while (last + 1 < groups.size && rowTop[last + 1] <= bottom) last++
                groups[first].first..groups[last].last
            }
            PageMode.Horizontal -> {
                val first = firstRowAtOrBelow(left)
                var last = first
                while (last + 1 < groups.size && rowTop[last + 1] <= right) last++
                groups[first].first..groups[last].last
            }
            PageMode.SinglePage -> {
                val first = (left / viewW).toInt().coerceIn(0, groups.lastIndex)
                val last = (right / viewW).toInt().coerceIn(0, groups.lastIndex)
                groups[first].first..groups[last].last
            }
        }
    }

    /** The first row (column, horizontally) whose far edge is at or past layout [y] (or x; binary search). */
    private fun firstRowAtOrBelow(y: Float): Int {
        var lo = 0
        var hi = groups.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (rowBottom[mid] + gap < y) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** The page under layout point ([x], [y]), if any. */
    fun pageAt(x: Float, y: Float): Int? {
        val range = visible(x, y, x, y)
        return range.firstOrNull { rects[it].contains(x, y) }
    }

    /** The page of spread [g] nearest layout column [x] (the first on a tie). */
    private fun nearestIn(g: Int, x: Float): Int {
        val r = groups[g]
        if (r.first == r.last) return r.first
        return r.minBy { i -> val p = rects[i]; if (x < p.left) p.left - x else if (x > p.right) x - p.right else 0f }
    }

    /** The page nearest layout point ([x], [y]): the one under it, else the closest. */
    fun nearestPage(x: Float, y: Float): Int {
        if (rects.isEmpty()) return 0
        return when (mode) {
            PageMode.Continuous -> {
                val g = firstRowAtOrBelow(y)
                val row = when {
                    // in the gap below row g: whichever edge is closer
                    y > rowBottom[g] && g + 1 < groups.size -> if (rowTop[g + 1] - y < y - rowBottom[g]) g + 1 else g
                    y < rowTop[g] && g > 0 -> if (rowTop[g] - y > y - rowBottom[g - 1]) g - 1 else g
                    else -> g
                }
                nearestIn(row, x)
            }
            PageMode.SinglePage -> nearestIn((x / viewW).toInt().coerceIn(0, groups.lastIndex), x)
            PageMode.Horizontal -> {
                val g = firstRowAtOrBelow(x)
                val col = when {
                    // in the gap after column g: whichever edge is closer
                    x > rowBottom[g] && g + 1 < groups.size -> if (rowTop[g + 1] - x < x - rowBottom[g]) g + 1 else g
                    x < rowTop[g] && g > 0 -> if (rowTop[g] - x > x - rowBottom[g - 1]) g - 1 else g
                    else -> g
                }
                nearestIn(col, x)
            }
        }
    }
}
