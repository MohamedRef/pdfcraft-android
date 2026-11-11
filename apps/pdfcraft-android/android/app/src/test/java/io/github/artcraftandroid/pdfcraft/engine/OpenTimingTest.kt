package io.github.artcraftandroid.pdfcraft.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class OpenTimingTest {
    @Test
    fun milestoneLineHasOnlyStageTimeAndCounts() {
        assertEquals("native_ready elapsed_ms=1200 bytes=314572800 pages=869", timingLine("native_ready", 1200, 314_572_800, 869))
        assertEquals("first_page_requested elapsed_ms=0", timingLine("first_page_requested", -5))
    }

    @Test
    fun openStagesGoToOpenAndRenderStagesToRender() {
        val at = LocalDateTime.of(2026, 11, 11, 21, 3, 12, 481_000_000)
        assertEquals("11-11 21:03:12.481 [open] native_open duration_ms=40", openTimingLine(at, "native_open duration_ms=40"))
        assertEquals("11-11 21:03:12.481 [render] page_render duration_ms=60", openTimingLine(at, "page_render duration_ms=60"))
        assertEquals("11-11 21:03:12.481 [render] tile_render_prefetch duration_ms=9", openTimingLine(at, "tile_render_prefetch duration_ms=9"))
        assertEquals("11-11 21:03:12.481 [render] stale_tile_dropped count=1", openTimingLine(at, "stale_tile_dropped count=1"))
    }
}
