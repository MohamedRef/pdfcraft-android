package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanFilter
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.ScanPage
import io.github.artcraftandroid.pdfcraft.ui.scanPageRequest
import io.github.artcraftandroid.pdfcraft.ui.scanTestPageJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A page whose outline was not found is not measured for its kind until it is cropped (#444). */
class ScanKindSourceTest {
    private val whole = ScanPage.WHOLE
    private val cropped = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
    private val leftHalf = floatArrayOf(0f, 0f, 0.5f, 0f, 0.5f, 1f, 0f, 1f)

    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        assertTrue(ScanLab().unfoundDocument)
        assertFalse(ScanLab(unfoundDocument = false).asDesigned)
        assertEquals(ScanLab(unfoundDocument = false), ScanLab.decode(ScanLab(unfoundDocument = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto;page_strength=0").unfoundDocument)
        assertEquals(false, ScanLab(unfoundDocument = false).diag().toMap()["unfound_document"])
        assertEquals(false, ScanLab(unfoundDocument = false).json().getBoolean("unfound_document"))
    }

    @Test
    fun aPageWithNoOutlineStartsUncroppedUnlessThePhotoIsAlreadyThePage() {
        assertTrue(scanUncropped(whole, null, straightened = false).contentEquals(whole))
        // a book half of a photo with no outline keeps its own corners
        assertTrue(scanUncropped(leftHalf, null, straightened = false).contentEquals(leftHalf))
        // an outline was found: measured as usual
        assertNull(scanUncropped(cropped, cropped, straightened = false))
        // a low-light merge is already straightened to the page (#297)
        assertNull(scanUncropped(whole, null, straightened = true))
    }

    @Test
    fun croppingThePageEndsIt() {
        assertTrue(scanOutlineMissing(whole, whole.copyOf()))
        assertFalse(scanOutlineMissing(whole, cropped))
        assertFalse(scanOutlineMissing(null, whole))
    }

    @Test
    fun theKindIsDocumentAndSaysWhy() {
        val lab = ScanLab()
        assertEquals("no_outline", scanKindSource(null, outlineMissing = true, lab))
        assertEquals("document", scanPresetFor(null, outlineMissing = true, lab))
        // once cropped, Clean scan works it out
        assertEquals("measured", scanKindSource(null, outlineMissing = false, lab))
        assertNull(scanPresetFor(null, outlineMissing = false, lab))
        // the person's own choice wins
        assertEquals("chosen", scanKindSource("receipt", outlineMissing = true, lab))
        assertEquals("receipt", scanPresetFor("receipt", outlineMissing = true, lab))
        // testing option off: measured from the whole photo, as before
        assertEquals("measured", scanKindSource(null, outlineMissing = true, ScanLab(unfoundDocument = false)))
        assertNull(scanPresetFor(null, outlineMissing = true, ScanLab(unfoundDocument = false)))
        // document types off
        assertEquals("off", scanKindSource(null, outlineMissing = true, ScanLab(presets = false)))
    }

    @Test
    fun theNativeRequestAsksForADocument() {
        val lab = ScanLab()
        val asked = scanPresetFor(null, outlineMissing = true, lab)
        assertEquals("document", scanPageRequest("/a", whole, ScanFilter.CleanScan, false, lab, "", preset = asked).preset)
        assertEquals("auto", scanPageRequest("/a", cropped, ScanFilter.CleanScan, false, lab, "", preset = scanPresetFor(null, false, lab)).preset)
    }

    @Test
    fun aRestoredPageOfItsOwnStillOnTheWholePhotoCountsAsNotCropped() {
        assertTrue(scanRestoredUncropped(whole.copyOf(), null, null, straightened = false)!!.contentEquals(whole))
        assertNull(scanRestoredUncropped(cropped, null, null, straightened = false))
        assertNull(scanRestoredUncropped(whole.copyOf(), whole.copyOf(), null, straightened = false))
        assertNull(scanRestoredUncropped(whole.copyOf(), null, null, straightened = true))
    }

    @Test
    fun aKeptPageSaysWhereItsKindCameFrom() {
        val json = JSONObject(scanTestPageJson("1.0 (1)", true, whole, null, null, "auto", 60, false, ScanLab(), null, null, kindSource = "no_outline"))
        assertEquals("no_outline", json.getString("document_type_source"))
        assertTrue(json.getJSONObject("options").getBoolean("unfound_document"))
        assertTrue(JSONObject(scanTestPageJson("1.0 (1)", true, whole, null, null, "auto", 60, false, ScanLab(), null, null)).isNull("document_type_source"))
    }
}
