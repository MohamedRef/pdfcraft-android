package io.github.artcraftandroid.pdfcraft.ui.scan

import kotlin.math.roundToInt

/*
 * The document screen (#460, part of #455): the scan's pages in a grid, where they are put in
 * order, chosen, deleted, turned, and saved or shared. Behind the testing option "Scan screens in
 * steps". What can be worked out without a screen is here, so it can be tested on the desktop.
 */

/** Where page [from] goes for TalkBack's Move earlier (-1) or Move later (+1); null when it is already at that end. */
internal fun scanMoveTarget(from: Int, step: Int, size: Int): Int? {
    val to = from + step
    return if (from in 0 until size && to in 0 until size && to != from) to else null
}

/** [list] with the item at [from] taken out and put back at [to]; unchanged when either is outside it. */
internal fun <T> scanMoved(list: List<T>, from: Int, to: Int): List<T> {
    if (from !in list.indices || to !in list.indices || from == to) return list
    val out = list.toMutableList()
    out.add(to, out.removeAt(from))
    return out
}

/** The pages still chosen once pages have gone or been retaken: those whose key is still in the scan. */
internal fun scanSelectionKept(selected: Set<String>, keys: List<String>): Set<String> =
    if (selected.all { it in keys }) selected else selected.filterTo(LinkedHashSet()) { it in keys }

/** The indexes of the chosen pages, in page order. */
internal fun scanSelectedIndexes(selected: Set<String>, keys: List<String>): List<Int> = keys.indices.filter { keys[it] in selected }

/** The log's line for a page moved (#460): [from] and [to] counted from 1; [by] is `drag` or `talkback`. */
internal fun scanReorderDiag(from: Int, to: Int, pages: Int, by: String): Array<Pair<String, Any?>> =
    arrayOf("from" to from + 1, "to" to to + 1, "pages" to pages, "by" to by)

/** The log's line for pages deleted together on the document screen (#460). */
internal fun scanPagesDeleteDiag(count: Int, pages: Int, confirmed: Boolean): Array<Pair<String, Any?>> =
    arrayOf("count" to count, "pages" to pages, "confirmed" to confirmed)

/** The log's line for a scan shared (#460): the PDF's pages and size, and how long it took to make. */
internal fun scanShareDiag(pages: Int, bytes: Long, ms: Long): Array<Pair<String, Any?>> =
    arrayOf("pages" to pages, "bytes" to bytes, "ms" to ms)

/**
 * Whether the camera closing (Done, the thumbnail, or Close with pages taken) leads to the
 * document screen: with the steps on, when no Adjust borders follows and it was not a retake
 * (a retake goes back to the screen it came from).
 */
internal fun scanDocumentAfterCamera(steps: Boolean, retaking: Boolean, opener: ScanBordersOpener?): Boolean =
    steps && !retaking && opener == null

/**
 * The part of a [width] × [height] thumbnail a page's [corners] (shares of the photo, x then y)
 * cover: left, top, width, height in pixels, at least 1 × 1. The grid shows that part, so a page
 * looks like the page and not the table it lay on.
 */
internal fun scanThumbRegion(corners: FloatArray, width: Int, height: Int): IntArray {
    if (width <= 0 || height <= 0) return intArrayOf(0, 0, 1, 1)
    if (corners.size != 8 || corners.any { !it.isFinite() }) return intArrayOf(0, 0, width, height)
    val xs = (0 until 4).map { corners[2 * it].coerceIn(0f, 1f) * width }
    val ys = (0 until 4).map { corners[2 * it + 1].coerceIn(0f, 1f) * height }
    val left = xs.min().roundToInt().coerceIn(0, width - 1)
    val top = ys.min().roundToInt().coerceIn(0, height - 1)
    val right = xs.max().roundToInt().coerceIn(left + 1, width)
    val bottom = ys.max().roundToInt().coerceIn(top + 1, height)
    return intArrayOf(left, top, right - left, bottom - top)
}

/** The index a drag dropped on: the cell under the finger at ([x], [y]), or null over a gap. Cells are left, top, width, height. */
internal fun scanDropIndex(cells: List<Pair<Int, IntArray>>, x: Float, y: Float): Int? =
    cells.firstOrNull { (_, r) -> x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3] }?.first

/** A page's name in the gallery (#470): the scan's name and its page number from 1, `.png` for a page made as PNG. */
internal fun scanGalleryName(base: String, index: Int, png: Boolean): String = "$base - ${index + 1}.${if (png) "png" else "jpg"}"

/**
 * The log's line for pages saved to the gallery (#470): how many were asked for, saved and
 * failed, which failed (from 1), their size, the time taken, whether only selected pages were
 * saved, and whether Cancel stopped it.
 */
internal fun scanGalleryDiag(pages: Int, saved: Int, failed: List<Int>, bytes: Long, ms: Long, selected: Boolean, cancelled: Boolean): Array<Pair<String, Any?>> =
    arrayOf(
        "pages" to pages, "saved" to saved, "failed" to failed.size, "failed_pages" to failed.joinToString(",").ifEmpty { "none" },
        "bytes" to bytes, "ms" to ms, "selected" to selected, "cancelled" to cancelled,
    )
