package io.github.artcraftandroid.pdfcraft.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The background queue makes two pages at once (#549): the testing option, the lanes and the `scan_ready` line. */
class ScanParallelPagesTest {
    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        assertTrue(ScanLab().parallelPages)
        assertFalse(ScanLab(parallelPages = false).asDesigned)
        assertEquals(ScanLab(parallelPages = false), ScanLab.decode(ScanLab(parallelPages = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto").parallelPages)
        assertEquals(false, ScanLab(parallelPages = false).diag().toMap()["parallel_pages"])
        assertEquals(false, ScanLab(parallelPages = false).json().getBoolean("parallel_pages"))
    }

    @Test
    fun twoLanesWithTheOptionOnAndOneWithout() {
        assertEquals(2, scanQueueLanes(true))
        assertEquals(1, scanQueueLanes(false))
    }

    @Test
    fun theReadyLineSaysWhetherPagesWereMadeTwoAtOnce() {
        val line = scanReadyDiag(9, 4200, true, 2).toMap()
        assertEquals(9, line["pages"])
        assertEquals(4200L, line["ms"])
        assertEquals(true, line["parallel"])
        assertEquals(2, line["most_at_once"])
        assertEquals(false, scanReadyDiag(1, 900, false, 1).toMap()["parallel"])
    }
}
