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

/** Bookmark title colours offered by Style, as r, g, b (black is the PDF default). */
val OUTLINE_COLORS: List<List<Float>> = listOf(
    listOf(0f, 0f, 0f),
    listOf(0.8f, 0f, 0f),
    listOf(0.9f, 0.45f, 0f),
    listOf(0f, 0.5f, 0f),
    listOf(0f, 0.3f, 0.8f),
    listOf(0.5f, 0f, 0.6f),
)
