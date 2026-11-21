package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.core.ScanFirstPage
import io.github.artcraftandroid.pdfcraft.core.ScanPaper
import io.github.artcraftandroid.pdfcraft.core.ScanTiming
import io.github.artcraftandroid.pdfcraft.ui.scan.scanCameraPageThumb
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A page's first picture while it is made (#785): the testing option, when one is needed, the log and the page file. */
class ScanFirstPictureTest {
    private val corners = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        val lab = ScanLab()
        assertTrue(lab.firstPicture)
        assertFalse(lab.copy(firstPicture = false).asDesigned)
        assertEquals(ScanLab(firstPicture = false), ScanLab.decode(ScanLab(firstPicture = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto").firstPicture)
        assertEquals(false, ScanLab(firstPicture = false).diag().toMap()["first_picture"])
        assertEquals(false, ScanLab(firstPicture = false).json().getBoolean("first_picture"))
    }

    @Test
    fun aPageNeedsAFirstPictureOnlyOfWhatItIsNow() {
        // never made, no first picture: needed
        assertTrue(scanNeedsFirst("k2", null, null, null))
        // made from this key, has one from this key, or one from this key failed: not needed
        assertFalse(scanNeedsFirst("k2", "k2", null, null))
        assertFalse(scanNeedsFirst("k2", null, "k2", null))
        assertFalse(scanNeedsFirst("k2", null, null, "k2"))
        // the page changed since: what was made, shown or failed for another key does not count
        assertTrue(scanNeedsFirst("k2", "k1", "k1", "k1"))
    }

    @Test
    fun theTimeToTheFirstPictureIsCountedFromWhenThePageBeganToWait() {
        assertEquals(140L, scanFirstMs(1000L, 1140L))
        // up before the wait was noted, or none at all
        assertEquals(0L, scanFirstMs(1000L, 990L))
        assertEquals(-1L, scanFirstMs(1000L, null))
        assertEquals(-1L, scanFirstMs(null, 1140L))
    }

    @Test
    fun theLogSaysHowTheFirstPictureWasMade() {
        val p = ScanFirstPage(
            width = 453u, height = 640u, rgba = ByteArray(0), turns = 1u, paper = ScanPaper.A4, fromSmall = true,
            timings = listOf(ScanTiming("scan_first_header", 0u), ScanTiming("scan_first_rectify", 14u), ScanTiming("scan_first_look", 82u)),
        )
        val line = scanFirstDiag(2, true, 101, p, turnKnown = false).toMap()
        assertEquals(3, line["index"])
        assertEquals(true, line["made"])
        assertEquals(101L, line["ms"])
        assertEquals(true, line["from_copy"])
        assertEquals(453L, line["width"])
        assertEquals(640L, line["height"])
        assertEquals(1, line["turns"])
        assertEquals(false, line["turn_known"])
        assertEquals(14L, line["first_rectify_ms"])
        assertEquals(82L, line["first_look_ms"])
        // one that failed: no size, no parts
        val failed = scanFirstDiag(0, false, 12, null, turnKnown = true).toMap()
        assertEquals(false, failed["made"])
        assertEquals(false, failed["from_copy"])
        assertEquals(0L, failed["width"])
        assertEquals(true, failed["turn_known"])
        assertFalse(failed.keys.any { it.startsWith("first_") })
    }

    @Test
    fun theBatchsLineCountsItsFirstPictures() {
        val line = scanReadyDiag(12, 25361, true, 2, ScanFirstBatch(12, 1900)).toMap()
        assertEquals(12, line["first_pictures"])
        assertEquals(1900L, line["first_ms"])
        // none made (the option off, or every page was made already)
        val none = scanReadyDiag(12, 25361, true, 2).toMap()
        assertEquals(0, none["first_pictures"])
        assertEquals(-1L, none["first_ms"])
    }

    @Test
    fun theCamerasButtonShowsThePageOnceThereIsOne() {
        assertEquals("made", scanCameraPageThumb(true, "made", "photo"))
        assertEquals("photo", scanCameraPageThumb(true, null, "photo"))
        // the option off: the photo as taken, as before
        assertEquals("photo", scanCameraPageThumb(false, "made", "photo"))
    }

    @Test
    fun aKeptPageSaysWhetherItHadAFirstPicture() {
        val result = ScanTestResult(1000, 1400, "jpeg", 200.0, "a4", "known_focal", false, false, 0.0)
        val record = ScanFirstRecord(made = true, ms = 101, fromCopy = true, width = 453, height = 640, turnKnown = false)
        val json = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 60, true, ScanLab(), "page-01.out.jpg", result, null, firstPicture = record.json()))
        val first = json.getJSONObject("first_picture")
        assertTrue(first.getBoolean("made"))
        assertEquals(101, first.getInt("ms"))
        assertTrue(first.getBoolean("from_copy"))
        assertEquals(453, first.getInt("width"))
        assertEquals(640, first.getInt("height"))
        assertFalse(first.getBoolean("turn_known"))
        assertTrue(json.getJSONObject("options").getBoolean("first_picture"))
        // none asked
        val none = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 60, true, ScanLab(), "page-01.out.jpg", result, null))
        assertTrue(none.isNull("first_picture"))
    }
}
