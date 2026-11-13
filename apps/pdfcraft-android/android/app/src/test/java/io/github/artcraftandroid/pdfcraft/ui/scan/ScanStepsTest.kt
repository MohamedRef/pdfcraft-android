package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adjust borders as the step after a photo (#458, part of #455). */
class ScanStepsTest {
    @Test
    fun onePageGoesStraightToTheBorders() {
        assertEquals(ScanBordersOpener.Capture, scanBordersAfterCapture(steps = true, bar = true, mode = ScanCameraMode.Single, retaking = false, retakeFromBorders = false))
        // Many pages and Book: not between photos
        assertNull(scanBordersAfterCapture(steps = true, bar = true, mode = ScanCameraMode.Batch, retaking = false, retakeFromBorders = false))
        assertNull(scanBordersAfterCapture(steps = true, bar = true, mode = ScanCameraMode.Book, retaking = false, retakeFromBorders = false))
        // the steps off, or the old camera bar (no modes): today's scan screen
        assertNull(scanBordersAfterCapture(steps = false, bar = true, mode = ScanCameraMode.Single, retaking = false, retakeFromBorders = false))
        assertNull(scanBordersAfterCapture(steps = true, bar = false, mode = ScanCameraMode.Single, retaking = false, retakeFromBorders = false))
    }

    @Test
    fun aRetakeReturnsWhereItCameFrom() {
        assertEquals(ScanBordersOpener.Retake, scanBordersAfterCapture(steps = true, bar = true, mode = ScanCameraMode.Batch, retaking = true, retakeFromBorders = true))
        // a retake from the scan screen goes back to the scan screen, even in One page
        assertNull(scanBordersAfterCapture(steps = true, bar = true, mode = ScanCameraMode.Single, retaking = true, retakeFromBorders = false))
        assertEquals(ScanBordersOpener.Retake, scanBordersAfterImport(steps = true, photos = 1, retaking = true, retakeFromBorders = true))
    }

    @Test
    fun oneImportedPhotoIsLikeOnePage() {
        assertEquals(ScanBordersOpener.Import, scanBordersAfterImport(steps = true, photos = 1, retaking = false, retakeFromBorders = false))
        assertNull(scanBordersAfterImport(steps = true, photos = 3, retaking = false, retakeFromBorders = false))
        assertNull(scanBordersAfterImport(steps = false, photos = 1, retaking = false, retakeFromBorders = false))
        assertTrue(ScanBordersOpener.Import.afterPhoto && ScanBordersOpener.Capture.afterPhoto)
        assertFalse(ScanBordersOpener.Page.afterPhoto)
    }

    @Test
    fun theScreenKnowsWhenTheSearchHasAnswered() {
        val quad = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
        assertEquals(ScanBordersFinding.Searching, scanBordersFinding(null, searched = false, uncropped = null))
        assertEquals(ScanBordersFinding.Found, scanBordersFinding(quad, searched = true, uncropped = null))
        assertEquals(ScanBordersFinding.NotFound, scanBordersFinding(null, searched = true, uncropped = null))
        // #444's mark of a page whose outline was not found counts as an answer too
        assertEquals(ScanBordersFinding.NotFound, scanBordersFinding(null, searched = false, uncropped = ScanPage.WHOLE))
    }

    @Test
    fun theOneButtonNamesWhatATapWillDo() {
        val found = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
        assertEquals(ScanBordersToggle.WholePhoto, scanBordersToggle(found.copyOf(), found))
        assertEquals(ScanBordersToggle.Auto, scanBordersToggle(ScanPage.WHOLE, found))
        val moved = found.copyOf().also { it[0] = 0.2f }
        assertEquals(ScanBordersToggle.Auto, scanBordersToggle(moved, found))
        // nothing found: Auto (which says when it finds nothing)
        assertEquals(ScanBordersToggle.Auto, scanBordersToggle(ScanPage.WHOLE, null))
    }

    @Test
    fun rotateTurnsBothWays() {
        assertEquals(3, scanTurnAfter(null, 0, -1))
        assertEquals(1, scanTurnAfter(null, 0, 1))
        // from what is shown: the classifier's turn when the person has not turned the page
        assertEquals(0, scanTurnAfter(null, 1, -1))
        assertEquals(0, scanTurnAfter(3, 1, 1))
    }
}
