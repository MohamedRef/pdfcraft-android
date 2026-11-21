package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review pages on the camera opens the scan's pages, with one tap back to the camera (#780). */
class ScanReviewReturnTest {
    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        val lab = ScanLab()
        assertTrue(lab.reviewReturn)
        assertFalse(lab.copy(reviewReturn = false).asDesigned)
        assertEquals(ScanLab(reviewReturn = false), ScanLab.decode(ScanLab(reviewReturn = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto;camera_buttons=1").reviewReturn)
        // the log's options line and a kept scan's page file say which review the scan had
        assertEquals(false, ScanLab(reviewReturn = false).diag().toMap()["review_return"])
        assertEquals(true, ScanLab().diag().toMap()["review_return"])
        assertEquals(false, ScanLab(reviewReturn = false).json().getBoolean("review_return"))
        // the camera's buttons keep their own option
        assertTrue(ScanLab(reviewReturn = false).cameraButtons)
    }

    @Test
    fun reviewOpensThePagesOnlyWhenTheyExistAndTheOptionIsOn() {
        assertEquals(ScanReviewTarget.Pages, scanReviewTarget(reviewReturn = true, steps = true, continuous = true))
        // the option off: the newest page in Enhance, as at first
        assertEquals(ScanReviewTarget.Enhance, scanReviewTarget(reviewReturn = false, steps = true, continuous = true))
        // no continuous pages, or the scan's screens not in steps: nothing to open them on
        assertEquals(ScanReviewTarget.Enhance, scanReviewTarget(reviewReturn = true, steps = true, continuous = false))
        assertEquals(ScanReviewTarget.Enhance, scanReviewTarget(reviewReturn = true, steps = false, continuous = true))
        val lab = ScanLab()
        assertEquals(ScanReviewTarget.Pages, scanReviewTarget(lab.reviewReturn, lab.steps, lab.continuousPages))
    }

    @Test
    fun reviewGoesToTheNewestPageInTheContinuousPagesWithoutCountingAsASwitch() {
        val view = ScanDocViewState()
        val keys = listOf("a", "b", "c")
        // the person had the grid open, on the first page, before Add page opened the camera
        view.show(ScanDocView.Grid)
        view.anchor = ScanDocAnchor("a", 0)
        val toggles = view.toggles
        view.review(ScanDocAnchor(keys.last(), keys.lastIndex))
        assertEquals(ScanDocView.Pages, scanDocViewShown(view.chosen, continuous = true))
        assertEquals(2, scanAnchorIndex(view.anchor, keys))
        assertEquals(toggles, view.toggles)
        assertEquals(1, view.jumps)
        // again after another photo: the pages already on screen are told to move
        view.review(ScanDocAnchor("d", 3))
        assertEquals(2, view.jumps)
        assertEquals(3, scanAnchorIndex(view.anchor, keys + "d"))
        // the newest page deleted meanwhile: the last page there is
        assertEquals(2, scanAnchorIndex(view.anchor, keys))
        view.reset()
        assertEquals(0, view.jumps)
    }

    @Test
    fun theCameraLeftForReviewKeepsItsPhotosThroughARetakeOnly() {
        // a retake under review, taken or closed without a photo: the waiting camera keeps what Undo knows
        assertTrue(scanReviewKeepsSession(reviewing = true, retaking = true))
        // One page's photo, Done, Close or a retake outside a review: as before, the camera's photos are let go of
        assertFalse(scanReviewKeepsSession(reviewing = true, retaking = false))
        assertFalse(scanReviewKeepsSession(reviewing = false, retaking = true))
        assertFalse(scanReviewKeepsSession(reviewing = false, retaking = false))
    }

    @Test
    fun undoStillKnowsThePhotosAfterTheWayBackAndForgetsAPageRetakenUnderReview() {
        var session = ScanCameraSession().handed("p1", 0L).handed("p2", 10L).handed("p3", 20L).seen(listOf("p1", "p2", "p3"))
        // Review pages, then Back to camera: nothing was touched
        assertEquals("p3", session.undoTarget(listOf("p1", "p2", "p3")))
        assertEquals(0, session.pending)
        // page 2 retaken under review: its photo is another now, and Undo never reaches past it to that
        val after = listOf("p1", "r2", "p3")
        session = session.seen(after)
        assertEquals(listOf("p1", "p3"), session.handed.map { it.path })
        assertEquals("p3", session.undoTarget(after))
        assertEquals(listOf(2), scanUndoIndexes(after, "p3"))
        session = session.undone("p3")
        assertEquals("p1", session.undoTarget(listOf("p1", "r2")))
    }

    @Test
    fun theLogLineHoldsOnlyAWordAndCounts() {
        val line = scanReviewEndDiag("button", ms = 4_200L, pages = 3).toMap()
        assertEquals("button", line["by"])
        assertEquals(4_200L, line["ms"])
        assertEquals(3, line["pages"])
        assertEquals(0L, scanReviewEndDiag("back", ms = -5L, pages = 1).toMap()["ms"])
        val ids = ScanReviewTarget.entries.map { it.id }
        assertTrue(ids.all { Regex("[a-z_]+").matches(it) })
        assertEquals(ids.size, ids.toSet().size)
    }
}
