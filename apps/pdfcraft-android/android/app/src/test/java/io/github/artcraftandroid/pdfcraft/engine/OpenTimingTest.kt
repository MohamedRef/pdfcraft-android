package io.github.artcraftandroid.pdfcraft.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenTimingTest {
    @Test
    fun recorderKeepsTheNewestLinesAndCountsDropped() {
        val r = TimingRecorder(capacity = 3)
        (1..5).forEach { r.add("l$it") }
        assertEquals(listOf("l3", "l4", "l5"), r.snapshot())
        assertEquals(2, r.droppedCount())
        r.clear()
        assertEquals(emptyList<String>(), r.snapshot())
        assertEquals(0, r.droppedCount())
    }

    @Test
    fun milestoneLineHasOnlyStageTimeAndCounts() {
        assertEquals("native_ready elapsed_ms=1200 bytes=314572800 pages=869", timingLine("native_ready", 1200, 314_572_800, 869))
        assertEquals("first_page_requested elapsed_ms=0", timingLine("first_page_requested", -5))
    }

    @Test
    fun reportHasHeaderThenLinesInOrder() {
        val text = timingReport(listOf("app" to "0.0.17-beta (17012)"), listOf("a", "b"), dropped = 4)
        val lines = text.lines()
        assertEquals("PdfCraft open timings", lines[0])
        assertEquals("app: 0.0.17-beta (17012)", lines[1])
        assertTrue("older lines dropped: 4" in lines)
        assertTrue(lines.indexOf("a") < lines.indexOf("b"))
    }
}
