package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageLook
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageSettings
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanPaperChoice
import io.github.artcraftandroid.pdfcraft.ui.scan.scanApplyAll
import io.github.artcraftandroid.pdfcraft.ui.scan.scanApplyAllChanges
import io.github.artcraftandroid.pdfcraft.ui.scan.scanShownDecisions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every page of a scan made in the background (#465). */
class ScanPageQueueTest {
    private val corners = ScanPage.WHOLE.toList()
    private val key = ScanPreviewKey("/p.jpg", corners, ScanFilter.CleanScan, false, ScanLab())

    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        assertTrue(ScanLab().backgroundPages)
        assertFalse(ScanLab(backgroundPages = false).asDesigned)
        assertEquals(ScanLab(backgroundPages = false), ScanLab.decode(ScanLab(backgroundPages = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto;unfound_doc=0").backgroundPages)
        assertEquals(false, ScanLab(backgroundPages = false).diag().toMap()["background_pages"])
        assertEquals(false, ScanLab(backgroundPages = false).json().getBoolean("background_pages"))
    }

    @Test
    fun aPageSaysWhyItIsMadeAgain() {
        assertEquals("new", scanQueueWhy(null, key, applyAll = false))
        assertEquals("new", scanQueueWhy(null, key, applyAll = true))
        assertEquals("look", scanQueueWhy(key, key.copy(filter = ScanFilter.Grayscale), applyAll = false))
        assertEquals("look", scanQueueWhy(key, key.copy(strength = 80, paper = ScanPaperChoice.White), applyAll = false))
        assertEquals("apply_all", scanQueueWhy(key, key.copy(flatten = true), applyAll = true))
        assertEquals("borders", scanQueueWhy(key, key.copy(corners = listOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)), applyAll = false))
        assertEquals("borders", scanQueueWhy(key, key.copy(flatten = true), applyAll = false))
        assertEquals("borders", scanQueueWhy(key, key.copy(turns = 1), applyAll = false))
        assertEquals("borders", scanQueueWhy(key, key.copy(preset = "receipt"), applyAll = false))
        assertEquals("options", scanQueueWhy(key, key.copy(lab = ScanLab(sharpen = 60)), applyAll = false))
        assertEquals("options", scanQueueWhy(key, key.copy(autoUpright = false), applyAll = false))
    }

    @Test
    fun thePageOnScreenGoesFirst() {
        assertEquals(4, scanQueueNext(listOf(1, 2, 4), shown = 4))
        assertEquals(1, scanQueueNext(listOf(2, 1, 4), shown = 0))
        assertNull(scanQueueNext(emptyList(), shown = 0))
    }

    @Test
    fun theScreenCountsThePagesReady() {
        assertEquals(3 to 9, scanPreparingCount(3, 9))
        assertEquals(0 to 1, scanPreparingCount(0, 1))
        assertNull(scanPreparingCount(9, 9))
        assertNull(scanPreparingCount(0, 0))
    }

    @Test
    fun aThumbnailIsMarkedUntilItsPageIsReady() {
        assertEquals(ScanPageMark.Ready, scanPageMark(ready = true, working = true))
        assertEquals(ScanPageMark.Working, scanPageMark(ready = false, working = true))
        assertEquals(ScanPageMark.Waiting, scanPageMark(ready = false, working = false))
    }

    @Test
    fun aKeptPageSaysWhetherItWasMadeBeforeItWasShown() {
        assertNull(scanMadeBeforeShown(on = false, atFirstShow = true, made = true))
        assertEquals(true, scanMadeBeforeShown(on = true, atFirstShow = true, made = true))
        assertEquals(false, scanMadeBeforeShown(on = true, atFirstShow = false, made = true))
        // saved without being opened
        assertEquals(true, scanMadeBeforeShown(on = true, atFirstShow = null, made = true))
        assertEquals(false, scanMadeBeforeShown(on = true, atFirstShow = null, made = false))
        val json = JSONObject(scanTestPageJson("1.0 (1)", true, ScanPage.WHOLE, null, null, "auto", 60, false, ScanLab(), null, null, madeBeforeShown = true, shownKept = scanShownDecisions(null, 1, "measured", "receipt")!!.json()))
        assertTrue(json.getBoolean("made_before_shown"))
        assertEquals(1, json.getJSONObject("shown_kept").getInt("turns"))
        assertEquals("receipt", json.getJSONObject("shown_kept").getString("preset"))
        assertTrue(json.getJSONObject("options").getBoolean("background_pages"))
        val off = JSONObject(scanTestPageJson("1.0 (1)", true, ScanPage.WHOLE, null, null, "auto", 60, false, ScanLab(), null, null))
        assertTrue(off.isNull("made_before_shown"))
        assertTrue(off.isNull("shown_kept"))
    }

    @Test
    fun saveKeepsTheTurnAndTheDocumentTypeThePageShowed() {
        // the classifier's turn and the measured type, as shown
        assertEquals(1, scanShownDecisions(null, 5, "measured", "book_page")!!.turns)
        assertEquals("book_page", scanShownDecisions(null, 1, "measured", "book_page")!!.preset)
        // the person's own turn is used anyway; a chosen or app-given type is not replaced
        assertNull(scanShownDecisions(2, 2, "chosen", "receipt"))
        assertNull(scanShownDecisions(2, 2, "no_outline", "document"))
        assertNull(scanShownDecisions(2, 2, "measured", null))
        assertEquals(0, scanShownDecisions(null, 0, "off", null)!!.turns)
        // the native request then asks for that turn instead of the classifier, and for that type
        val shown = scanShownDecisions(null, 3, "measured", "receipt")!!
        val request = scanPageRequest("/a", ScanPage.WHOLE, ScanFilter.CleanScan, false, ScanLab(), "", shown.turns, preset = shown.preset)
        assertFalse(request.upright)
        assertEquals(3, request.turns.toInt())
        assertEquals("receipt", request.preset)
    }

    @Test
    fun applyToAllCarriesTheDocumentTypeAndFlattenPage() {
        val first = ScanPageSettings(ScanPageLook(ScanFilter.CleanScan, 70, ScanPaperChoice.White), "book_page", true)
        val other = ScanPageSettings(ScanPageLook(), null, null)
        val all = scanApplyAll(listOf(first, other, other), 0)
        assertEquals(listOf(first, first, first), all)
        assertTrue(scanApplyAllChanges(listOf(first, other), 0))
        // only Flatten page differs: still worth applying
        assertTrue(scanApplyAllChanges(listOf(first, first.copy(flatten = null)), 0))
        assertFalse(scanApplyAllChanges(listOf(first, first), 1))
        assertFalse(scanApplyAllChanges(listOf(first), 5))
        assertEquals(listOf(first), scanApplyAll(listOf(first), 5))
    }
}
