package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.core.CommentInfo
import io.github.artcraftandroid.pdfcraft.ui.viewer.CommentFilters
import io.github.artcraftandroid.pdfcraft.ui.viewer.CommentKindFilter
import io.github.artcraftandroid.pdfcraft.ui.viewer.CommentListRow
import io.github.artcraftandroid.pdfcraft.ui.viewer.CommentStatusFilter
import io.github.artcraftandroid.pdfcraft.ui.viewer.filterThreads
import io.github.artcraftandroid.pdfcraft.ui.viewer.pageRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentFilterTest {
    private var next = 0

    private fun comment(subtype: String, contents: String? = null, author: String? = "Ann", page: Int = 3, replyTo: String? = null) = CommentInfo(
        page = page.toUInt(), index = (next++).toUInt(), subtype = subtype, kind = subtype, author = author, contents = contents,
        modified = null, rect = listOf(0f, 0f, 10f, 10f), color = null, name = "c$next", replyTo = replyTo, state = null,
        locked = false, fillSign = false, opacity = 1f, width = null, hidden = false, points = emptyList(),
    )

    private fun ink() = listOf(comment("Ink"))

    @Test
    fun aPageOfHandwritingFoldsAfterItsNotes() {
        // the acceptance case of #196: 50 strokes and 2 notes on one page
        val notes = listOf(listOf(comment("Text", "Check this")), listOf(comment("FreeText", "Typo")))
        val threads = List(25) { ink() } + notes + List(25) { ink() }
        val rows = pageRows(3, threads, expanded = false)
        assertEquals(3, rows.size)
        assertEquals(CommentListRow.Thread(notes[0]), rows[0])
        assertEquals(CommentListRow.Thread(notes[1]), rows[1])
        assertEquals(50, (rows[2] as CommentListRow.Drawings).threads.size)
        // opened, every drawing has its own row again, in page order
        assertEquals(52, pageRows(3, threads, expanded = true).size)
    }

    @Test
    fun aSingleDrawingOrOneWithTextOrRepliesKeepsItsRow() {
        val lone = ink()
        assertEquals(listOf(CommentListRow.Thread(lone)), pageRows(0, listOf(lone), expanded = false))
        val written = listOf(comment("Square", "Look"))
        val answered = listOf(comment("Ink"), comment("Text", "Agreed", replyTo = "x"))
        val rows = pageRows(0, listOf(written, answered, ink(), ink()), expanded = false)
        assertEquals(listOf(CommentListRow.Thread(written), CommentListRow.Thread(answered)), rows.take(2))
        assertEquals(2, (rows[2] as CommentListRow.Drawings).threads.size)
    }

    @Test
    fun kindChipsAndMine() {
        val note = listOf(comment("Text", "Note", author = "Mona"))
        val highlight = listOf(comment("Highlight", author = "Ali"))
        val squiggly = listOf(comment("Squiggly", author = "Ali"))
        val drawing = ink()
        val stamp = listOf(comment("Stamp"))
        val all = listOf(note, highlight, squiggly, drawing, stamp)
        fun kind(k: CommentKindFilter, me: String? = null) = filterThreads(all, CommentFilters(kind = k), me, "Guest") { null }
        assertEquals(all, kind(CommentKindFilter.All))
        assertEquals(listOf(note), kind(CommentKindFilter.Notes))
        assertEquals(listOf(highlight, squiggly), kind(CommentKindFilter.Highlights))
        assertEquals(listOf(drawing, stamp), kind(CommentKindFilter.Drawings))
        // Mine uses the saved author name, whatever its case or spaces
        assertEquals(listOf(note), kind(CommentKindFilter.Mine, " mona "))
        assertTrue(kind(CommentKindFilter.Mine, null).isEmpty())
    }

    @Test
    fun searchFindsTextRepliesAndAuthorsIgnoringCaseAndAccents() {
        val a = listOf(comment("Text", "See the Résumé"))
        val b = listOf(comment("Highlight"), comment("Text", "Why here?", author = "Bo", replyTo = "x"))
        val c = listOf(comment("Text", "Other", author = "Zed"))
        val all = listOf(a, b, c)
        fun find(q: String) = filterThreads(all, CommentFilters(query = q), null, "Guest") { null }
        assertEquals(listOf(a), find("resume"))
        assertEquals(listOf(b), find("WHY"))
        assertEquals(listOf(c), find("zed"))
        assertEquals(all, find("  "))
    }

    @Test
    fun searchStaysFastOnFiveHundredComments() {
        val all = List(500) { i -> listOf(comment("Text", "Comment number $i about chapter ${i % 40}"), comment("Text", "Reply $i", replyTo = "x")) }
        val start = System.nanoTime()
        val found = filterThreads(all, CommentFilters(query = "chapter 7"), null, "Guest") { null }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(13, found.size) // chapter 7, plus none of 70..79 since % 40 < 40
        assertTrue("took $ms ms", ms < 300)
    }

    @Test
    fun statusAndAuthorFilters() {
        val open = listOf(comment("Text", "a", author = "Ann"))
        val accepted = listOf(comment("Text", "b", author = "Bo"))
        val guest = listOf(comment("Text", "c", author = null))
        val all = listOf(open, accepted, guest)
        val status = mapOf(accepted.first() to "Accepted")
        fun f(filters: CommentFilters) = filterThreads(all, filters, null, "Guest") { status[it] }
        assertEquals(listOf(accepted), f(CommentFilters(status = CommentStatusFilter.Accepted)))
        assertEquals(listOf(open, guest), f(CommentFilters(status = CommentStatusFilter.Open)))
        assertTrue(f(CommentFilters(status = CommentStatusFilter.Rejected)).isEmpty())
        // hiding an author's chip hides their comments; a comment without one is the guest's
        assertEquals(listOf(open), f(CommentFilters(hiddenAuthors = setOf("Bo", "Guest"))))
    }
}
