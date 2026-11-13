package io.github.artcraftandroid.pdfcraft.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A grainy page's paper drawn white (#466): the testing option, the request and the page file. */
class ScanGrainWhiteTest {
    private val corners = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        val lab = ScanLab()
        assertTrue(lab.grainWhite)
        assertFalse(lab.copy(grainWhite = false).asDesigned)
        assertEquals(ScanLab(grainWhite = false), ScanLab.decode(ScanLab(grainWhite = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto;denoise=0").grainWhite)
        assertEquals(false, ScanLab(grainWhite = false).diag().toMap()["grain_white"])
        assertEquals(false, ScanLab(grainWhite = false).json().getBoolean("grain_white"))
    }

    @Test
    fun theRequestCarriesTheOptionAndAPreviewMadeWithItIsNotOneMadeWithout() {
        assertTrue(scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(), "").grainWhite)
        assertFalse(scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(grainWhite = false), "").grainWhite)
        // smoothing the grain is its own option
        assertTrue(scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(grainWhite = false), "").denoise)
        val page = ScanPreviewKey("/a", corners.toList(), ScanFilter.CleanScan, false, ScanLab())
        assertFalse(page == page.copy(lab = ScanLab(grainWhite = false)))
    }

    @Test
    fun aKeptPageSaysHowMuchItsPaperVariedAndWhereWhiteBegan() {
        val grainy = ScanTestResult(1000, 1414, "jpeg", 200.0, "a4", "known_focal", false, false, 0.0, paperSpread = 0.04512, whitePoint = 0.88952)
        val result = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 60, false, ScanLab(), "page-01.out.jpg", grainy)).getJSONObject("result")
        assertEquals(0.0451, result.getDouble("paper_spread"), 1e-9)
        assertEquals(0.89, result.getDouble("white_point"), 1e-9)
        // another filter measures neither
        val plain = ScanTestResult(1000, 1414, "jpeg", 200.0, "a4", "known_focal", false, false, 0.0)
        val other = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "original", 0, false, ScanLab(), "page-01.out.jpg", plain)).getJSONObject("result")
        assertTrue(other.isNull("paper_spread") && other.isNull("white_point"))
    }
}
