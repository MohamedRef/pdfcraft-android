package io.github.artcraftandroid.pdfcraft.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #618, #635: one message at a time, no queue, errors kept readable, buttons that do what they name. */
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
        assertEquals("The file could not be saved", board.current!!.text)
        // an Undo/Redo step is not kept for later
        assertTrue(logs.any { it is MessageLog.Dropped && it.kind == MessageKind.Step })
        // once the error could be read, a new notice replaces it at once
        advance(MessageBoard.ERROR_MIN_MS)
        assertTrue(board.raise("Saved"))
        assertEquals("Saved", board.current!!.text)
    }

    // ---- #635: what is raised behind a fresh error ------------------------------------------------

    @Test
    fun aSecondTryThatWorkedIsShownWhenTheErrorCouldBeRead() {
        board.raise("Could not save to Downloads", MessageKind.Error)
        advance(2_000)
        assertFalse("not on screen yet", board.raise("Saved to Downloads"))
        assertEquals("Could not save to Downloads", board.current!!.text)
        assertEquals(1, logs.count { it is MessageLog.Held })
        advance(999)
        assertEquals("the error has its three seconds", "Could not save to Downloads", board.current!!.text)
        advance(1)
        assertEquals("Saved to Downloads", board.current!!.text)
        assertEquals(MessageKind.Notice, board.current!!.kind)
        // the message that waited has its own full time from now, and nothing follows it
        advance(MessageBoard.NOTICE_MS - 1)
        assertNotNull(board.current)
        advance(1)
        assertNull(board.current)
        advance(60_000)
        assertNull(board.current)
        assertEquals(2, logs.count { it is MessageLog.Started })
    }

    @Test
    fun aMessageWithAButtonWaitsBehindAnErrorAndKeepsItsButton() {
        var undone = 0
        board.raise("The file could not be saved", MessageKind.Error)
        advance(1_000)
        assertFalse(board.raise("3 pages deleted", actionLabel = "Undo", action = { undone++ }, valid = { true }))
        // a plain notice does not push the button out of the one place
        assertFalse(board.raise("Copied"))
        advance(2_000)
        assertEquals("3 pages deleted", board.current!!.text)
        assertEquals(MessageKind.WithButton, board.current!!.kind)
        assertEquals("Undo", board.current!!.actionLabel)
        assertTrue(board.press())
        assertEquals(1, undone)
    }

    @Test
    fun onlyTheNewestMessageWaitsItIsNotAQueue() {
        board.raise("The file could not be saved", MessageKind.Error)
        advance(100)
        board.raise("Copied")
        board.raise("Saved")
        board.raise("Exported")
        advance(MessageBoard.ERROR_MIN_MS)
        assertEquals("Exported", board.current!!.text)
        advance(60_000)
        assertNull("the others were not kept", board.current)
        assertEquals(2, logs.count { it is MessageLog.Started })
    }

    @Test
    fun anUndoStepNeverWaitsBehindAnError() {
        board.raise("The file could not be saved", MessageKind.Error)
        advance(100)
        assertFalse(board.raise("Ink undone", MessageKind.Step))
        assertEquals(0, logs.count { it is MessageLog.Held })
        // nothing waits, so the error keeps its full time
        advance(MessageBoard.ERROR_MS - 101)
        assertEquals("The file could not be saved", board.current!!.text)
        advance(1)
        assertNull(board.current)
    }

    @Test
    fun aWaitingMessageWhoseDocumentHasClosedIsNotShown() {
        val doc = Any()
        board.frontChanged(doc)
        board.raise("The file could not be saved", MessageKind.Error, owner = doc)
        advance(500)
        board.raise("Saved to Downloads", owner = doc)
        board.frontChanged(null)
        advance(60_000)
        assertNull(board.current)
        assertEquals("only the error was ever shown", 1, logs.count { it is MessageLog.Started })
    }

    @Test
    fun aWaitingUndoButtonThatWouldUndoALaterEditIsNotShown() {
        val doc = Any()
        var generation = 1
        val at = generation
        board.raise("The file could not be saved", MessageKind.Error, owner = doc)
        advance(500)
        board.raise("3 pages deleted", actionLabel = "Undo", action = {}, owner = doc, valid = { generation == at })
        generation++
        advance(60_000)
        assertNull(board.current)
        assertEquals(1, logs.count { it is MessageLog.Started })
    }

    @Test
    fun swipingTheErrorAwayBringsNoWaitingMessageUp() {
        board.raise("The file could not be saved", MessageKind.Error)
        advance(500)
        board.raise("Saved to Downloads")
        board.dismiss()
        assertNull(board.current)
        advance(60_000)
        assertNull(board.current)
        assertEquals(1, logs.count { it is MessageLog.Started })
    }

    @Test
    fun aSecondErrorHasItsOwnSecondsToBeRead() {
        board.raise("The file could not be saved", MessageKind.Error)
        advance(2_900)
        board.raise("The page could not be deleted", MessageKind.Error)
        advance(200)
        // 3.1 s after the first error, 0.2 s after the second: the second is still being read
        assertFalse(board.raise("Copied"))
        assertEquals("The page could not be deleted", board.current!!.text)
        advance(2_799)
        assertEquals("The page could not be deleted", board.current!!.text)
        advance(1)
        assertEquals("Copied", board.current!!.text)
    }

    @Test
    fun theSameErrorAgainHasHadItsSeconds() {
        board.raise("The file could not be saved", MessageKind.Error)
        advance(2_900)
        board.raise("The file could not be saved", MessageKind.Error)
        assertEquals(2, board.current!!.count)
        advance(200)
        assertTrue("its text has been on screen for 3.1 s", board.raise("Copied"))
        assertEquals("Copied", board.current!!.text)
    }

    @Test
    fun anUndoButtonIsNotSpentOnAStrokeDrawnSince() {
        val doc = Any()
        var drafts = 0
        var undone = 0
        // as AppViewModel.tellUndoable makes it
        fun raise() = board.raise("3 pages deleted", actionLabel = "Undo", action = { undone++ }, owner = doc, valid = { undoButtonValid(true, true, drafts > 0) })
        raise()
        drafts = 1
        assertFalse("tapped after a stroke was drawn: Undo would take the stroke back", board.press())
        assertEquals(0, undone)
        assertEquals(MessageEnd.Stale, logs.filterIsInstance<MessageLog.Ended>().last().how)
        // and it goes by itself when the tool takes that step (AppRoot tells the board)
        drafts = 0
        raise()
        drafts = 1
        board.ownerChanged(doc)
        assertNull(board.current)
    }

    @Test
    fun anUndoButtonIsValidOnlyForItsOwnEditInFront() {
        assertTrue(undoButtonValid(inFront = true, unchanged = true, toolHasOwnStep = false))
        assertFalse(undoButtonValid(inFront = false, unchanged = true, toolHasOwnStep = false))
        assertFalse(undoButtonValid(inFront = true, unchanged = false, toolHasOwnStep = false))
        assertFalse(undoButtonValid(inFront = true, unchanged = true, toolHasOwnStep = true))
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
