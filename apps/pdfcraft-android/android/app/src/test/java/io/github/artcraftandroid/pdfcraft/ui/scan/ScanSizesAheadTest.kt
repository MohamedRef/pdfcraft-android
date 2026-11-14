package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every size stored from one making (#528): the sheet's whole-scan sizes, the option, the log. */
class ScanSizesAheadTest {
    private fun sizes(vararg bytes: Long): Map<String, Long> = ScanLab.SIZES.zip(bytes.toList()).toMap()

    @Test
    fun theSheetAddsEveryPageAtEachSize() {
        val totals = scanSizeTotals(listOf(sizes(400, 300, 200, 100), sizes(40, 30, 20, 10)))!!
        assertEquals(ScanLab.SIZES.zip(listOf(440L, 330L, 220L, 110L)).toMap(), totals)
    }

    @Test
    fun aPageNotStoredAtEverySizeLeavesTheEstimate() {
        assertNull(scanSizeTotals(emptyList()))
        assertNull(scanSizeTotals(listOf(sizes(4, 3, 2, 1), null)))
        assertNull(scanSizeTotals(listOf(mapOf(ScanLab.SIZES.first() to 4L))))
    }

    @Test
    fun theOptionAndItsLog() {
        assertTrue(ScanLab().sizesAhead)
        val off = ScanLab(sizesAhead = false)
        assertFalse(off.asDesigned)
        assertEquals(off, ScanLab.decode(off.encode()))
        val m = scanSizesAheadDiag(true, 3, 2, 120).toMap()
        assertEquals(true, m["sizes_ahead"])
        assertEquals(3, m["pages_made_ahead"])
        assertEquals(2, m["pages_stored_ahead"])
        assertEquals(120L, m["make_ms"])
    }
}
