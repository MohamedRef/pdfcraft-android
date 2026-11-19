package io.github.artcraftandroid.pdfcraft.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/** #460, #716: where Discard on Adjust borders goes once the photo is dropped. */
class ScanBordersDiscardTest {
    @Test
    fun theOnlyPhotoPickedEndsTheScanAndNeverOpensTheCamera() {
        // Scan from photos, one photo, Back, Discard: out of the scan, as closing the picker does (#716)
        assertEquals("end", scanBordersDiscardTo(ScanBordersOpener.Import, pages = 1))
        assertEquals("end", scanBordersDiscardTo(ScanBordersOpener.Import, pages = 0))
    }

    @Test
    fun anImportedPhotoWithOtherPagesGoesBackToThem() {
        assertEquals("document", scanBordersDiscardTo(ScanBordersOpener.Import, pages = 2))
        assertEquals("document", scanBordersDiscardTo(ScanBordersOpener.Import, pages = 9))
    }

    @Test
    fun aPhotoJustTakenBringsTheCameraBack() {
        for (pages in listOf(1, 3)) {
            assertEquals("camera", scanBordersDiscardTo(ScanBordersOpener.Capture, pages))
            assertEquals("camera", scanBordersDiscardTo(ScanBordersOpener.Retake, pages))
        }
    }
}
