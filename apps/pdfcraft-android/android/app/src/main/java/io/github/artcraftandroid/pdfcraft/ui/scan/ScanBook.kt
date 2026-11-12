package io.github.artcraftandroid.pdfcraft.ui.scan

import java.util.Locale
import org.json.JSONObject

/** The order the two pages of a book spread go into the scan (#303). */
enum class ScanBookOrder(val id: String) {
    LeftToRight("ltr"),
    RightToLeft("rtl"),
    ;

    companion object {
        /** The default: right to left when the app is laid out right to left (Arabic), else left to right. */
        fun default(rtl: Boolean): ScanBookOrder = if (rtl) RightToLeft else LeftToRight

        fun of(id: String?): ScanBookOrder? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A page that is one half of a photographed book spread (#303): which half, and how the spread
 * was cut. [gutterTop] and [gutterBottom] are where the cut crosses the straightened spread's top
 * and bottom edges (shares of its width); [gutterFound] is false when the cut is the centre line
 * (no dark gutter, or the gutter search was switched off for testing).
 */
data class ScanBookHalf(
    val left: Boolean,
    val gutterFound: Boolean,
    val gutterTop: Float,
    val gutterBottom: Float,
    val confidence: Float,
    val order: ScanBookOrder,
) {
    val side: String get() = if (left) "left" else "right"

    /** One field of a saved scan session (see [decode]). */
    fun encode(): String = listOf(side, if (gutterFound) "1" else "0", num(gutterTop), num(gutterBottom), num(confidence), order.id).joinToString(",")

    /** For a kept scan's page file. */
    fun json(): JSONObject = JSONObject().put("half", side).put("gutter_found", gutterFound)
        .put("gutter_top", round(gutterTop)).put("gutter_bottom", round(gutterBottom)).put("confidence", round(confidence)).put("order", order.id)

    companion object {
        private fun num(v: Float) = String.format(Locale.US, "%.5f", if (v.isFinite()) v else 0f)
        private fun round(v: Float): Double = if (v.isFinite()) Math.round(v * 10000.0) / 10000.0 else 0.0

        /** The half written by [encode]; null for anything else (an empty field is a whole page). */
        fun decode(text: String?): ScanBookHalf? {
            val p = text?.split(',')?.takeIf { it.size == 6 } ?: return null
            val left = when (p[0]) { "left" -> true; "right" -> false; else -> return null }
            val numbers = p.subList(2, 5).map { it.toFloatOrNull()?.takeIf { v -> v.isFinite() } ?: return null }
            val order = ScanBookOrder.of(p[5]) ?: return null
            return ScanBookHalf(left, p[1] == "1", numbers[0], numbers[1], numbers[2], order)
        }
    }
}

/** What the native split of a spread came back with, as plain values (the binding's record is not used in tests). */
data class ScanBookCut(
    val split: Boolean,
    val left: FloatArray,
    val right: FloatArray,
    val gutterFound: Boolean,
    val gutterTop: Float,
    val gutterBottom: Float,
    val confidence: Float,
) {
    /** Usable as two pages: split, with eight finite shares for each half. */
    val usable: Boolean get() = split && left.size == 8 && right.size == 8 && (left + right).all { it.isFinite() }
}

/**
 * The pages one photo becomes in book mode, as (corners, half) pairs in the order they go into
 * the scan: the left and right halves in [order] when the spread was cut, else the photo's one
 * page as it was ([corners], no half).
 */
fun scanBookPages(corners: FloatArray, cut: ScanBookCut?, order: ScanBookOrder): List<Pair<FloatArray, ScanBookHalf?>> {
    if (cut == null || !cut.usable) return listOf(corners to null)
    fun half(left: Boolean) = ScanBookHalf(left, cut.gutterFound, cut.gutterTop, cut.gutterBottom, cut.confidence, order)
    val left = cut.left to half(true)
    val right = cut.right to half(false)
    return if (order == ScanBookOrder.RightToLeft) listOf(right, left) else listOf(left, right)
}

/** The diagnostics fields for one photo in book mode: short words, booleans and numbers only. */
fun scanBookDiag(on: Boolean, lookForGutter: Boolean, order: ScanBookOrder, cut: ScanBookCut?, ms: Long): Array<Pair<String, Any?>> =
    if (!on) {
        arrayOf("book" to false)
    } else {
        arrayOf(
            "book" to true, "book_order" to order.id, "book_gutter_search" to lookForGutter,
            "book_split" to (cut?.usable == true), "gutter_found" to (cut?.gutterFound == true),
            "gutter_top" to (cut?.gutterTop?.toDouble() ?: 0.0), "gutter_bottom" to (cut?.gutterBottom?.toDouble() ?: 0.0),
            "gutter_confidence" to (cut?.confidence?.toDouble() ?: 0.0), "book_ms" to ms,
        )
    }

/**
 * The pages after page [at] is replaced by [replacement] (one page, or a spread's two halves).
 * When the page at [at] is half of a spread, its other half (the page beside it with the same
 * photo, [sameFile]) goes too: retaking a spread retakes both its pages. [at] outside the list
 * appends.
 */
fun <T> scanReplacePage(pages: List<T>, at: Int?, replacement: List<T>, sameFile: (T, T) -> Boolean): List<T> {
    if (at == null || at !in pages.indices) return pages + replacement
    val target = pages[at]
    var from = at
    var to = at
    if (from > 0 && sameFile(pages[from - 1], target)) from--
    if (to < pages.lastIndex && sameFile(pages[to + 1], target)) to++
    return pages.subList(0, from) + replacement + pages.subList(to + 1, pages.size)
}

/**
 * The photo files no longer needed once the scan's pages went from [before] to [after]: a photo
 * stays while any page uses it (the two halves of a spread share one, #303).
 */
fun scanUnusedFiles(before: List<String>, after: List<String>): Set<String> = before.toSet() - after.toSet()

/**
 * Auto-detect on the crop screen for a half of a spread (#303): the spread found is cut again and
 * the same half kept. Null when the spread was not cut in two, so the corners stay as they are.
 */
fun scanBookRecut(half: ScanBookHalf, cut: ScanBookCut?): FloatArray? =
    cut?.takeIf { it.usable }?.let { if (half.left) it.left else it.right }
