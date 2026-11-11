package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.DimmedComment
import io.github.artcraftandroid.pdfcraft.ui.viewer.dimmedComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentDimmingTest {
    private data class C(val page: Int, val rect: List<Float>)

    @Test
    fun whatTheFiltersLeaveOutIsDimmed() {
        val ink = C(2, listOf(10f, 10f, 50f, 50f))
        val note = C(0, listOf(1f, 2f, 3f, 4f))
        val reply = C(0, listOf(0f, 0f, 0f, 0f))
        val highlight = C(1, listOf(5f, 5f, 9f, 9f))
        val threads = listOf(listOf(ink), listOf(note, reply), listOf(highlight))
        // "ink by me" shows the drawing: the note and the highlight are dimmed, in page order
        assertEquals(
            listOf(DimmedComment(0, note.rect), DimmedComment(1, highlight.rect)),
            dimmedComments(threads, listOf(listOf(ink)), { it.page }, { it.rect }),
        )
        // nothing filtered out: nothing dimmed; a comment without a rectangle is left alone
        assertTrue(dimmedComments(threads, threads, { it.page }, { it.rect }).isEmpty())
        assertTrue(dimmedComments(listOf(listOf(C(0, emptyList()))), emptyList(), { it.page }, { it.rect }).isEmpty())
    }
}
