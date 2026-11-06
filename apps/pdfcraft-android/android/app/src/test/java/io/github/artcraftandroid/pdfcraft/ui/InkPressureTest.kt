package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.MAX_INK_WIDTH_RUNS
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkWidthRuns
import org.junit.Assert.*
import org.junit.Test

class InkPressureTest {
    @Test fun touchAndSnappedStrokesKeepTheirSelectedWidth() {
        val runs = inkWidthRuns(listOf(0.1f, 0.8f, 0.3f), 4f, stylus = false)
        assertEquals(1, runs.size)
        assertEquals(0, runs.first().start)
        assertEquals(2, runs.first().end)
        assertEquals(4f, runs.first().width, 0.0001f)
    }

    @Test fun stylusPressureChangesSurviveAsSeparateOrderedSegments() {
        val pressure = listOf(0.12f, 0.12f, 0.12f, 1f, 1f, 1f)
        val runs = inkWidthRuns(pressure, 6f, stylus = true)
        assertTrue(runs.size >= 2)
        assertTrue(runs.first().width < runs.last().width)
        assertEquals(0, runs.first().start)
        assertEquals(pressure.lastIndex, runs.last().end)
        for (i in 1 until runs.size) {
            assertEquals("Neighboring PDF segments must share an endpoint", runs[i - 1].end, runs[i].start)
        }
        assertTrue(runs.all { it.end > it.start && it.width > 0f && it.width <= 6f })
    }

    @Test fun neutralMissingPenPressureNeverProducesNaNOrZeroWidth() {
        val pressure = listOf(Float.NaN, 0f, Float.POSITIVE_INFINITY, -4f, 1f)
        val runs = inkWidthRuns(pressure, 3f, stylus = true)
        assertEquals(1, runs.size)
        assertEquals(3f, runs.single().width, 0.0001f)
        assertEquals(1f, inkWidthRuns(listOf(1f, 1f), Float.NaN, true).single().width, 0.0001f)
    }

    @Test fun shortAndConstantStrokesDoNotCreateExtraAnnotations() {
        assertTrue(inkWidthRuns(emptyList(), 2f, true).isEmpty())
        assertTrue(inkWidthRuns(listOf(1f), 2f, true).isEmpty())
        assertEquals(1, inkWidthRuns(List(1024) { 1f }, 2f, true).size)
    }

    @Test fun longJitteryStylusInputStaysWithinAnnotationLimit() {
        val pressure = List(4097) { if (it / 4 % 2 == 0) 0.15f else 0.95f }
        val runs = inkWidthRuns(pressure, 5f, stylus = true)
        assertTrue(runs.size in 2..MAX_INK_WIDTH_RUNS)
        assertEquals(0, runs.first().start)
        assertEquals(pressure.lastIndex, runs.last().end)
        runs.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
        assertTrue(runs.all { it.start < it.end && it.width.isFinite() && it.width > 0f })
    }
}
