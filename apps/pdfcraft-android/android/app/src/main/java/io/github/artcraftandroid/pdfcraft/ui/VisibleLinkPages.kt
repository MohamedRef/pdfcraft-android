package io.github.artcraftandroid.pdfcraft.ui

/**
 * Bound work to visible pages; a 10,000-page PDF should not fetch 10,000 link tables at open.
 * Called from the viewer on viewport changes.
 */
internal fun missingVisibleLinkPages(
    visible: IntRange,
    pageCount: Int,
    alreadyLoaded: Set<Int>,
    maxBatch: Int = 12,
): List<Int> =
    if (pageCount <= 0 || maxBatch <= 0) emptyList()
    else (maxOf(0, visible.first)..minOf(pageCount - 1, visible.last)).asSequence().filter { it !in alreadyLoaded }
        .take(maxBatch).toList()
