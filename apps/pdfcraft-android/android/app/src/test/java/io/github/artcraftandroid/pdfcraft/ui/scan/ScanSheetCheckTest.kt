package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.core.ScanCornerPick
import io.github.artcraftandroid.pdfcraft.core.ScanFindOptions
import io.github.artcraftandroid.pdfcraft.core.ScanFinder
import io.github.artcraftandroid.pdfcraft.ui.ScanFinding
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.scanCornerPickId
import io.github.artcraftandroid.pdfcraft.ui.scanTestPageJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sheet check: corners of different sheets of a pile are not taken for one page. */
class ScanSheetCheckTest {
    private val quad = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    @Test
    fun theSearchAsksForItAsDesigned() {
        assertTrue(ScanLab().sheetCheck)
        assertEquals(ScanFindOptions(ScanFinder.AUTO, true, true, true), ScanLab().findOptions())
        // a search that does not name it has it on
        assertTrue(ScanFindOptions(ScanFinder.AUTO, true).sheetCheck)
        // off: each corner's surest place whatever shape the four make, as before
        assertFalse(ScanLab(sheetCheck = false).findOptions().sheetCheck)
    }

    @Test
    fun theTestingOptionIsRemembered() {
        val off = ScanLab(sheetCheck = false)
        assertFalse(ScanLab.decode(off.encode()).sheetCheck)
        assertFalse(off.asDesigned)
        assertEquals(false, off.diag().toMap()["sheet_check"])
        assertEquals(false, off.json().getBoolean("sheet_check"))
        // a phone that saved its options before the check has it on
        assertTrue(ScanLab.decode("keep=1;finder=auto;refine=1;second_look=1").sheetCheck)
    }

    @Test
    fun theLogSaysWhichCornersWereTaken() {
        assertEquals("none", scanCornerPickId(ScanCornerPick.NONE))
        assertEquals("first", scanCornerPickId(ScanCornerPick.FIRST))
        assertEquals("other", scanCornerPickId(ScanCornerPick.OTHER))
        // the line detector's page refused for its shape
        assertEquals("refused", io.github.artcraftandroid.pdfcraft.ui.scanSecondLookId(io.github.artcraftandroid.pdfcraft.core.ScanSecondLook.REFUSED))
    }

    @Test
    fun theKeptPageSaysItToo() {
        val finding = ScanFinding("model", 0.55, 60, 7, 4, 0, false, ScanLab().findOptions(), cornerPick = "first", shapesRefused = 1, skew = 0.24)
        val json = JSONObject(scanTestPageJson("t", true, quad, quad, finding, "auto", 60, false, ScanLab(), null, null)).getJSONObject("finding")
        assertEquals("first", json.getString("corner_pick"))
        assertEquals(1, json.getInt("shapes_refused"))
        assertEquals(0.2, json.getDouble("skew"), 1e-9)
        // a finding made before the check says nothing was refused
        val before = ScanFinding("model", 0.9, 0, 1, 4, 0)
        assertEquals("none", before.cornerPick)
        assertEquals(0, before.shapesRefused)
    }
}
