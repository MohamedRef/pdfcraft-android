package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.MAX_SCAN_ENHANCE_PAGES
import io.github.artcraftandroid.pdfcraft.ui.viewer.ScanEnhancePlan
import io.github.artcraftandroid.pdfcraft.ui.viewer.scanEnhancePlan
import io.github.artcraftandroid.pdfcraft.ui.viewer.validScanEnhancePlan
import org.junit.Assert.*
import org.junit.Test

class ScanEnhancePlanTest {
    @Test fun defaultsToOnePageAndPreservesNativePageNumbers() {
        val plan = scanEnhancePlan(300, 20, false, 50, true, false)
        assertEquals(listOf(21), plan?.pages)
        assertEquals(50, plan?.level)
        assertTrue(validScanEnhancePlan(plan!!, 300))
    }
    @Test fun cappedBatchNeverProcessesEntireLongFile() {
        val plan = scanEnhancePlan(10000, 9990, true, 80, true, true)
        assertEquals((9991..10000).toList(), plan?.pages)
        assertTrue(plan!!.pages.size <= MAX_SCAN_ENHANCE_PAGES)
        assertEquals((9..20).toList(), scanEnhancePlan(10000, 8, true, 50, true, false)?.pages)
    }
    @Test fun lastPageAndOutOfBoundsAreSafe() {
        assertEquals(listOf(2), scanEnhancePlan(2, 1, true, 50, true, false)?.pages)
        assertNull(scanEnhancePlan(0, 0, false, 50, true, false))
        assertNull(scanEnhancePlan(10, -1, false, 50, true, false))
        assertNull(scanEnhancePlan(10, 10, true, 50, true, false))
        assertNull(scanEnhancePlan(10, 0, false, 101, true, false))
        assertNull(scanEnhancePlan(10, 0, false, 0, false, false))
    }
    @Test fun malformedOrNoncontiguousNativeScopesAreRejected() {
        assertFalse(validScanEnhancePlan(ScanEnhancePlan(emptyList(), 50, true, false), 20))
        assertFalse(validScanEnhancePlan(ScanEnhancePlan(listOf(1, 3), 50, true, false), 20))
        assertFalse(validScanEnhancePlan(ScanEnhancePlan(listOf(0), 50, true, false), 20))
        assertFalse(validScanEnhancePlan(ScanEnhancePlan(listOf(1, 2, 21), 50, true, false), 20))
        assertFalse(validScanEnhancePlan(ScanEnhancePlan((1..13).toList(), 50, true, false), 20))
        assertFalse(validScanEnhancePlan(ScanEnhancePlan(listOf(2), 0, false, false), 20))
        assertTrue(validScanEnhancePlan(ScanEnhancePlan(listOf(2), 0, false, true), 20))
    }
}
