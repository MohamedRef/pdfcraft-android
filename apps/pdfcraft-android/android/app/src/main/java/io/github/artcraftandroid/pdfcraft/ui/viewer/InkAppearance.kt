package io.github.artcraftandroid.pdfcraft.ui.viewer

/** Immutable appearance captured when ink begins, retained through undo/redo and save. */
internal data class InkAppearance(val argb: Int, val opacity: Float, val width: Float, val author: String?)

/** Merge only adjacent equal appearances; globally grouping red/blue/red changes paint order. */
internal fun <T, K> consecutiveInkBatches(items: List<T>, key: (T) -> K): List<List<T>> {
    val batches = ArrayList<MutableList<T>>()
    for (item in items) {
        val last = batches.lastOrNull()
        if (last != null && key(last.last()) == key(item)) last.add(item)
        else batches.add(arrayListOf(item))
    }
    return batches
}
