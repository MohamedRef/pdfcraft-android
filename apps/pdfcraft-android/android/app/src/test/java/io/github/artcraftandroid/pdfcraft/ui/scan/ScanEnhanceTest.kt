package io.github.artcraftandroid.pdfcraft.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Enhance, the look of one page with the page taking the screen (#459). */
class ScanEnhanceTest {
    @Test
    fun arrowsAndSwipesStopAtTheEnds() {
        assertEquals(1, scanEnhancePageAfter(0, 3, 1))
        assertEquals(2, scanEnhancePageAfter(2, 3, 1))
        assertEquals(0, scanEnhancePageAfter(0, 3, -1))
        assertEquals(0, scanEnhancePageAfter(5, 0, 1))
    }

    @Test
    fun aSwipeMustCrossASixthOfThePage() {
        assertEquals(0, scanEnhanceSwipeStep(-50f, 600f, rtl = false))
        // to the left: the next page; to the right: the one before
        assertEquals(1, scanEnhanceSwipeStep(-120f, 600f, rtl = false))
        assertEquals(-1, scanEnhanceSwipeStep(120f, 600f, rtl = false))
        // right to left (Arabic): the other way round
        assertEquals(1, scanEnhanceSwipeStep(120f, 600f, rtl = true))
        assertEquals(0, scanEnhanceSwipeStep(500f, 0f, rtl = false))
    }

    @Test
    fun moreOffersOnlyWhatCanBeDone() {
        assertFalse(scanEnhanceMoreEnabled(ScanEnhanceMore.ApplyAll, pages = 1, applyNeeded = true, lookIsDefault = false, canCompare = true, canRename = true))
        assertTrue(scanEnhanceMoreEnabled(ScanEnhanceMore.ApplyAll, pages = 2, applyNeeded = true, lookIsDefault = false, canCompare = true, canRename = true))
        assertFalse(scanEnhanceMoreEnabled(ScanEnhanceMore.ResetLook, pages = 2, applyNeeded = false, lookIsDefault = true, canCompare = true, canRename = true))
        assertFalse(scanEnhanceMoreEnabled(ScanEnhanceMore.Compare, pages = 2, applyNeeded = false, lookIsDefault = true, canCompare = false, canRename = true))
        assertFalse(scanEnhanceMoreEnabled(ScanEnhanceMore.Rename, pages = 2, applyNeeded = false, lookIsDefault = true, canCompare = false, canRename = false))
        assertTrue(scanEnhanceMoreEnabled(ScanEnhanceMore.AddPage, pages = 0, applyNeeded = false, lookIsDefault = true, canCompare = false, canRename = false))
        assertEquals(5, ScanEnhanceMore.entries.map { it.id }.toSet().size)
    }
}
