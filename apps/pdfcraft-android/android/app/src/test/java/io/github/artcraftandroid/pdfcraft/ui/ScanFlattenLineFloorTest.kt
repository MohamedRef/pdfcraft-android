package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.core.ScanFlattenCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The flattening guard's floor on lines followed (#302): the testing option, the request and the log. */
class ScanFlattenLineFloorTest {
    private val corners = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        val lab = ScanLab()
        assertTrue(lab.flattenLineFloor)
        assertFalse(lab.copy(flattenLineFloor = false).asDesigned)
        assertEquals(ScanLab(flattenLineFloor = false), ScanLab.decode(ScanLab(flattenLineFloor = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto").flattenLineFloor)
        assertEquals(false, ScanLab(flattenLineFloor = false).diag().toMap()["flatten_line_floor"])
        assertEquals(false, ScanLab(flattenLineFloor = false).json().getBoolean("flatten_line_floor"))
        // automatic flattening itself stays off by default
        assertFalse(lab.flattenAuto)
    }

    @Test
    fun theRequestAsksForEightLinesWithTheOptionAndNoneWithout() {
        assertEquals(8, scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(flattenAuto = true), "").flattenMinLines.toInt())
        assertEquals(0, scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(flattenAuto = true, flattenLineFloor = false), "").flattenMinLines.toInt())
        val page = ScanPreviewKey("/a", corners.toList(), ScanFilter.CleanScan, false, ScanLab())
        assertFalse(page == page.copy(lab = ScanLab(flattenLineFloor = false)))
    }

    @Test
    fun theLogSaysWhenTooFewLinesStoppedAFlattenedPage() {
        val diag = scanFlattenCheckDiag(ScanFlattenCheck(false, "fewlines", 19.1f, 3.2f, 3u, 3u)).toMap()
        assertEquals("fewlines", diag["flatten_why"])
        assertEquals(false, diag["flatten_kept"])
        assertEquals(3L, diag["straight_lines"])
    }
}
