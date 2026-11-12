package io.github.artcraftandroid.pdfcraft.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The photograph rule (#427): the testing option, the request, the log and the page file. */
class ScanStrictPhotoTest {
    private val corners = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        val lab = ScanLab()
        assertTrue(lab.strictPhoto)
        assertFalse(lab.copy(strictPhoto = false).asDesigned)
        assertEquals(ScanLab(strictPhoto = false), ScanLab.decode(ScanLab(strictPhoto = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto;strict_gutter=0").strictPhoto)
        assertEquals(false, ScanLab(strictPhoto = false).diag().toMap()["strict_photo"])
        assertEquals(false, ScanLab(strictPhoto = false).json().getBoolean("strict_photo"))
    }

    @Test
    fun theRequestCarriesTheRuleAndAPreviewMadeWithOneIsNotTheOther() {
        assertTrue(scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(), "").strictPhoto)
        assertFalse(scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(strictPhoto = false), "").strictPhoto)
        // the other rule's option is its own
        assertTrue(scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(strictPhoto = false), "").strictGutter)
        val page = ScanPreviewKey("/a", corners.toList(), ScanFilter.CleanScan, false, ScanLab())
        assertFalse(page == page.copy(lab = ScanLab(strictPhoto = false)))
    }

    @Test
    fun aKeptPageSaysWhatBothRulesRead() {
        val kind = ScanTestKind("document", false, 60, 30, false, false, false, 1.414, 0.05, 0.0, 0.0, 0.3, 0.42, false, false, photoLoose = 0.14833)
        val result = ScanTestResult(1000, 1414, "jpeg", 200.0, "a4", "known_focal", false, false, 0.0)
        val type = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 60, false, ScanLab(), "page-01.out.jpg", result, kind = kind))
            .getJSONObject("document_type")
        assertEquals(0.0, type.getDouble("photo_share"), 1e-9)
        assertEquals(0.148, type.getDouble("photo_loose"), 1e-9)
    }
}
