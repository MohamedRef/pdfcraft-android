package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.core.CommentInfo
import io.github.artcraftandroid.pdfcraft.ui.viewer.QuizSession
import io.github.artcraftandroid.pdfcraft.ui.viewer.StudyState
import io.github.artcraftandroid.pdfcraft.ui.viewer.TAPE_MARK
import io.github.artcraftandroid.pdfcraft.ui.viewer.isTape
import io.github.artcraftandroid.pdfcraft.ui.viewer.maskAt
import io.github.artcraftandroid.pdfcraft.ui.viewer.quizItems
import io.github.artcraftandroid.pdfcraft.ui.viewer.tapeArgs
import io.github.artcraftandroid.pdfcraft.ui.viewer.tapeRectOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Masking tape and quiz mode (#143). */
class StudyTest {
    private var next = 0

    private fun comment(subtype: String, page: Int = 0, y: Float = 0f, contents: String? = null, opacity: Float = 1f, replyTo: String? = null) = CommentInfo(
        page = page.toUInt(), index = (next++).toUInt(), subtype = subtype, kind = subtype, author = "Ann", contents = contents,
        modified = null, rect = listOf(10f, y, 100f, y + 12f), color = 0xE0A526u, name = "c$next", replyTo = replyTo, state = null,
        locked = false, fillSign = false, opacity = opacity, width = null, hidden = false,
    )

    @Test
    fun tapeIsAnInvisibleMarkedRectangle() {
        assertTrue(comment("Square", contents = TAPE_MARK, opacity = 0f).isTape())
        // a real rectangle someone drew, or one that says so in its text, is not tape
        assertFalse(comment("Square", contents = TAPE_MARK, opacity = 1f).isTape())
        assertFalse(comment("Square", contents = "note", opacity = 0f).isTape())
        assertFalse(comment("Highlight", contents = TAPE_MARK, opacity = 0f).isTape())
        val args = tapeArgs(2, floatArrayOf(1f, 2f, 30f, 40f), 0xE0A526, "Ann")
        assertEquals(3, args["page"])
        assertEquals("rectangle", args["type"])
        assertEquals(0.0, args["opacity"])
        assertEquals(TAPE_MARK, args["contents"])
        assertEquals("#E0A526", args["fill"])
        assertEquals(listOf(1.0, 2.0, 30.0, 40.0), args["rect"])
    }

    @Test
    fun tapeNeedsARealDrag() {
        assertNull(tapeRectOf(10f, 10f, 12f, 40f))
        assertEquals(listOf(5f, 10f, 50f, 40f), tapeRectOf(50f, 40f, 5f, 10f)!!.toList())
        assertNull(tapeRectOf(Float.NaN, 0f, 10f, 10f))
    }

    @Test
    fun quizCoversTenHighlightsInReadingOrder() {
        val comments = (9 downTo 0).map { comment("Highlight", y = it * 20f, contents = if (it == 3) " Mitochondria " else null) } +
            comment("Ink") + comment("Highlight", page = 1) + comment("Highlight", replyTo = "c1") +
            comment("Square", contents = TAPE_MARK, opacity = 0f)
        val page = quizItems(comments, 0)
        assertEquals(10, page.size)
        assertEquals((0..9).map { it * 20f }, page.map { it.rect[1] })
        assertEquals("Mitochondria", page[3].label)
        assertEquals(11, quizItems(comments, null).size)
        assertEquals(0, quizItems(comments, 5).size)
    }

    @Test
    fun quizRevealAnswerAndResults() {
        val items = quizItems((0 until 4).map { comment("Highlight", y = it * 20f) }, 0)
        var q = QuizSession(items)
        assertEquals(4, q.masks.size)
        // a tap on the second: it uncovers and waits for an answer
        val hit = maskAt(q.masks, 0, 50f, 25f)!!
        assertEquals(items[1].key, hit.key)
        q = q.reveal(hit.key)
        assertEquals(3, q.masks.size)
        assertEquals(hit.key, q.awaiting?.key)
        q = q.answer(knew = false)
        assertNull(q.awaiting)
        assertEquals(items[0].key, q.next?.key)
        for (m in listOf(items[0], items[2], items[3])) q = q.reveal(m.key).answer(knew = true)
        assertTrue(q.done)
        assertEquals(3, q.known)
        assertEquals(listOf(items[1].key), q.unknown.map { it.key })
        // answering twice changes nothing; a retry covers only what was missed
        assertEquals(q, q.answer(knew = true))
        val retry = q.retryUnknown()
        assertEquals(1, retry.masks.size)
        assertFalse(retry.done)
        // covering again keeps the answer
        assertTrue(q.cover(items[0].key).answers.containsKey(items[0].key))
        assertNull(maskAt(q.masks, 1, 50f, 25f))
    }

    @Test
    fun liftedTapeStaysWhileOthersAreCovered() {
        val s = StudyState()
        assertFalse(s.isLifted("a"))
        s.lifted["a"] = true
        assertTrue(s.isLifted("a"))
        s.allLifted = true
        assertTrue(s.isLifted("b"))
    }
}
