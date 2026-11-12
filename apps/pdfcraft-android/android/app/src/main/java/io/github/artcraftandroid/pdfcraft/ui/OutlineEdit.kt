package io.github.artcraftandroid.pdfcraft.ui

/**
 * One embedded bookmark as `bookmark_list` gives it: [path] is 1-based positions from the top
 * level (what the other bookmark tools take), [page] 0-based, [color] r, g, b in 0..1 when set.
 */
data class OutlineRow(
    val path: List<Int>,
    val title: String,
    val page: Int?,
    val color: List<Float>? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
) {
    val depth: Int get() = path.size - 1
}

/** Where Move up / down / in / out takes a bookmark (the TalkBack-friendly alternative to dragging). */
enum class OutlineMove { Up, Down, In, Out }

/** How many bookmarks share the parent [parent] (empty: the top level). */
fun siblingCount(rows: List<OutlineRow>, parent: List<Int>): Int =
    rows.count { it.path.size == parent.size + 1 && it.path.subList(0, parent.size) == parent }

/**
 * The `bookmark_move` arguments for [move] of the bookmark at [path], or null when it can't move
 * that way. Positions are 1-based and, as the tool counts them, taken after the bookmark is removed.
 * - Up / Down: the same parent, one place earlier or later.
 * - In: the last child of the bookmark above it.
 * - Out: right after its parent, in the parent's level.
 */
fun outlineMoveArgs(rows: List<OutlineRow>, path: List<Int>, move: OutlineMove): Map<String, Any?>? {
    if (path.isEmpty()) return null
    val k = path.last()
    val parent = path.dropLast(1)
    fun args(newParent: List<Int>, position: Int?): Map<String, Any?> = buildMap {
        put("path", path)
        if (newParent.isNotEmpty()) put("parent", newParent)
        if (position != null) put("position", position)
    }
    return when (move) {
        OutlineMove.Up -> if (k > 1) args(parent, k - 1) else null
        OutlineMove.Down -> if (k < siblingCount(rows, parent)) args(parent, k + 1) else null
        OutlineMove.In -> if (k > 1) args(parent + (k - 1), null) else null
        OutlineMove.Out -> if (parent.isNotEmpty()) args(parent.dropLast(1), parent.last() + 1) else null
    }
}

/** The rows without the bookmark at [path] and its children: what a drag moves among. */
fun outlineRowsWithout(rows: List<OutlineRow>, path: List<Int>): List<OutlineRow> =
    rows.filterNot { it.path.size >= path.size && it.path.subList(0, path.size) == path }

/**
 * The levels a bookmark dropped before `rest[slot]` can take, where `rest` is
 * [outlineRowsWithout] (slot == rest.size: after the last row): no deeper than one under the row
 * above it, and no shallower than the row below it, so the rows below keep their parents.
 */
fun outlineDropDepths(rest: List<OutlineRow>, slot: Int): IntRange {
    val above = rest.getOrNull(slot - 1)
    val below = rest.getOrNull(slot)
    val max = above?.let { it.depth + 1 } ?: 0
    val min = (below?.depth ?: 0).coerceAtMost(max)
    return min..max
}

/**
 * The `bookmark_move` arguments for dragging the bookmark at [path] (with its children) to just
 * before `rest[slot]` at level [depth] (clamped to [outlineDropDepths]), or null when that puts it
 * back where it was. As the tool takes them, `parent` is a path in the outline as it is now and
 * `position` is 1-based among the parent's children once the bookmark is taken out.
 */
fun outlineDropArgs(rows: List<OutlineRow>, path: List<Int>, slot: Int, depth: Int): Map<String, Any?>? {
    if (path.isEmpty() || rows.none { it.path == path }) return null
    val rest = outlineRowsWithout(rows, path)
    val at = slot.coerceIn(0, rest.size)
    val d = depth.coerceIn(outlineDropDepths(rest, at))
    // the parent is the nearest row above at one level up (none: the top level)
    val parent = if (d == 0) emptyList() else rest.subList(0, at).lastOrNull { it.depth == d - 1 }?.path ?: return null
    val position = 1 + rest.subList(0, at).count { it.path.size == parent.size + 1 && it.path.subList(0, parent.size) == parent }
    if (parent == path.dropLast(1) && position == path.last()) return null
    return buildMap {
        put("path", path)
        if (parent.isNotEmpty()) put("parent", parent)
        put("position", position)
    }
}

/** Bookmark title colours offered by Style, as r, g, b (black is the PDF default). */
val OUTLINE_COLORS: List<List<Float>> = listOf(
    listOf(0f, 0f, 0f),
    listOf(0.8f, 0f, 0f),
    listOf(0.9f, 0.45f, 0f),
    listOf(0f, 0.5f, 0f),
    listOf(0f, 0.3f, 0.8f),
    listOf(0.5f, 0f, 0.6f),
)
