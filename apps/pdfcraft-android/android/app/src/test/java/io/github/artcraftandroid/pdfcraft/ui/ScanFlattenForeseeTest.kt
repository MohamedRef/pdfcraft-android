package io.github.artcraftandroid.pdfcraft.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Flattening that the check could not keep is not run (#785): the testing option, the request and the page file. */
class ScanFlattenForeseeTest {
    private val corners = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        assertTrue(ScanLab().flattenForesee)
        assertFalse(ScanLab(flattenForesee = false).asDesigned)
        assertEquals(ScanLab(flattenForesee = false), ScanLab.decode(ScanLab(flattenForesee = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto;skip_flat=0").flattenForesee)
        assertEquals(false, ScanLab(flattenForesee = false).diag().toMap()["flatten_foresee"])
        assertEquals(false, ScanLab(flattenForesee = false).json().getBoolean("flatten_foresee"))
    }

    @Test
    fun theRequestAsksForItOnlyWithTheOptionOn() {
        assertTrue(scanPageRequest("/a", corners, ScanFilter.CleanScan, true, ScanLab(), "").flattenForesee)
        assertFalse(scanPageRequest("/a", corners, ScanFilter.CleanScan, true, ScanLab(flattenForesee = false), "").flattenForesee)
    }

    @Test
    fun aKeptPageSaysFlatteningWasNotRun() {
        val result = ScanTestResult(1000, 1400, "jpeg", 200.0, "a4", "known_focal", false, false, 0.0, flattenForeseen = true)
        val json = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 60, true, ScanLab(), "page-01.out.jpg", result))
        assertTrue(json.getJSONObject("result").getBoolean("flatten_foreseen"))
        assertTrue(json.getJSONObject("options").getBoolean("flatten_foresee"))
        val plain = ScanTestResult(1000, 1400, "jpeg", 200.0, "a4", "known_focal", false, true, 0.6)
        assertFalse(JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 60, true, ScanLab(), "page-01.out.jpg", plain)).getJSONObject("result").getBoolean("flatten_foreseen"))
    }
}
