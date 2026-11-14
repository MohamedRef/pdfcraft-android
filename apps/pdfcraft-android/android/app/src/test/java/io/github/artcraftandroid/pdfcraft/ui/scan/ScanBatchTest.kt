package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.scanTestPageJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Borders and looks for several pages in one pass (#515). */
class ScanBatchTest {
    private val quad = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    @Test
    fun thePassGoesOverTheChosenPagesInTheScansOrder() {
        val keys = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("b", "d", "e"), scanBatchKeys(setOf("e", "b", "d"), keys))
        // pages whose markup was kept are left out; a key no longer in the scan is not there
        assertEquals(listOf("b", "e"), scanBatchKeys(setOf("e", "b", "d", "gone"), keys, left = setOf("d")))
    }

    @Test
    fun nextAndPreviousMoveAndThePassEndsPastTheLastPageOrWithTheTick() {
        val pass = ScanBatchPass(ScanBatchWhat.Borders, listOf("a", "b", "c"), 0, 0L)
        assertEquals(1, scanBatchStep(pass, 1)!!.at)
        // before the first it stays on the first
        assertEquals(0, scanBatchStep(pass, -1)!!.at)
        assertNull(scanBatchStep(pass.copy(at = 2), 1))
        assertNull(scanBatchStep(pass.copy(at = 1), 0))
        assertEquals("b", pass.copy(at = 1).key)
    }

    @Test
    fun aSwipeSidewaysGoesToTheNextPageInTheReadingDirection() {
        val min = 64f
        assertEquals(1, scanBatchSwipe(-200f, 10f, 0, min, rtl = false))
        assertEquals(-1, scanBatchSwipe(200f, 10f, 0, min, rtl = false))
        // in Arabic the next page is to the left: a swipe to the right
        assertEquals(1, scanBatchSwipe(200f, 10f, 0, min, rtl = true))
        // too short, or more up and down than sideways
        assertEquals(0, scanBatchSwipe(-40f, 0f, 0, min, rtl = false))
        assertEquals(0, scanBatchSwipe(-100f, 120f, 0, min, rtl = false))
        // a photo shown a quarter turn clockwise: its own "up" is the screen's right
        assertEquals(-1, scanBatchSwipe(0f, -200f, 1, min, rtl = false))
        assertEquals(1, scanBatchSwipe(200f, 0f, 2, min, rtl = false))
    }

    @Test
    fun anImportOfSeveralPhotosOffersThePass() {
        assertTrue(scanBatchOffers(true, true, 11))
        assertFalse(scanBatchOffers(true, true, 1))
        assertFalse(scanBatchOffers(false, true, 11))
        assertFalse(scanBatchOffers(true, false, 11))
    }

    @Test
    fun theLogSaysHowManyPagesWereShownAndChangedAndTheTimeForEach() {
        val open = scanBatchOpenDiag(ScanBatchWhat.Look, 3, "selection", 0).toMap()
        assertEquals("look", open["what"])
        assertEquals(3, open["pages"])
        val pass = ScanBatchPass(ScanBatchWhat.Borders, List(11) { "p$it" }, 10, 0L, shown = List(11) { "p$it" }.toSet(), changed = setOf("p3"))
        val done = scanBatchDoneDiag(pass, 22_000L, "done").toMap()
        assertEquals(11, done["pages"])
        assertEquals(11, done["shown"])
        assertEquals(1, done["changed"])
        assertEquals(2_000L, done["per_page_ms"])
        assertEquals(0L, scanBatchDoneDiag(pass.copy(shown = emptySet()), 10L, "back").toMap()["per_page_ms"])
    }

    @Test
    fun theKeptPageSaysItsBordersWereCheckedAndInAPass() {
        val inPass = ScanCropStats(visits = 1, batchVisits = 1)
        val json = JSONObject(scanTestPageJson("t", true, quad, quad, null, "auto", 60, false, ScanLab(), null, null, crop = inPass))
        assertTrue(json.getBoolean("borders_checked"))
        assertTrue(json.getBoolean("batch"))
        val never = JSONObject(scanTestPageJson("t", true, quad, quad, null, "auto", 60, false, ScanLab(), null, null))
        assertFalse(never.getBoolean("borders_checked"))
        assertFalse(never.getBoolean("batch"))
        assertEquals(2, (inPass + ScanCropStats(visits = 1, batchVisits = 1)).batchVisits)
        assertEquals(1, inPass.diag().toMap()["batch_visits"])
    }

    @Test
    fun theTestingOptionIsOnAndRemembered() {
        assertTrue(ScanLab().batchBorders)
        val off = ScanLab(batchBorders = false)
        assertFalse(ScanLab.decode(off.encode()).batchBorders)
        assertFalse(off.asDesigned)
        assertEquals(false, off.diag().toMap()["batch_borders"])
        assertEquals(false, off.json().getBoolean("batch_borders"))
        assertTrue(ScanLab.decode("keep=0").batchBorders)
    }
}
