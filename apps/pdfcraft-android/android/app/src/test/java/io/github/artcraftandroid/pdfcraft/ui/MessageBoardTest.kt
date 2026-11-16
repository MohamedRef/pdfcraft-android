package io.github.artcraftandroid.pdfcraft.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #618: one message at a time, no queue, errors kept readable, buttons that do what they name. */
class MessageBoardTest {
    private var clock = 0L
    private val logs = ArrayList<MessageLog>()
    private val board = MessageBoard({ clock }, { logs += it })

    /** Move the clock on, letting the message's time run out as the UI would. */
    private fun advance(ms: Long) {
        val end = clock + ms
        while (true) {
            val until = board.current?.until
            if (until == null || until > end) break
            clock = until
            board.expire()
        }
        clock = end
    }

    @Test
    fun tenUndosWithinTwoSecondsShowOneMessageGoneThreeSecondsAfterTheLast() {
        val doc = Any()
        val ids = HashSet<Long>()
        repeat(10) { i ->
            board.raise("Ink $i undone", MessageKind.Step, owner = doc)
            ids += board.current!!.id
            assertEquals("Ink $i undone", board.current!!.text)
            advance(200)
        }
        assertEquals("one message, updated in place, never a new one sliding in", 1, ids.size)
        assertEquals(10, board.current!!.count)
        advance(3_000 - 200)
        assertNull(board.current)
        val ended = logs.filterIsInstance<MessageLog.Ended>()
        assertEquals(1, ended.size)
        assertEquals(MessageEnd.Time, ended.single().how)
        assertEquals(9, ended.single().updates)
        assertEquals(1, logs.count { it is MessageLog.Started })
    }

    @Test
    fun undoStepLeavesAboutTwoSecondsAfterTheLastTap() {
        board.raise("Ink undone", MessageKind.Step)
        advance(1_900)
        assertNotNull(board.current)
        advance(200)
        assertNull(board.current)
    }

    @Test
    fun aNoticeDoesNotReplaceAFreshError() {
        board.raise("The file could not be saved", MessageKind.Error)
        advance(500)
        assertFalse(board.raise("Ink undone", MessageKind.Step))
        assertFalse(board.raise("Copied"))
        assertFalse(board.raise("3 pages deleted", actionLabel = "Undo", action = {}))
        assertEquals("The file could not be saved", board.current!!.text)
        assertTrue(logs.any { it is MessageLog.Dropped })
        // once it could be read, a new notice replaces it at once
        advance(MessageBoard.ERROR_MIN_MS)
        assertTrue(board.raise("Copied"))
        assertEquals("Copied", board.current!!.text)
    }

    @Test
    fun errorsInARowDoNotStack() {
        board.raise("First failure", MessageKind.Error)
        val id = board.current!!.id
        advance(100)
        board.raise("Second failure", MessageKind.Error)
        advance(100)
        board.raise("Third failure", MessageKind.Error)
        assertEquals("Third failure", board.current!!.text)
        assertEquals(3, board.current!!.count)
        assertEquals(id, board.current!!.id)
        advance(MessageBoard.ERROR_MS)
        assertNull(board.current)
    }

    @Test
    fun aNewNoticeReplacesTheOldOneAtOnce() {
        board.raise("Copied")
        advance(100)
        board.raise("Saved")
        assertEquals("Saved", board.current!!.text)
        assertEquals(MessageEnd.Replaced, (logs.filterIsInstance<MessageLog.Ended>().single()).how)
        // nothing left waiting behind it
        advance(MessageBoard.NOTICE_MS)
        assertNull(board.current)
        advance(60_000)
        assertNull(board.current)
    }

    @Test
    fun theSameTextTwiceIsShownOnceAndItsTimeStartsAgain() {
        board.raise("Copied")
        val id = board.current!!.id
        advance(3_000)
        board.raise("Copied")
        assertEquals(id, board.current!!.id)
        assertEquals(1, logs.count { it is MessageLog.Started })
        advance(3_000)
        assertNotNull("its time started again", board.current)
        advance(1_100)
        assertNull(board.current)
    }

    @Test
    fun swipingItAwayBringsNothingUpBehindIt() {
        board.raise("Copied")
        board.raise("Saved")
        board.dismiss()
        assertNull(board.current)
        assertEquals(MessageEnd.Swipe, logs.filterIsInstance<MessageLog.Ended>().last().how)
        advance(60_000)
        assertNull(board.current)
    }

    @Test
    fun closingTheDocumentClearsItsMessage() {
        val doc = Any()
        board.raise("Ink undone", MessageKind.Step, owner = doc)
        board.frontChanged(doc)
        assertNotNull("still in front", board.current)
        board.frontChanged(null)
        assertNull(board.current)
        assertEquals(MessageEnd.Closed, logs.filterIsInstance<MessageLog.Ended>().last().how)
        // a message about the app as a whole stays
        board.raise("Copied")
        board.frontChanged(Any())
        assertNotNull(board.current)
    }

    @Test
    fun anUndoButtonTappedAfterALaterEditDoesNotUndoIt() {
        val doc = Any()
        var generation = 1
        var undone = 0
        val at = generation
        board.raise("3 pages deleted", actionLabel = "Undo", action = { undone++ }, owner = doc, valid = { generation == at })
        // another edit
        generation++
        assertFalse(board.press())
        assertEquals(0, undone)
        assertNull(board.current)
        assertEquals(MessageEnd.Stale, logs.filterIsInstance<MessageLog.Ended>().last().how)
    }

    @Test
    fun anUndoButtonGoesAsSoonAsTheDocumentChangesAgain() {
        val doc = Any()
        var generation = 1
        val at = generation
        board.raise("3 pages deleted", actionLabel = "Undo", action = {}, owner = doc, valid = { generation == at })
        board.ownerChanged(doc)
        assertNotNull("nothing changed yet", board.current)
        generation++
        board.ownerChanged(doc)
        assertNull(board.current)
    }

    @Test
    fun anUndoButtonTappedInTimeUndoes() {
        var undone = 0
        board.raise("3 pages deleted", actionLabel = "Undo", action = { undone++ }, valid = { true })
        assertEquals(MessageKind.WithButton, board.current!!.kind)
        assertTrue(board.press())
        assertEquals(1, undone)
        assertNull(board.current)
        assertEquals(MessageEnd.Button, logs.filterIsInstance<MessageLog.Ended>().last().how)
    }

    @Test
    fun theAccessibilityTimeoutLengthensTheTime() {
        board.timeout = { ms, _ -> ms * 2 }
        board.raise("Copied")
        advance(MessageBoard.NOTICE_MS + 1)
        assertNotNull(board.current)
        advance(MessageBoard.NOTICE_MS)
        assertNull(board.current)
    }

    @Test
    fun anErrorAboutAClosedDocumentStays() {
        val doc = Any()
        board.raise("The file could not be saved", MessageKind.Error, owner = doc)
        board.frontChanged(null)
        assertNotNull(board.current)
    }
}
