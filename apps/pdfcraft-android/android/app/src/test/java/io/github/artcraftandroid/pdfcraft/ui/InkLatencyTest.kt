package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.LatencyStats
import io.github.artcraftandroid.pdfcraft.ui.viewer.LatencySummary
import io.github.artcraftandroid.pdfcraft.ui.viewer.PendingInput
import io.github.artcraftandroid.pdfcraft.ui.viewer.latencySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InkLatencyTest {
    @Test
    fun summaryUsesNearestRankPercentiles() {
        assertNull(latencySummary(emptyList()))
        assertEquals(LatencySummary(1, 7, 7), latencySummary(listOf(7)))
        // 1..100: median 50, 95th percentile 95
        assertEquals(LatencySummary(100, 50, 95), latencySummary((1L..100L).shuffled(java.util.Random(4))))
    }

    @Test
    fun statsKeepTheNewestSamplesAndDropImpossibleOnes() {
        val s = LatencyStats(capacity = 3)
        listOf(-1L, 5L, 2000L, 10L, 20L, 30L).forEach(s::add)
        assertEquals(LatencySummary(3, 20, 30), s.summary())
        s.clear()
        assertNull(s.summary())
    }

    @Test
    fun aPenSampleIsCountedOnceByTheFirstDrawThatShowsIt() {
        val stats = LatencyStats()
        val p = PendingInput()
        p.drawn(100, stats)          // nothing pending
        p.at = 90
        p.drawn(104, stats)
        p.drawn(120, stats)          // a redraw without new input
        assertEquals(LatencySummary(1, 14, 14), stats.summary())
    }
}
