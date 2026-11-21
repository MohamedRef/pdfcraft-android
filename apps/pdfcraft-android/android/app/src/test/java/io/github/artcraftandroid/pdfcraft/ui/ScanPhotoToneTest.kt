package io.github.artcraftandroid.pdfcraft.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Photographs on a page of print keep their own tone (#784): the testing option, the request and the page file. */
class ScanPhotoToneTest {
    private val corners = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        val lab = ScanLab()
        assertTrue(lab.photoTone)
        assertFalse(lab.copy(photoTone = false).asDesigned)
        assertEquals(ScanLab(photoTone = false), ScanLab.decode(ScanLab(photoTone = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto;denoise=0").photoTone)
        assertEquals(false, ScanLab(photoTone = false).diag().toMap()["photo_tone"])
        assertEquals(false, ScanLab(photoTone = false).json().getBoolean("photo_tone"))
    }

    @Test
    fun theRequestCarriesTheOptionAndAPreviewMadeWithItIsNotOneMadeWithout() {
        assertTrue(scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(), "").photoTone)
        assertFalse(scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(photoTone = false), "").photoTone)
        // sharpening photographs gently is its own option
        assertTrue(scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(photoTone = false), "").photoSharpen)
        val page = ScanPreviewKey("/a", corners.toList(), ScanFilter.CleanScan, false, ScanLab())
        assertFalse(page == page.copy(lab = ScanLab(photoTone = false)))
    }

    @Test
    fun aKeptPageSaysHowMuchOfItTookThePhotographsTone() {
        val toned = ScanTestResult(1000, 1414, "jpeg", 200.0, "a4", "known_focal", false, false, 0.0, photoToneShare = 0.26874, photoToneFingers = 1)
        val result = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 60, false, ScanLab(), "page-01.out.jpg", toned)).getJSONObject("result")
        assertEquals(0.2687, result.getDouble("photo_tone_share"), 1e-9)
        assertEquals(1, result.getInt("photo_tone_fingers"))
        // another filter reports neither
        val plain = ScanTestResult(1000, 1414, "jpeg", 200.0, "a4", "known_focal", false, false, 0.0)
        val other = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "original", 0, false, ScanLab(), "page-01.out.jpg", plain)).getJSONObject("result")
        assertTrue(other.isNull("photo_tone_share") && other.isNull("photo_tone_fingers"))
    }
}
