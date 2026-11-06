package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.MAX_STYLUS_HISTORY_POINTS
import io.github.artcraftandroid.pdfcraft.ui.viewer.StylusHistoryPoint
import io.github.artcraftandroid.pdfcraft.ui.viewer.stylusMotionPoints
import org.junit.Assert.*
import org.junit.Test

class StylusHistoryTest {
    @Test fun intermediatePenMovesStayInEventTimeOrderWithCurrentEndpoint() {
        val points = stylusMotionPoints(
            Offset.Zero, Offset(4f, 0f), 0L, 40L, 0.2f, 1f,
            listOf(
                StylusHistoryPoint(30L, Offset(3f, 0f)),
                StylusHistoryPoint(10L, Offset(1f, 0f)),
                StylusHistoryPoint(20L, Offset(2f, 0f)),
            ),
        )
        assertEquals(listOf(10L, 20L, 30L, 40L), points.map { it.uptimeMillis })
        assertEquals(listOf(1f, 2f, 3f, 4f), points.map { it.position.x })
        assertEquals(0.4f, points.first().pressure, 0.0001f)
        assertEquals(1f, points.last().pressure, 0.0001f)
    }

    @Test fun duplicateInvalidAndOutOfWindowHistoryNeverReordersStroke() {
        val points = stylusMotionPoints(
            Offset.Zero, Offset(4f, 4f), 100L, 200L, 0.5f, Float.NaN,
            listOf(
                StylusHistoryPoint(99L, Offset(1f, 1f)),
                StylusHistoryPoint(110L, Offset(1f, 1f)),
                StylusHistoryPoint(110L, Offset(2f, 2f)),
                StylusHistoryPoint(150L, Offset(Float.NaN, 3f)),
                StylusHistoryPoint(180L, Offset(1f, 1f)),
                StylusHistoryPoint(201L, Offset(2f, 2f)),
            ),
        )
        assertEquals(2, points.size)
        assertEquals(110L, points[0].uptimeMillis)
        assertEquals(200L, points[1].uptimeMillis)
        assertTrue(points.all { it.pressure.isFinite() && it.pressure > 0f })
    }

    @Test fun oversizedHistoryIsBoundedWhileKeepingTheLastLivePoint() {
        val history = (1L..9999L).map { StylusHistoryPoint(it, Offset(it.toFloat(), 1f)) }
        val points = stylusMotionPoints(
            Offset.Zero, Offset(10000f, 1f), 0L, 10000L, 0.3f, 0.8f, history,
        )
        assertTrue(points.size <= MAX_STYLUS_HISTORY_POINTS + 1)
        assertEquals(10000L, points.last().uptimeMillis)
        assertEquals(10000f, points.last().position.x)
        assertTrue(points.zipWithNext().all { (a, b) -> a.uptimeMillis < b.uptimeMillis })
    }

    @Test fun emptyOrNoMovementNeverInventsAnInkStroke() {
        assertTrue(stylusMotionPoints(
            Offset.Zero, Offset.Zero, 0L, 1L, 1f, 1f, emptyList(),
        ).isEmpty())
        assertTrue(stylusMotionPoints(
            Offset.Zero, Offset(Float.NaN, 0f), 0L, 1L, 1f, 1f,
            listOf(StylusHistoryPoint(0L, Offset(1f, 1f))),
        ).isEmpty())
        assertEquals(1, stylusMotionPoints(
            Offset.Zero, Offset(3f, 4f), 10L, 10L, 1f, 1f, emptyList(),
        ).size)
    }
}
