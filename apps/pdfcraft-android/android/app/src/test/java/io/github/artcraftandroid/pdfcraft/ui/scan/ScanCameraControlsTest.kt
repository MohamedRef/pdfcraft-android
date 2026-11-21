package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review pages, Undo and Done on the camera screen (#780): what the bar shows, when each can act, what Undo takes back and what Done waits for. */
class ScanCameraControlsTest {
    private val all = ScanCameraControl.entries.toSet()

    private fun reduce(start: ScanCameraState, vararg events: ScanCameraEvent): Pair<ScanCameraState, List<ScanCameraEffect>> {
        var state = start
        val effects = mutableListOf<ScanCameraEffect>()
        for (e in events) {
            val (next, fx) = state.reduce(e)
            state = next
            effects += fx
        }
        return state to effects
    }

    // ---- the testing option ----

    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        val lab = ScanLab()
        assertTrue(lab.cameraButtons)
        assertFalse(lab.copy(cameraButtons = false).asDesigned)
        assertEquals(ScanLab(cameraButtons = false), ScanLab.decode(ScanLab(cameraButtons = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto").cameraButtons)
        // the log's options line and a kept scan's page file say which bar the photo was taken with
        assertEquals(false, ScanLab(cameraButtons = false).diag().toMap()["camera_buttons"])
        assertEquals(true, ScanLab().diag().toMap()["camera_buttons"])
        assertEquals(false, ScanLab(cameraButtons = false).json().getBoolean("camera_buttons"))
    }

    // ---- what the bar shows ----

    @Test
    fun manyPagesAndBookAlwaysShowAllThreeSoNothingMovesAfterAPhoto() {
        for (mode in listOf(ScanCameraMode.Batch, ScanCameraMode.Book)) for (pages in listOf(0, 1, 7)) {
            assertEquals(all, scanCameraControlsShown(on = true, mode = mode, retaking = false, pages = pages))
        }
    }

    @Test
    fun onePageHasOnlyReviewAndOnlyOnceThereArePages() {
        assertEquals(emptySet<ScanCameraControl>(), scanCameraControlsShown(on = true, mode = ScanCameraMode.Single, retaking = false, pages = 0))
        assertEquals(setOf(ScanCameraControl.Review), scanCameraControlsShown(on = true, mode = ScanCameraMode.Single, retaking = false, pages = 2))
    }

    @Test
    fun aRetakeAndTheOptionOffShowNone() {
        for (mode in ScanCameraMode.entries) {
            assertEquals(emptySet<ScanCameraControl>(), scanCameraControlsShown(on = true, mode = mode, retaking = true, pages = 3))
            assertEquals(emptySet<ScanCameraControl>(), scanCameraControlsShown(on = false, mode = mode, retaking = false, pages = 3))
        }
    }

    // ---- when each can act ----

    @Test
    fun beforeTheFirstPhotoNothingCanActAndEachSaysThereAreNoPages() {
        val c = scanCameraControls(all, pages = 0, pending = 0, undoable = false, busy = false)
        assertEquals(ScanCameraBlock.NoPages, c.review)
        assertEquals(ScanCameraBlock.NoPages, c.undo)
        assertEquals(ScanCameraBlock.NoPages, c.done)
        assertEquals(0, c.pages)
        for (control in ScanCameraControl.entries) assertEquals(ScanCameraBlock.NoPages, c.block(control))
    }

    @Test
    fun withAPageOfThisCameraAllThreeCanAct() {
        val c = scanCameraControls(all, pages = 3, pending = 0, undoable = true, busy = false)
        for (control in ScanCameraControl.entries) assertEquals(ScanCameraBlock.None, c.block(control))
        assertEquals(3, c.pages)
    }

    @Test
    fun pagesThisCameraDidNotTakeAreNotUndone() {
        // imported pages, or a scan's pages from before the camera opened: Review and Done act, Undo says why not
        val c = scanCameraControls(all, pages = 4, pending = 0, undoable = false, busy = false)
        assertEquals(ScanCameraBlock.None, c.review)
        assertEquals(ScanCameraBlock.NothingToUndo, c.undo)
        assertEquals(ScanCameraBlock.None, c.done)
    }

    @Test
    fun whileAPhotoIsTakenOrOnItsWayReviewAndUndoWaitAndDoneIsTaken() {
        for ((pending, busy) in listOf(1 to false, 0 to true, 2 to true)) for (pages in listOf(0, 2)) {
            val c = scanCameraControls(all, pages = pages, pending = pending, undoable = pages > 0, busy = busy)
            assertEquals(ScanCameraBlock.Busy, c.review)
            assertEquals(ScanCameraBlock.Busy, c.undo)
            // Done waits for the photo itself, so it is never refused while one is coming
            assertEquals(ScanCameraBlock.None, c.done)
        }
    }

    @Test
    fun theCountIsOfPagesInTheScanNeverOfPhotosOnTheirWay() {
        val c = scanCameraControls(all, pages = 2, pending = 3, undoable = true, busy = false)
        assertEquals(2, c.pages)
        assertEquals(3, c.pending)
        assertEquals(0, scanCameraControls(all, pages = -1, pending = -4, undoable = false, busy = false).pages)
    }

    // ---- the photos of one camera session ----

    @Test
    fun aPhotoIsWaitedForUntilItIsSeenAsAPage() {
        var s = ScanCameraSession().handed("a.jpg", 100L)
        assertEquals(1, s.pending)
        // not a page yet: nothing to undo, and Undo does not reach for an older page instead
        assertNull(s.undoTarget(emptyList()))
        assertEquals(listOf("a.jpg"), s.newlySeen(listOf("a.jpg")).map { it.path })
        s = s.seen(listOf("a.jpg"))
        assertEquals(0, s.pending)
        assertEquals("a.jpg", s.undoTarget(listOf("a.jpg")))
        // handed twice is one photo
        assertEquals(s, s.handed("a.jpg", 900L))
        // seen again changes nothing
        assertTrue(s === s.seen(listOf("a.jpg")))
    }

    @Test
    fun threePhotosThenUndoLeavesTwoAndUndoGoesOnBackwards() {
        var s = ScanCameraSession()
        var pages = listOf<String>()
        for ((i, p) in listOf("1.jpg", "2.jpg", "3.jpg").withIndex()) {
            s = s.handed(p, 100L * i)
            pages = pages + p
            s = s.seen(pages)
            assertEquals(i + 1, pages.size)
        }
        assertEquals("3.jpg", s.undoTarget(pages))
        assertEquals(listOf(2), scanUndoIndexes(pages, "3.jpg"))
        pages = pages - "3.jpg"
        s = s.undone("3.jpg").seen(pages)
        assertEquals(listOf("1.jpg", "2.jpg"), pages)
        assertEquals(1, s.undone)
        assertEquals("2.jpg", s.undoTarget(pages))
        // back to an empty scan: nothing left to undo, the camera is still this session
        s = s.undone("2.jpg").undone("1.jpg").seen(emptyList())
        assertNull(s.undoTarget(emptyList()))
        assertEquals(3, s.undone)
        assertEquals(0, s.pending)
    }

    @Test
    fun undoNeverTakesAnImportOrAPageFromBeforeTheCameraOpened() {
        val pages = listOf("old.jpg", "import.jpg")
        assertNull(ScanCameraSession().undoTarget(pages))
        // one photo of this camera among them: only that one
        val s = ScanCameraSession().handed("mine.jpg", 0L).seen(pages + "mine.jpg")
        assertEquals("mine.jpg", s.undoTarget(pages + "mine.jpg"))
        assertNull(s.undone("mine.jpg").undoTarget(pages))
    }

    @Test
    fun whileTheNewestPhotoIsOnItsWayUndoDoesNotTakeAnOlderOne() {
        val s = ScanCameraSession().handed("1.jpg", 0L).seen(listOf("1.jpg")).handed("2.jpg", 500L)
        assertEquals(1, s.pending)
        assertNull(s.undoTarget(listOf("1.jpg")))
        assertEquals("2.jpg", s.seen(listOf("1.jpg", "2.jpg")).undoTarget(listOf("1.jpg", "2.jpg")))
    }

    @Test
    fun aPageDeletedOrRetakenElsewhereIsForgottenSoUndoDoesNotReachPastIt() {
        var s = ScanCameraSession().handed("1.jpg", 0L).handed("2.jpg", 10L).seen(listOf("1.jpg", "2.jpg"))
        // page 2 was deleted on the Enhance screen during Review pages
        s = s.seen(listOf("1.jpg"))
        assertEquals(listOf("1.jpg"), s.handed.map { it.path })
        assertEquals("1.jpg", s.undoTarget(listOf("1.jpg")))
        // before that look at the pages, the answer is already the same
        val stale = ScanCameraSession().handed("1.jpg", 0L).handed("2.jpg", 10L).seen(listOf("1.jpg", "2.jpg"))
        assertEquals("1.jpg", stale.undoTarget(listOf("1.jpg")))
        // a session kept from a scan that has ended undoes nothing in the next
        assertNull(stale.undoTarget(listOf("other.jpg")))
        assertTrue(stale.seen(listOf("other.jpg")).handed.isEmpty())
    }

    @Test
    fun aPhotoThatNeverBecomesAPageIsGivenUpOn() {
        val s = ScanCameraSession().handed("1.jpg", 1_000L).seen(listOf("1.jpg")).handed("lost.jpg", 2_000L)
        assertEquals(2_000L + ScanCameraSession.PENDING_MS, s.nextExpiry())
        assertTrue(s === s.expired(2_000L + ScanCameraSession.PENDING_MS - 1))
        val after = s.expired(2_000L + ScanCameraSession.PENDING_MS)
        assertEquals(0, after.pending)
        assertNull(after.nextExpiry())
        // the page that did arrive is still this camera's
        assertEquals("1.jpg", after.undoTarget(listOf("1.jpg")))
        assertNull(ScanCameraSession().nextExpiry())
    }

    @Test
    fun aBookPhotoIsUndoneAsBothItsPagesWhereverTheyWereMoved() {
        val pages = listOf("spread.jpg", "other.jpg", "spread.jpg", "last.jpg")
        assertEquals(listOf(2, 0), scanUndoIndexes(pages, "spread.jpg"))
        assertEquals(emptyList<Int>(), scanUndoIndexes(pages, "none.jpg"))
        val s = ScanCameraSession().handed("spread.jpg", 0L).seen(pages)
        assertEquals("spread.jpg", s.undoTarget(pages))
    }

    @Test
    fun closingAfterEveryPhotoWasUndoneEndsTheScan() {
        assertTrue(scanCameraEndsEmpty(on = true, pages = 0, pending = 0, undone = 1))
        // a page left, a photo still coming, nothing undone, or the option off: as before
        assertFalse(scanCameraEndsEmpty(on = true, pages = 1, pending = 0, undone = 1))
        assertFalse(scanCameraEndsEmpty(on = true, pages = 0, pending = 1, undone = 1))
        assertFalse(scanCameraEndsEmpty(on = true, pages = 0, pending = 0, undone = 0))
        assertFalse(scanCameraEndsEmpty(on = false, pages = 0, pending = 0, undone = 2))
    }

    // ---- Done and a photo asked for ----

    @Test
    fun doneWaitsForAPhotoStillFocusingThenClosesOnce() {
        val (queued, fx) = reduce(ScanCameraState(), ScanCameraEvent.Shutter(100L), ScanCameraEvent.Done(150L, keepQueued = true))
        assertEquals(ScanCameraPhase.Focusing, queued.phase)
        assertTrue(queued.captureQueued && queued.doneQueued)
        assertEquals(listOf(ScanCameraEffect.FocusCentre), fx)
        // a second press changes nothing
        assertEquals(queued, queued.reduce(ScanCameraEvent.Done(160L, keepQueued = true)).first)
        val (taking, fx2) = reduce(queued, ScanCameraEvent.FocusEnded(true, 400L))
        assertEquals(ScanCameraPhase.Capturing, taking.phase)
        assertEquals(listOf(ScanCameraEffect.Capture), fx2)
        assertTrue(taking.doneQueued)
        val (end, fx3) = reduce(taking, ScanCameraEvent.CaptureEnded(true, 900L))
        assertEquals(ScanCameraPhase.Finished, end.phase)
        assertEquals(listOf(ScanCameraEffect.Finish), fx3)
        // nothing arriving late closes it again or takes another photo
        val late = reduce(end, ScanCameraEvent.CaptureEnded(true, 950L), ScanCameraEvent.Done(960L, keepQueued = true), ScanCameraEvent.Shutter(970L), ScanCameraEvent.FocusEnded(true, 980L))
        assertTrue(late.second.isEmpty())
    }

    @Test
    fun doneWaitsThroughTheFocusTimeOutToo() {
        val (queued, _) = reduce(ScanCameraState(), ScanCameraEvent.Shutter(0L), ScanCameraEvent.Done(10L, keepQueued = true))
        val (taking, fx) = reduce(queued, ScanCameraEvent.Tick(ScanCameraTiming.FOCUS_WAIT_MS))
        assertEquals(listOf(ScanCameraEffect.Capture), fx)
        assertTrue(taking.doneQueued)
    }

    @Test
    fun aPhotoThatFailsAfterDoneKeepsTheCameraOpenWithTheError() {
        val focused = ScanCameraState(lastFocusAt = 0L, lastFocusOk = true)
        val (s, fx) = reduce(focused, ScanCameraEvent.Shutter(100L), ScanCameraEvent.Done(200L, keepQueued = true), ScanCameraEvent.CaptureEnded(false, 700L))
        assertEquals(ScanCameraPhase.Idle, s.phase)
        assertFalse(s.doneQueued)
        assertEquals(listOf(ScanCameraEffect.Capture, ScanCameraEffect.ShowError), fx)
        assertTrue(s.shutterReady)
        // the photo taken again, Done pressed again: it closes
        val (end, fx2) = reduce(s, ScanCameraEvent.Shutter(800L), ScanCameraEvent.CaptureEnded(true, 1_200L), ScanCameraEvent.Done(1_300L, keepQueued = true))
        assertEquals(ScanCameraPhase.Finished, end.phase)
        assertEquals(listOf(ScanCameraEffect.Capture, ScanCameraEffect.Finish), fx2)
    }

    @Test
    fun withTheOptionOffDoneIsAsBefore() {
        // a photo still focusing is dropped
        val (s, fx) = reduce(ScanCameraState(), ScanCameraEvent.Shutter(100L), ScanCameraEvent.Done(150L))
        assertEquals(ScanCameraPhase.Finished, s.phase)
        assertFalse(s.captureQueued)
        assertEquals(listOf(ScanCameraEffect.FocusCentre, ScanCameraEffect.Finish), fx)
        // a photo that fails still closes the screen
        val focused = ScanCameraState(lastFocusAt = 0L, lastFocusOk = true)
        val (failed, fx2) = reduce(focused, ScanCameraEvent.Shutter(100L), ScanCameraEvent.Done(200L), ScanCameraEvent.CaptureEnded(false, 700L))
        assertEquals(ScanCameraPhase.Finished, failed.phase)
        assertEquals(listOf(ScanCameraEffect.Capture, ScanCameraEffect.ShowError, ScanCameraEffect.Finish), fx2)
    }

    @Test
    fun doneWithNothingAskedForClosesAtOnce() {
        val (s, fx) = reduce(ScanCameraState(), ScanCameraEvent.Done(0L, keepQueued = true))
        assertEquals(ScanCameraPhase.Finished, s.phase)
        assertEquals(listOf(ScanCameraEffect.Finish), fx)
        // a tap to focus that is still going is not a photo: nothing to wait for
        val (t, fx2) = reduce(ScanCameraState(), ScanCameraEvent.TapFocus(0L), ScanCameraEvent.Done(50L, keepQueued = true))
        assertEquals(ScanCameraPhase.Finished, t.phase)
        assertEquals(listOf(ScanCameraEffect.Finish), fx2)
    }

    // ---- what is said and recorded ----

    @Test
    fun theLineAfterAPhotoSaysTakenThenCountsThePages() {
        assertEquals(ScanCameraCue.Taken, ScanCameraCue.of(pages = 2, pending = 1))
        assertEquals(ScanCameraCue.Taken, ScanCameraCue.of(pages = 0, pending = 1))
        assertEquals(ScanCameraCue.Count, ScanCameraCue.of(pages = 3, pending = 0))
        assertEquals(ScanCameraCue.Empty, ScanCameraCue.of(pages = 0, pending = 0))
        // long enough to read, short enough not to hold the next page's advice back
        assertTrue(ScanCameraCue.MS in 1_000L..2_500L)
        assertTrue(ScanCameraCue.BLOCKED_MS >= ScanCameraCue.MS)
    }

    @Test
    fun everyReasonHasItsOwnSentenceAndActingHasNone() {
        assertNull(scanCameraBlockText(ScanCameraBlock.None))
        val texts = ScanCameraBlock.entries.filter { it != ScanCameraBlock.None }.map { scanCameraBlockText(it) }
        assertTrue(texts.all { it != null })
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test
    fun theLogLineHoldsOnlyWordsAndCounts() {
        val line = scanCameraActionDiag("undo", "ok", ScanCameraBlock.None, pages = 2, pending = 0, removed = 1).toMap()
        assertEquals("undo", line["action"])
        assertEquals("ok", line["result"])
        assertEquals("none", line["why"])
        assertEquals(2, line["pages"])
        assertEquals(0, line["pending"])
        assertEquals(1, line["removed"])
        val blocked = scanCameraActionDiag("review", "blocked", ScanCameraBlock.NoPages, pages = 0, pending = 0).toMap()
        assertEquals("no_pages", blocked["why"])
        assertEquals(0, blocked["removed"])
        // ids for the log are short lowercase words, each its own
        val ids = ScanCameraControl.entries.map { it.id } + ScanCameraBlock.entries.map { it.id }
        assertTrue(ids.all { Regex("[a-z_]+").matches(it) })
        assertEquals(ids.size, ids.toSet().size)
    }
}
