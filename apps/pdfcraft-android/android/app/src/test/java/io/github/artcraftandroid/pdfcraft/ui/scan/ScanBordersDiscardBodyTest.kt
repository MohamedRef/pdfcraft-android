package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** #737: "Discard this photo?" says what happens to the photo, by where it came from. */
class ScanBordersDiscardBodyTest {
    @Test
    fun aPickedPhotoIsLeftOutOfTheScanNotDeleted() {
        assertEquals(R.string.scan_borders_discard_body_picked, scanBordersDiscardBody(ScanBordersOpener.Import))
    }

    @Test
    fun aPhotoJustTakenIsDeleted() {
        for (opener in listOf(ScanBordersOpener.Capture, ScanBordersOpener.Retake, ScanBordersOpener.Page)) {
            assertEquals(R.string.scan_borders_discard_body, scanBordersDiscardBody(opener))
        }
    }
}
