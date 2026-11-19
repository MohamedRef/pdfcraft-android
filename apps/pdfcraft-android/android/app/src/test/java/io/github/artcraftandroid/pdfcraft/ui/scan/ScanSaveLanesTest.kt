package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Save makes the pages not made ahead two at once (#549). */
class ScanSaveLanesTest {
    @Test
    fun twoLanesWithTheOptionAndTwoPagesToMake() {
        // the owner's log of 2026-11-19: 7 of 12 pages were made at Save, one after another, in 19.9 s
        assertEquals(2, scanSaveLanes(parallel = true, toMake = 7))
        assertEquals(2, scanSaveLanes(parallel = true, toMake = 2))
        // one page, or none, needs no second lane
        assertEquals(1, scanSaveLanes(parallel = true, toMake = 1))
        assertEquals(1, scanSaveLanes(parallel = true, toMake = 0))
        // off: one after another, as before
        assertEquals(1, scanSaveLanes(parallel = false, toMake = 7))
    }

    @Test
    fun theSavedLineSaysHowManyAtOnce() {
        val line = scanSaveLanesDiag(lanes = 2, mostAtOnce = 2).toMap()
        assertEquals(2, line["save_lanes"])
        assertEquals(2, line["save_most_at_once"])
        assertEquals(0, scanSaveLanesDiag(2, 0).toMap()["save_most_at_once"])
    }

    @Test
    fun theTestingOptionIsOnAndRemembered() {
        assertTrue(ScanLab().parallelSave)
        val off = ScanLab(parallelSave = false)
        assertFalse(ScanLab.decode(off.encode()).parallelSave)
        assertFalse(off.asDesigned)
        assertEquals(false, off.diag().toMap()["parallel_save"])
        assertEquals(false, off.json().getBoolean("parallel_save"))
        // a phone that saved its options before this has it on
        assertTrue(ScanLab.decode("keep=0;parallel_pages=1").parallelSave)
    }
}
