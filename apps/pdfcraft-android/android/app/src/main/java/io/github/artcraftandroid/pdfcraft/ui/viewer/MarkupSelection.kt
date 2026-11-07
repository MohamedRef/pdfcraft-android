package io.github.artcraftandroid.pdfcraft.ui.viewer

import io.github.artcraftandroid.pdfcraft.core.TextRange

/** A selection may span many lines, but cannot produce unbounded native PDF quads. */
internal const val MAX_MARKUP_LINE_RECTS = 1024
internal const val MAX_MARKUP_GLYPHS = 100_000u

/** Include complete words at both ends, including for right-to-left or upward drags. */
internal fun markupWordRange(anchor: TextRange?, focus: TextRange?): TextRange? {
    if (anchor == null || focus == null || anchor.page != focus.page ||
        anchor.start >= anchor.end || focus.start >= focus.end) return null
    val start = minOf(anchor.start, focus.start)
    val end = maxOf(anchor.end, focus.end)
    if (end - start > MAX_MARKUP_GLYPHS) return null
    return TextRange(anchor.page, start, end)
}

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
 * A small additional tolerance is allowed only for the drag endpoint. Never
 * accept a distant nearestGlyph result, such as text in another column.
 * Reject truncated or nonfinite native glyph geometry before indexing it.
 */
internal fun markupEndpointNearGlyph(x: Float, y: Float, rect: List<Float>, tolerance: Float): Boolean {
    if (!x.isFinite() || !y.isFinite() || !tolerance.isFinite() || tolerance < 0f ||
        rect.size != 4 || rect.any { !it.isFinite() }) return false
    val left = minOf(rect[0], rect[2])
    val right = maxOf(rect[0], rect[2])
    val top = minOf(rect[1], rect[3])
    val bottom = maxOf(rect[1], rect[3])
    if (left >= right || top >= bottom) return false
    return x >= left - tolerance && x <= right + tolerance &&
        y >= top - tolerance && y <= bottom + tolerance
}
