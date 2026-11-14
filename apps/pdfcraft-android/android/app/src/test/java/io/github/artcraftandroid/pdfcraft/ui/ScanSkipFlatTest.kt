package io.github.artcraftandroid.pdfcraft.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A page already flat is not flattened (#549): the testing option, the request and the page file. */
class ScanSkipFlatTest {
    private val corners = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        assertTrue(ScanLab().skipFlat)
        assertFalse(ScanLab(skipFlat = false).asDesigned)
        assertEquals(ScanLab(skipFlat = false), ScanLab.decode(ScanLab(skipFlat = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto").skipFlat)
        assertEquals(false, ScanLab(skipFlat = false).diag().toMap()["skip_flat"])
        assertEquals(false, ScanLab(skipFlat = false).json().getBoolean("skip_flat"))
    }

    @Test
    fun theRequestAsksForItOnlyWithTheOptionOn() {
        assertTrue(scanPageRequest("/a", corners, ScanFilter.CleanScan, true, ScanLab(), "").skipFlat)
        assertFalse(scanPageRequest("/a", corners, ScanFilter.CleanScan, true, ScanLab(skipFlat = false), "").skipFlat)
    }

    @Test
    fun aKeptPageSaysItWasFoundFlat() {
        val result = ScanTestResult(1000, 1400, "jpeg", 200.0, "a4", "known_focal", false, false, 0.0, flattenSkippedFlat = true)
        val json = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 60, true, ScanLab(), "page-01.out.jpg", result))
        assertTrue(json.getJSONObject("result").getBoolean("flatten_skipped_flat"))
        assertTrue(json.getJSONObject("options").getBoolean("skip_flat"))
        val plain = ScanTestResult(1000, 1400, "jpeg", 200.0, "a4", "known_focal", false, true, 0.6)
        assertFalse(JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 60, true, ScanLab(), "page-01.out.jpg", plain)).getJSONObject("result").getBoolean("flatten_skipped_flat"))
    }
}
