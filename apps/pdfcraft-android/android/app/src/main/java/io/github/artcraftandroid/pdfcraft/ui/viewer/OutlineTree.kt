package io.github.artcraftandroid.pdfcraft.ui.viewer

/**
 * The contents as a tree that opens in place (#647). The outline comes from the engine flattened
 * in reading order, each entry with its level (0 for a chapter): everything here works on those
 * levels and on the entries' pages, so it can be tested without a document.
 */

/**
 * The entries to show, as indexes into the outline: every top-level entry, and the children of
 * each entry in [expanded] whose own ancestors are all open. An entry deeper than one below the
 * entry before it (a damaged outline) is shown under that entry.
 */
internal fun outlineVisible(levels: List<Int>, expanded: Set<Int>): List<Int> {
    val shown = ArrayList<Int>()
    // while a folded entry's children are passed: the level at which entries show again
    var hiddenBelow = Int.MAX_VALUE
    for (i in levels.indices) {
        val level = levels[i].coerceAtLeast(0)
        if (level > hiddenBelow) continue
        shown += i
        hiddenBelow = if (i in expanded) Int.MAX_VALUE else level
    }
    return shown
}

/** Whether entry [index] has entries under it: the next entry is deeper. */
internal fun outlineHasChildren(levels: List<Int>, index: Int): Boolean =
    index in levels.indices && index + 1 < levels.size && levels[index + 1] > levels[index]

/** The entries above [index] that must be open for it to show, the chapter first. */
internal fun outlineAncestors(levels: List<Int>, index: Int): List<Int> {
    if (index !in levels.indices) return emptyList()
    val up = ArrayList<Int>()
    var level = levels[index]
    var k = index - 1
    while (k >= 0 && level > 0) {
        if (levels[k] < level) {
            up += k
            level = levels[k]
        }
        k--
    }
    return up.asReversed()
}

/**
 * The entry the reader is in on [page] (0-based): the last entry, in reading order, of those
 * whose page is the nearest at or before [page]. -1 before the first entry's page, or when no
 * entry goes to a page.
 */
internal fun outlineCurrent(pages: List<Int?>, page: Int): Int {
    var best = -1
    var bestPage = -1
    for ((i, p) in pages.withIndex()) {
        if (p == null || p > page) continue
        if (p >= bestPage) {
            best = i
            bestPage = p
        }
    }
    return best
}

/** [expanded] with [index] opened or folded; folding also folds what is open under it. */
internal fun outlineToggled(levels: List<Int>, expanded: Set<Int>, index: Int): Set<Int> {
    if (index !in levels.indices) return expanded
    if (index !in expanded) return expanded + index
    val gone = HashSet<Int>()
    gone += index
    var k = index + 1
    while (k < levels.size && levels[k] > levels[index]) {
        gone += k
        k++
    }
    return expanded - gone
}

/** Every entry with entries under it, for "Open all". */
internal fun outlineAllParents(levels: List<Int>): Set<Int> = levels.indices.filter { outlineHasChildren(levels, it) }.toSet()

/** How far an entry of [level] is set in, in dp: a step per level, no deeper than the fifth so a long title keeps its width. */
internal fun outlineIndentDp(level: Int): Int = level.coerceIn(0, 5) * 16

/** The window is wide enough for the top bar to hold Share beside Contents (#647); narrower, Share is in More. */
internal fun topBarHoldsShare(widthDp: Int): Boolean = widthDp >= 412
