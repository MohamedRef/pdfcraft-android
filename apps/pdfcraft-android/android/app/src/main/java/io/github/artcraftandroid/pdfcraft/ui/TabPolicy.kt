package io.github.artcraftandroid.pdfcraft.ui

/**
 * The rules behind document tabs (#138) and split view (#139), kept free of Android and the
 * engine so they can be unit tested.
 */
internal object TabPolicy {
    /** Documents kept open in the engine at once (the one in front included); older tabs are parked. */
    const val MAX_HOT = 4

    /** The tab to show after the tab at [closed] (of [size]) closes: the next one, else the one before. */
    fun nextAfterClose(size: Int, closed: Int): Int? {
        if (size <= 1 || closed !in 0 until size) return null
        // after removal the tab that was at closed + 1 sits at closed
        return if (closed < size - 1) closed else closed - 1
    }

    /** Ctrl+Tab ([step] 1) and Ctrl+Shift+Tab ([step] -1) from [current] of [size], wrapping round. */
    fun cycle(size: Int, current: Int, step: Int): Int? {
        if (size <= 1) return null
        val from = if (current in 0 until size) current else 0
        return Math.floorMod(from + step, size)
    }

    /**
     * Which of the open (hot) documents to park so at most [maxHot] stay open. [lastUsed] is when
     * each was last in front; [keep] are never parked (the one in front, the split pane's, one
     * whose changes did not reach the original). The least recently used go first.
     */
    fun <T> toPark(hot: List<T>, lastUsed: (T) -> Long, keep: (T) -> Boolean, maxHot: Int = MAX_HOT): List<T> {
        val extra = hot.size - maxHot
        if (extra <= 0) return emptyList()
        return hot.filterNot(keep).sortedBy(lastUsed).take(extra)
    }

    /** Where a newly opened tab goes: right after the tab in front, else at the end. */
    fun insertAt(size: Int, active: Int?): Int = if (active != null && active in 0 until size) active + 1 else size

    /** The split's share of the width (or height) the main pane keeps, within limits both panes stay usable at. */
    fun clampSplit(fraction: Float): Float = fraction.coerceIn(0.25f, 0.75f)

    /** Side by side when the window is wider than tall, else one above the other. */
    fun sideBySide(widthDp: Int, heightDp: Int): Boolean = widthDp >= heightDp

    /** The open tabs as saved across a restart: one `uri<TAB>title` per line. */
    fun encode(tabs: List<Pair<String, String>>): String =
        tabs.joinToString("\n") { (uri, title) -> uri.replace('\n', ' ').replace('\t', ' ') + "\t" + title.replace('\n', ' ').replace('\t', ' ') }

    fun decode(saved: String?): List<Pair<String, String>> =
        saved.orEmpty().split('\n').mapNotNull { line ->
            val cut = line.indexOf('\t')
            if (cut <= 0) return@mapNotNull null
            val uri = line.substring(0, cut)
            if (!uri.startsWith("content:") && !uri.startsWith("file:")) return@mapNotNull null
            uri to line.substring(cut + 1)
        }.distinctBy { it.first }
}

/** Where a dropped snip goes (#139). */
internal object SplitDrop {
    /** Pixels per point a snip without text is copied at (144 dpi). */
    const val SNIP_SCALE = 2f

    /**
     * The box [w] × [h] points for a drop at ([x], [y]) on a page [pageW] × [pageH]: its top-left
     * at the point, or [centred] on it, shrunk to fit and kept on the page.
     */
    fun place(x: Float, y: Float, w: Float, h: Float, pageW: Float, pageH: Float, centred: Boolean): FloatArray {
        var bw = w.coerceAtLeast(8f)
        var bh = h.coerceAtLeast(8f)
        val fit = minOf(1f, pageW / bw, pageH / bh)
        bw *= fit
        bh *= fit
        val x0 = (if (centred) x - bw / 2f else x).coerceIn(0f, (pageW - bw).coerceAtLeast(0f))
        val y0 = (if (centred) y - bh / 2f else y).coerceIn(0f, (pageH - bh).coerceAtLeast(0f))
        return floatArrayOf(x0, y0, x0 + bw, y0 + bh)
    }

    /** The area a drag from [a] to [b] marks (points on one page), or null when too small to mean anything. */
    fun area(ax: Float, ay: Float, bx: Float, by: Float, min: Float = 6f): FloatArray? {
        val r = floatArrayOf(minOf(ax, bx), minOf(ay, by), maxOf(ax, bx), maxOf(ay, by))
        return r.takeIf { it[2] - it[0] >= min && it[3] - it[1] >= min }
    }
}
