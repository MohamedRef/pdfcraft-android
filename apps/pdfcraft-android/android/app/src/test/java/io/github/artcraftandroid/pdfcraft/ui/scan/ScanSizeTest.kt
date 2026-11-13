package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.core.ScanEncoding
import io.github.artcraftandroid.pdfcraft.ui.ScanFilter
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.scanPageRequest
import io.github.artcraftandroid.pdfcraft.ui.scanTestPageJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The size choice at Save (#505): the testing options, the request, the log and the page file. */
class ScanSizeTest {
    private val corners = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
    private val grey = ScanEncoding("small", true, "jpeg_grey", 1654u, 2339u, 200u, 65u.toUByte(), false, 151_234uL, "no_colour+stroke")

    @Test
    fun theTestingOptionsAreOnByDefaultAndSurviveTheirLine() {
        val lab = ScanLab()
        assertTrue(lab.autoEncode)
        assertNull(lab.size)
        val set = ScanLab(autoEncode = false, size = "smallest")
        assertEquals(set, ScanLab.decode(set.encode()))
        assertFalse(set.asDesigned)
        // a size the app does not know is the person's choice
        assertNull(ScanLab.decode("size=huge").size)
        // a line written before #505 is the scanner as designed
        assertTrue(ScanLab.decode("keep=0;markup=1").autoEncode)
    }

    @Test
    fun aSizeSetForEverySaveWinsOverThePersonsChoice() {
        assertEquals("small", scanSizeFor(ScanLab(), "small"))
        assertEquals("smallest", scanSizeFor(ScanLab(size = "smallest"), "best"))
        assertEquals(ScanSizeSetting.DEFAULT, scanSizeFor(ScanLab(), "unknown"))
        assertEquals("balanced", ScanSizeSetting.DEFAULT)
    }

    @Test
    fun onlySaveAsksForASizeAndTheOptionDecidesTheEncoding() {
        val preview = scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(), "")
        assertEquals("best", preview.size)
        assertTrue(preview.autoEncode)
        val saved = scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(autoEncode = false), "/out", size = "small")
        assertEquals("small", saved.size)
        assertFalse(saved.autoEncode)
    }

    @Test
    fun theLogHasEveryFieldTheIssueAsksFor() {
        val fields = scanEncodingDiag(grey).toMap()
        assertEquals("jpeg_grey", fields["encoding"])
        assertEquals(1654L * 2339L, fields["px_out"])
        assertEquals(200L, fields["dpi_out"])
        assertEquals(65, fields["quality"])
        assertEquals(151_234L, fields["bytes"])
        assertEquals("no_colour+stroke", fields["encode_why"])
        assertEquals("small", fields["size_choice"])
        // a camera file kept as it was
        assertEquals("camera_file", scanEncodingDiag(null).toMap()["encoding"])
        val saved = scanSavedSizeDiag("balanced", 700_000, 7, 690_000).toMap()
        assertEquals("balanced", saved["size_choice"])
        assertEquals(100_000L, saved["bytes_per_page"])
        assertEquals(690_000L, saved["pages_bytes"])
    }

    @Test
    fun theKeptPageFileHasTheSameFields() {
        val page = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 60, false, ScanLab(), "page-01.out.jpg", null, encoding = scanEncodingJson(grey)))
        val e = page.getJSONObject("encoding")
        assertEquals("jpeg_grey", e.getString("encoding"))
        assertEquals(1654L * 2339L, e.getLong("px_out"))
        assertEquals(151_234L, e.getLong("bytes"))
        assertEquals("no_colour+stroke", e.getString("encode_why"))
        assertTrue(JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 60, false, ScanLab(), "page-01.out.jpg", null)).isNull("encoding"))
    }

    @Test
    fun theEstimateIsTheFirstPageTimesThePages() {
        assertEquals(700L, scanSizeEstimate(100, 7))
        assertEquals(100L, scanSizeEstimate(100, 0))
        assertTrue(scanSizeShowsPage("small") && scanSizeShowsPage("smallest"))
        assertFalse(scanSizeShowsPage("best") || scanSizeShowsPage("balanced"))
    }
}
