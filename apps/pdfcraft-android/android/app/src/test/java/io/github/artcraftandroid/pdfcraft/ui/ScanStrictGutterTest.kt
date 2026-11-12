package io.github.artcraftandroid.pdfcraft.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The book-page gutter rule (#427): the testing option, the request, the log and the page file. */
class ScanStrictGutterTest {
    private val corners = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        val lab = ScanLab()
        assertTrue(lab.strictGutter)
        assertFalse(lab.copy(strictGutter = false).asDesigned)
        assertEquals(ScanLab(strictGutter = false), ScanLab.decode(ScanLab(strictGutter = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto").strictGutter)
        assertEquals(false, ScanLab(strictGutter = false).diag().toMap()["strict_gutter"])
        assertEquals(false, ScanLab(strictGutter = false).json().getBoolean("strict_gutter"))
    }

    @Test
    fun theRequestCarriesTheRuleAndAPreviewMadeWithOneIsNotTheOther() {
        assertTrue(scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(), "").strictGutter)
        assertFalse(scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(strictGutter = false), "").strictGutter)
        val page = ScanPreviewKey("/a", corners.toList(), ScanFilter.CleanScan, false, ScanLab())
        assertFalse(page == page.copy(lab = ScanLab(strictGutter = false)))
    }

    @Test
    fun aKeptPageSaysWhatBothRulesReadAndTheBrightShareByItsNewName() {
        val kind = ScanTestKind("document", false, 60, 30, false, false, false, 1.414, 0.05, 0.0, 0.0, 0.3, 0.42, false, false, gutterLoose = true, gutterCover = 0.61234)
        val result = ScanTestResult(1000, 1414, "jpeg", 200.0, "a4", "known_focal", false, false, 0.0)
        val type = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 60, false, ScanLab(), "page-01.out.jpg", result, kind = kind))
            .getJSONObject("document_type")
        assertFalse(type.getBoolean("gutter_band"))
        assertTrue(type.getBoolean("gutter_loose"))
        assertEquals(0.612, type.getDouble("gutter_cover"), 1e-9)
        assertEquals(0.42, type.getDouble("bright_share"), 1e-9)
        assertFalse(type.has("glare_share"))
    }
}
