package io.github.artcraftandroid.pdfcraft.ui.viewer

import io.github.artcraftandroid.pdfcraft.core.CommentInfo

/** The kinds the comment list filters by (#155). */
enum class CommentKind { Notes, Highlights, Ink, Shapes, Stamps }

/** The kind of a comment of PDF [subtype], or null for one the list doesn't filter. */
fun commentKind(subtype: String): CommentKind? = when (subtype) {
    "Text", "FreeText", "Sound" -> CommentKind.Notes
    "Highlight", "Underline", "StrikeOut", "Squiggly", "Caret" -> CommentKind.Highlights
    "Ink" -> CommentKind.Ink
    "Line", "Square", "Circle", "Polygon", "PolyLine" -> CommentKind.Shapes
    "Stamp" -> CommentKind.Stamps
    else -> null
}

/**
 * The threads whose first comment is of [kind] (any when null) and by [author] (anyone when
 * null; a blank author counts as no author). Replies stay with their thread.
 */
fun filterThreads(threads: List<List<CommentInfo>>, kind: CommentKind?, author: String?): List<List<CommentInfo>> =
    filterThreadsBy(threads, kind, author, { it.subtype }, { it.author })

fun <T> filterThreadsBy(threads: List<List<T>>, kind: CommentKind?, author: String?, subtype: (T) -> String, authorOf: (T) -> String?): List<List<T>> =
    threads.filter { t ->
        val first = t.first()
        (kind == null || commentKind(subtype(first)) == kind) && (author == null || authorOf(first).orEmpty().trim() == author)
    }

/** The authors of [threads]' first comments, for the author filter. */
fun threadAuthors(threads: List<List<CommentInfo>>): List<String> = threadAuthorsBy(threads) { it.author }

fun <T> threadAuthorsBy(threads: List<List<T>>, authorOf: (T) -> String?): List<String> =
    threads.mapNotNull { authorOf(it.first())?.trim()?.takeIf(String::isNotEmpty) }.distinct().sorted()
