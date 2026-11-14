package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.scan.scanFullReadyDiag
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The saved page takes its preview's flattening decision (#549): the testing option, the request, the log and the page file. */
class ScanFlattenReuseTest {
    private val corners = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        assertTrue(ScanLab().flattenReuse)
        assertFalse(ScanLab(flattenReuse = false).asDesigned)
        assertEquals(ScanLab(flattenReuse = false), ScanLab.decode(ScanLab(flattenReuse = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto").flattenReuse)
        assertEquals(false, ScanLab(flattenReuse = false).diag().toMap()["flatten_reuse"])
        assertEquals(false, ScanLab(flattenReuse = false).json().getBoolean("flatten_reuse"))
    }

    @Test
    fun theRequestAsksForTheDecisionOnlyWithTheOptionOn() {
        assertTrue(scanPageRequest("/a", corners, ScanFilter.CleanScan, true, ScanLab(), "").flattenReuse)
        assertFalse(scanPageRequest("/a", corners, ScanFilter.CleanScan, true, ScanLab(flattenReuse = false), "").flattenReuse)
    }

    @Test
    fun aKeptPageAndThePageMadeAheadSayWhetherTheDecisionWasTakenOver() {
        val result = ScanTestResult(1000, 1400, "jpeg", 200.0, "a4", "known_focal", false, true, 0.6, flattenReused = true)
        val json = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 60, true, ScanLab(), "page-01.out.jpg", result))
        assertTrue(json.getJSONObject("result").getBoolean("flatten_reused"))
        assertTrue(json.getJSONObject("options").getBoolean("flatten_reuse"))
        val plain = ScanTestResult(1000, 1400, "jpeg", 200.0, "a4", "known_focal", false, false, 0.0)
        val none = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 60, true, ScanLab(), "page-01.out.jpg", plain))
        assertFalse(none.getJSONObject("result").getBoolean("flatten_reused"))
        assertEquals(true, scanFullReadyDiag(0, 900, 1_000_000, "new", true).toMap()["flatten_reused"])
        assertEquals(false, scanFullReadyDiag(0, 900, 1_000_000, "new").toMap()["flatten_reused"])
    }
}
