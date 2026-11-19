package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanFinding
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.ScanPage
import io.github.artcraftandroid.pdfcraft.ui.scanTestPageJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pages whose page was not found or is unsure, offered for a check (#758). */
class ScanUnsureTest {
    private val quad = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
    private val moved = floatArrayOf(0.12f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
    private fun found(confidence: Double, source: String = "model") = ScanFinding(source, confidence, 0, 1, 4, 0)
    private val nothing = ScanFinding("none", 0.39, 0, 7, 0, 0)

    @Test
    fun aPageNotFoundIsUnsureUntilItsCornersMoveOrItsBordersAreLookedAt() {
        // the owner's log of 2026-11-19: two photos with no page found went into the PDF as whole photos
        assertTrue(scanPageUnsure(ScanPage.WHOLE, null, ScanPage.WHOLE, nothing, 0))
        assertFalse(scanPageUnsure(moved, null, ScanPage.WHOLE, nothing, 0))
        assertFalse(scanPageUnsure(ScanPage.WHOLE, null, ScanPage.WHOLE, nothing, visits = 1))
        // a page that kept no start corners counts from the whole photo
        assertTrue(scanPageUnsure(ScanPage.WHOLE, null, null, nothing, 0))
    }

    @Test
    fun aPageFoundUnderTheSureMarkIsUnsureAndOneAboveItIsNot() {
        // the detector's own mark: SURE in detect.rs
        assertEquals(0.7, SCAN_SURE_CONFIDENCE, 0.0)
        for (c in listOf(0.56, 0.58, 0.61, 0.63, 0.66)) assertTrue("$c", scanPageUnsure(quad, quad, null, found(c), 0))
        for (c in listOf(0.7, 0.75, 0.85, 0.88)) assertFalse("$c", scanPageUnsure(quad, quad, null, found(c), 0))
        // the line detector's answer has no confidence of its own (0.5)
        assertTrue(scanPageUnsure(quad, quad, null, found(0.5, "lines"), 0))
        // corners moved, or Adjust borders left with Done: someone has looked
        assertFalse(scanPageUnsure(moved, quad, null, found(0.61), 0))
        assertFalse(scanPageUnsure(quad, quad, null, found(0.61), visits = 1))
    }

    @Test
    fun aPageThatWasNotSearchedIsNotOffered() {
        // a low-light merge comes straightened, a restored page keeps no search
        assertFalse(scanPageUnsure(ScanPage.WHOLE, null, ScanPage.WHOLE, null, 0))
        assertFalse(scanPageUnsure(quad, quad, null, null, 0))
    }

    @Test
    fun theOfferNeedsItsOptionsAndAPageNotDismissedAndGivesWayToTheImportOffer() {
        val unsure = setOf("a", "b")
        assertTrue(scanUnsureOffers(true, true, true, unsure, emptySet(), 0))
        assertFalse(scanUnsureOffers(false, true, true, unsure, emptySet(), 0))
        assertFalse(scanUnsureOffers(true, false, true, unsure, emptySet(), 0))
        assertFalse(scanUnsureOffers(true, true, false, unsure, emptySet(), 0))
        assertFalse(scanUnsureOffers(true, true, true, emptySet(), emptySet(), 0))
        // closed: it comes back only for a page that wasn't unsure then
        assertFalse(scanUnsureOffers(true, true, true, unsure, unsure, 0))
        assertTrue(scanUnsureOffers(true, true, true, unsure + "c", unsure, 0))
        // the offer after an import covers every imported page
        assertFalse(scanUnsureOffers(true, true, true, unsure, emptySet(), 5))
    }

    @Test
    fun theLogLineCountsThePagesOffered() {
        val line = scanUnsureOfferDiag(pages = 7, notFound = 2, scanPages = 12).toMap()
        assertEquals(7, line["pages"])
        assertEquals(2, line["not_found"])
        assertEquals(5, line["low_confidence"])
        assertEquals(12, line["scan_pages"])
        assertEquals(0.7, line["sure"])
    }

    @Test
    fun theKeptPageSaysWhetherItWasStillUnsure() {
        val unsure = JSONObject(scanTestPageJson("t", true, quad, quad, found(0.61), "auto", 60, false, ScanLab(), null, null, unsure = true))
        assertTrue(unsure.getBoolean("unsure"))
        val sure = JSONObject(scanTestPageJson("t", true, quad, quad, found(0.88), "auto", 60, false, ScanLab(), null, null, unsure = false))
        assertFalse(sure.getBoolean("unsure"))
        assertTrue(JSONObject(scanTestPageJson("t", true, quad, quad, null, "auto", 60, false, ScanLab(), null, null)).isNull("unsure"))
    }

    @Test
    fun theTestingOptionIsOnAndRemembered() {
        assertTrue(ScanLab().checkUnsure)
        val off = ScanLab(checkUnsure = false)
        assertFalse(ScanLab.decode(off.encode()).checkUnsure)
        assertFalse(off.asDesigned)
        assertEquals(false, off.diag().toMap()["check_unsure"])
        assertEquals(false, off.json().getBoolean("check_unsure"))
        // a phone that saved its options before #758 has it on
        assertTrue(ScanLab.decode("keep=0;batch=1").checkUnsure)
    }
}
