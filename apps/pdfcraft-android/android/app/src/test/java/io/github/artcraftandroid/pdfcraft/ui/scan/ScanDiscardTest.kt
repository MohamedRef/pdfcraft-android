package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanDiscard
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.ScanPage
import io.github.artcraftandroid.pdfcraft.ui.scanTestPageJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Back and Delete on the scan screen ask before a scan or a page is thrown away (#456). */
class ScanDiscardTest {
    @Test
    fun backAsksOnlyWhenThereIsAPageToLose() {
        assertFalse(scanBackAsks(0, ScanLab()))
        assertTrue(scanBackAsks(1, ScanLab()))
        assertTrue(scanBackAsks(20, ScanLab()))
        // the testing option off: Back discards at once, as before
        assertFalse(scanBackAsks(20, ScanLab(askDiscard = false)))
    }

    @Test
    fun deleteAsksBeforeAPageGoes() {
        assertTrue(scanDeleteAsks(1, ScanLab()))
        assertFalse(scanDeleteAsks(0, ScanLab()))
        assertFalse(scanDeleteAsks(3, ScanLab(askDiscard = false)))
    }

    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        assertTrue(ScanLab().askDiscard)
        assertTrue(ScanLab().asDesigned)
        assertFalse(ScanLab(askDiscard = false).asDesigned)
        assertEquals(ScanLab(askDiscard = false), ScanLab.decode(ScanLab(askDiscard = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto;unfound_doc=0").askDiscard)
        assertEquals(false, ScanLab(askDiscard = false).diag().toMap()["ask_discard"])
        assertEquals(false, ScanLab(askDiscard = false).json().getBoolean("ask_discard"))
    }

    @Test
    fun theLogCountsPagesFromOne() {
        val delete = scanPageDeleteDiag(0, 3, asked = true, confirmed = false).toMap()
        assertEquals(1, delete["index"])
        assertEquals(3, delete["pages"])
        assertEquals(true, delete["asked"])
        assertEquals(false, delete["confirmed"])
        val discard = scanDiscardDiag(20, asked = true, confirmed = true).toMap()
        assertEquals(mapOf<String, Any?>("pages" to 20, "asked" to true, "confirmed" to true), discard)
    }

    @Test
    fun aKeptDiscardedPageSaysWhetherThePersonWasAsked() {
        val whole = ScanPage.WHOLE
        val json = JSONObject(scanTestPageJson("1.0 (1)", false, whole, null, null, null, null, false, ScanLab(), null, null, discard = ScanDiscard(asked = true, pages = 4)))
        assertEquals("discarded", json.getString("outcome"))
        assertTrue(json.getJSONObject("discard").getBoolean("asked"))
        assertEquals(4, json.getJSONObject("discard").getInt("pages"))
        assertTrue(json.getJSONObject("options").getBoolean("ask_discard"))
        // a saved page has none
        assertTrue(JSONObject(scanTestPageJson("1.0 (1)", true, whole, null, null, "auto", 60, false, ScanLab(), null, null)).isNull("discard"))
    }
}
