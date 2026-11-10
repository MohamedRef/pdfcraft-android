package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.PageGenerations
import io.github.artcraftandroid.pdfcraft.ui.viewer.pageTouchedBy
import io.github.artcraftandroid.pdfcraft.ui.viewer.pageTouchedByBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PageGenerationsTest {
    @Test fun aCommentOnOnePageRenewsOnlyThatPage() {
        val g = PageGenerations(5).next(6, touchedPage = 2, pageCount = 10, pagesChanged = false)
        assertEquals(6L, g.of(2))
        assertEquals(5L, g.of(0))
        assertEquals(5L, g.of(9))
    }

    @Test fun anythingElseRenewsEveryPage() {
        val local = PageGenerations(5).next(6, 2, 10, false)
        assertEquals(PageGenerations(7), local.next(7, touchedPage = null, pageCount = 10, pagesChanged = false))
        assertEquals(PageGenerations(7), local.next(7, touchedPage = 2, pageCount = 10, pagesChanged = true))
        assertEquals(PageGenerations(7), local.next(7, touchedPage = 12, pageCount = 10, pagesChanged = false))
        assertEquals(PageGenerations(7), local.next(7, touchedPage = -1, pageCount = 10, pagesChanged = false))
    }

    @Test fun anUnchangedDocumentKeepsItsGenerations() {
        val g = PageGenerations(5)
        assertSame(g, g.next(5, null, 3, false))
    }

    @Test fun anOlderOrEqualGenerationOnAPageRenewsAll() {
        val g = PageGenerations(5).next(8, 1, 3, false)
        assertEquals(PageGenerations(8), g.next(8, 1, 3, false))
    }

    @Test fun onlyCommentToolsWithAPageArePageLocal() {
        assertEquals(0, pageTouchedBy("comment_add", mapOf("page" to 1)))
        assertEquals(4, pageTouchedBy("comment_erase", mapOf("page" to 5L)))
        assertEquals(2, pageTouchedBy("comment_edit", mapOf("page" to 3.0)))
        assertNull(pageTouchedBy("comment_edit", mapOf("id" to "x")))
        assertNull(pageTouchedBy("comment_add", mapOf("page" to 0)))
        assertNull(pageTouchedBy("comment_add", mapOf("page" to 2.5)))
        assertNull(pageTouchedBy("page_delete", mapOf("page" to 3)))
        assertNull(pageTouchedBy("edit_undo", emptyMap()))
    }

    @Test fun aBatchIsPageLocalOnlyOnOnePage() {
        assertEquals(1, pageTouchedByBatch("comment_add", listOf(mapOf("page" to 2), mapOf("page" to 2))))
        assertNull(pageTouchedByBatch("comment_add", listOf(mapOf("page" to 2), mapOf("page" to 3))))
        assertNull(pageTouchedByBatch("comment_add", listOf(mapOf("page" to 2), emptyMap())))
        assertNull(pageTouchedByBatch("comment_add", emptyList()))
    }
}
