package io.github.artcraftandroid.pdfcraft.ui

import java.io.File

/** Replace with…: [pages] (0-based) of [doc] are to take the content of the picked [file]. */
class ReplacePrompt(val doc: OpenDocument, val pages: List<Int>, val file: File)

/** How Split cuts a document, as `doc_split` takes it. */
sealed class SplitMode {
    /** A new part every [pages] pages. */
    data class Every(val pages: Int) : SplitMode()
    /** A new part starts at each of [pages] (0-based). */
    data class Before(val pages: List<Int>) : SplitMode()
    /** One part per top-level bookmark, named after it. */
    data object AtBookmarks : SplitMode()
    /** Parts of at most [mb] megabytes. */
    data class MaxSize(val mb: Double) : SplitMode()

    fun args(): Map<String, Any?> = when (this) {
        is Every -> mapOf("every" to pages.coerceAtLeast(1))
        // the first page always starts a part: the engine needs only the later cuts
        is Before -> mapOf("before" to pages.filter { it > 0 }.distinct().sorted().map { it + 1 })
        AtBookmarks -> mapOf("bookmarks" to true)
        is MaxSize -> mapOf("max_mb" to mb)
    }

    /** Whether this can produce more than one part for [pageCount] pages. */
    fun usable(pageCount: Int): Boolean = when (this) {
        is Every -> pages in 1 until pageCount
        is Before -> pages.any { it in 1 until pageCount }
        AtBookmarks -> true
        is MaxSize -> mb > 0.0
    }
}

/** Files made for the person, waiting for the folder they are saved into. */
internal class FolderOutput(val files: List<Pair<File, String>>, val mime: String, private val cleanup: File?) {
    fun discard() {
        files.forEach { it.first.delete() }
        cleanup?.deleteRecursively()
    }
}

/** Page-label numbering styles, as `page_number` names them, with what each looks like. */
enum class LabelStyle(val engine: String, val sample: String) {
    Decimal("decimal", "1, 2, 3"),
    LowerRoman("lower-roman", "i, ii, iii"),
    UpperRoman("upper-roman", "I, II, III"),
    LowerAlpha("lower-alpha", "a, b, c"),
    UpperAlpha("upper-alpha", "A, B, C"),
    None("none", ""),
}

/** Whether any page has a label other than its own 1-based number. */
fun hasCustomLabels(labels: List<String>): Boolean = labels.withIndex().any { (i, l) -> l != (i + 1).toString() }

/**
 * The page (0-based) a Go to page entry means. As in Acrobat, a page label is matched first (exactly,
 * then ignoring case), so "iv" or "A-2" reach their page and "1" reaches the page labelled 1; an entry
 * that is no label is read as a 1-based page number.
 */
fun pageForEntry(labels: List<String>, entry: String): Int? {
    val e = entry.trim()
    if (e.isEmpty()) return null
    labels.indexOf(e).takeIf { it >= 0 }?.let { return it }
    labels.indexOfFirst { it.equals(e, ignoreCase = true) }.takeIf { it >= 0 }?.let { return it }
    val n = e.toIntOrNull() ?: return null
    return (n - 1).takeIf { n >= 1 && n <= labels.size }
}
