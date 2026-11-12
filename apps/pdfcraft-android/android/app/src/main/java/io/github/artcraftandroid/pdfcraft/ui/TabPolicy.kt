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
