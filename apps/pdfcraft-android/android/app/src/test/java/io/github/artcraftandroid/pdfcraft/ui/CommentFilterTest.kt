package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.CommentKind
import io.github.artcraftandroid.pdfcraft.ui.viewer.commentKind
import io.github.artcraftandroid.pdfcraft.ui.viewer.filterThreadsBy
import io.github.artcraftandroid.pdfcraft.ui.viewer.threadAuthorsBy
import org.junit.Assert.assertEquals
import org.junit.Test

class CommentFilterTest {
    private data class C(val subtype: String, val author: String?)

    private val threads = listOf(
        listOf(C("Ink", "Mo"), C("Text", "Sara")),
        listOf(C("Highlight", "Sara")),
        listOf(C("Ink", " Sara ")),
        listOf(C("Square", null)),
    )

    private fun filter(kind: CommentKind?, author: String?) = filterThreadsBy(threads, kind, author, { it.subtype }, { it.author })

    @Test
    fun kindsGroupSubtypes() {
        assertEquals(CommentKind.Highlights, commentKind("StrikeOut"))
        assertEquals(CommentKind.Ink, commentKind("Ink"))
        assertEquals(CommentKind.Shapes, commentKind("Circle"))
        assertEquals(CommentKind.Notes, commentKind("FreeText"))
        assertEquals(null, commentKind("Link"))
    }

    @Test
    fun inkByOneAuthorKeepsOnlyThoseThreadsWithTheirReplies() {
        assertEquals(listOf(threads[0]), filter(CommentKind.Ink, "Mo"))
        assertEquals(listOf(threads[2]), filter(CommentKind.Ink, "Sara"))
        assertEquals(listOf(threads[1], threads[2]), filter(null, "Sara"))
        assertEquals(threads, filter(null, null))
        assertEquals(listOf(threads[3]), filter(CommentKind.Shapes, null))
    }

    @Test
    fun authorsAreListedOnceAndSorted() {
        assertEquals(listOf("Mo", "Sara"), threadAuthorsBy(threads) { it.author })
    }
}
