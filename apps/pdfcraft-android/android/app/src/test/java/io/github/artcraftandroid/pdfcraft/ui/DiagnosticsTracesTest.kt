package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.StrokeTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTracesTest {
    @Test
    fun strokeTraceSummarisesOneStroke() {
        val t = StrokeTrace()
        t.down(time = 1_000, eraser = false, active = true)
        repeat(10) { t.move(count = 4) }
        listOf(8L, 10L, 12L, 30L).forEach { t.latency(front = false, ms = it) }
        listOf(3L, 4L).forEach { t.latency(front = true, ms = it) }
        val s = t.up(time = 1_200, cancelled = false)!!
        assertEquals(200, s.durationMs)
        assertEquals(10, s.events)
        assertEquals(40, s.samples)
        assertEquals(200, s.sampleHz)
        assertEquals(4, s.normal!!.count)
        assertEquals(10, s.normal!!.medianMs)
        assertEquals(30, s.normalMaxMs)
        assertEquals(2, s.front!!.count)
        assertEquals(4, s.frontMaxMs)
        // the pen lifted: a second up is not a stroke
        assertNull(t.up(time = 1_300, cancelled = false))
    }

    @Test
    fun strokeTraceCountsFramesDrawTimeAndRecompositions() {
        val t = StrokeTrace()
        t.down(time = 0, eraser = false, active = true, pointer = "finger")
        // draws 8, 8 and 25 ms apart: one dropped frame
        listOf(0L, 8L, 16L, 41L).forEachIndexed { i, ms -> t.drawn(nowNs = 1_000_000_000L + ms * 1_000_000, tookUs = 100L + i * 100) }
        repeat(3) { t.recomposed() }
        val s = t.up(time = 50, cancelled = false)!!
        assertEquals("finger", s.pointer)
        assertEquals(4, s.draws)
        assertEquals(8, s.frameGap!!.medianMs)
        assertEquals(25, s.frameGapMaxMs)
        assertEquals(400, s.drawMaxUs)
        assertEquals(3, s.recompositions)
        // an idle trace ignores draws
        t.drawn(nowNs = 2_000_000_000L, tookUs = 5)
        t.recomposed()
        t.down(time = 0, eraser = true, active = true)
        assertEquals("eraser", t.up(time = 10, cancelled = false)!!.pointer)
    }

    @Test
    fun strokeTraceIsIdleWhileTheLogIsOff() {
        val t = StrokeTrace()
        t.down(time = 0, eraser = true, active = false)
        t.move(5)
        t.latency(front = false, ms = 9)
        assertNull(t.up(time = 100, cancelled = true))
    }

    @Test
    fun scanStatsTellUntouchedFromMovedCorners() {
        val found = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
        val untouched = scanPageStats(found.copyOf(), found, ScanPage.WHOLE, 3000.0, 4000.0)
        assertTrue(untouched.found)
        assertFalse(untouched.touched)
        assertEquals(0.0, untouched.maxMovePct, 0.0)

        // one corner moved 50 px right on a 3000 × 4000 photo (diagonal 5000 px): 1 %
        val moved = found.copyOf().also { it[0] = 0.1f + 50f / 3000f }
        val s = scanPageStats(moved, found, ScanPage.WHOLE, 3000.0, 4000.0)
        assertTrue(s.touched)
        assertEquals(1.0, s.maxMovePct, 0.01)

        // nothing found: the whole photo, untouched unless the user cropped it
        assertFalse(scanPageStats(ScanPage.WHOLE.copyOf(), null, ScanPage.WHOLE, 3000.0, 4000.0).touched)
        val cropped = scanPageStats(found.copyOf(), null, ScanPage.WHOLE, 3000.0, 4000.0)
        assertFalse(cropped.found)
        assertTrue(cropped.touched)
        assertEquals(0.0, cropped.maxMovePct, 0.0)
    }
}
