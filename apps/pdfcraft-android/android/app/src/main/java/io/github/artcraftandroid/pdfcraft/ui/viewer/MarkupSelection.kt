package io.github.artcraftandroid.pdfcraft.ui.viewer

import io.github.artcraftandroid.pdfcraft.core.TextRange

/** A selection may span many lines, but cannot produce unbounded native PDF quads. */
internal const val MAX_MARKUP_LINE_RECTS = 1024
internal const val MAX_MARKUP_GLYPHS = 100_000u

/** Return no partial selection if the native line rectangle payload is malformed or oversized. */
internal fun markupLineRects(flat: List<Float>): List<FloatArray> {
    if (flat.isEmpty() || flat.size % 4 != 0 || flat.size / 4 > MAX_MARKUP_LINE_RECTS)
        return emptyList()
    val rects = ArrayList<FloatArray>(flat.size / 4)
    for (i in flat.indices step 4) {
        val x0 = flat[i]
        val y0 = flat[i + 1]
        val x1 = flat[i + 2]
        val y1 = flat[i + 3]
        if (!x0.isFinite() || !y0.isFinite() || !x1.isFinite() || !y1.isFinite() ||
            x0 >= x1 || y0 >= y1) return emptyList()
        rects += floatArrayOf(x0, y0, x1, y1)
    }
    return rects
}

/**
 * The letters a markup drag covers (#657): from glyph [a] to glyph [b] in reading order, both
 * included, whichever way the drag went. Null when they are not on one page or the range is too long.
 */
internal fun markupLetterRange(page: UInt, a: UInt, b: UInt): TextRange? {
    val start = minOf(a, b)
    val end = maxOf(a, b) + 1u
    if (end - start > MAX_MARKUP_GLYPHS) return null
    return TextRange(page, start, end)
}

/** How far beside a line, in points, a markup drag may start or end and still take its nearest letter (#657): about a page margin. */
internal const val MARKUP_SIDE_REACH_PT = 72f

/**
 * Whether a drag's start or end at ([x], [y]) is close enough to the glyph [rect] it found to take
 * it (#657): beside the line up to [side] points (the margin, the gap before a short line), but
 * above or below it only up to [vertical] points, so a drag between two columns or far under the
 * text does not reach them.
 */
internal fun markupNearLine(x: Float, y: Float, rect: List<Float>, side: Float, vertical: Float): Boolean {
    if (!x.isFinite() || !y.isFinite() || !side.isFinite() || !vertical.isFinite() || side < 0f || vertical < 0f ||
        rect.size != 4 || rect.any { !it.isFinite() }) return false
    val left = minOf(rect[0], rect[2])
    val right = maxOf(rect[0], rect[2])
    val top = minOf(rect[1], rect[3])
    val bottom = maxOf(rect[1], rect[3])
    if (left >= right || top >= bottom) return false
    return x >= left - side && x <= right + side && y >= top - vertical && y <= bottom + vertical
}

/** The box around a markup's line rectangles, as [x0, y0, x1, y1]; null when there are none or one is not finite. */
internal fun markupBounds(rects: List<FloatArray>): FloatArray? {
    if (rects.isEmpty() || rects.any { r -> r.size < 4 || r.any { !it.isFinite() } }) return null
    return floatArrayOf(rects.minOf { it[0] }, rects.minOf { it[1] }, rects.maxOf { it[2] }, rects.maxOf { it[3] })
}

/**
 * Where the bar by a new markup goes (#197, #650), as its top in screen pixels: [top] and [bottom]
 * of the markup, the bar's [height], the [margin] between them, and the page area from [minTop]
 * to [maxBottom]. After the selection menu it goes below the markup when it fits, else above. In
 * the Highlight tool ([preferAbove]) it goes above when it fits under the top bar, so it does not
 * cover the lines still to be marked, else below. It never leaves the page area.
 */
internal fun followUpBarTop(top: Float, bottom: Float, height: Float, margin: Float, minTop: Float, maxBottom: Float, preferAbove: Boolean): Float {
    val above = top - margin - height
    val below = bottom + margin
    val fitsAbove = above >= minTop
    val fitsBelow = below + height < maxBottom
    val y = when {
        preferAbove && fitsAbove -> above
        preferAbove -> below
        fitsBelow -> below
        else -> above
    }
    return y.coerceIn(minTop, (maxBottom - height).coerceAtLeast(minTop))
}

/** The colours one tap away on Highlight's row (#650), as ARGB: yellow, green, light blue, pink and red of the comment palette. */
internal val MARKUP_QUICK_COLORS = listOf(0xFFFFD100, 0xFF6AD928, 0xFF38E5FF, 0xFFFF809D, 0xFFE52237)

/**
 * How many of them Highlight's row has room for at a window [widthDp] wide, beside its four
 * tools and the colour button (about 276 dp), each 36 dp wide: three on a 384 dp phone, one at
 * 320 dp, all five from 456 dp.
 */
internal fun markupQuickColorCount(widthDp: Int): Int = ((widthDp - 276) / 36).coerceIn(0, MARKUP_QUICK_COLORS.size)

