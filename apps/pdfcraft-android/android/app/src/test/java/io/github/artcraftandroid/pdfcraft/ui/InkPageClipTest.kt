package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkRun
import io.github.artcraftandroid.pdfcraft.ui.viewer.clipInkToPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkPageClipTest {
    private val w = 600f
    private val h = 800f

    @Test fun strokeOnThePageIsUnchanged() {
        val points = listOf(Offset(10f, 10f), Offset(0f, 400f), Offset(600f, 800f))
        val pressures = listOf(0.2f, 0.5f, 0.9f)
        assertEquals(listOf(InkRun(points, pressures)), clipInkToPage(points, pressures, w, h))
    }

    @Test fun strokeThatLeavesAndComesBackIsCutAtTheEdgeWithNoChord() {
        // From inside, 2 cm (57 pt) past the right edge, then back in.
        val points = listOf(Offset(500f, 100f), Offset(580f, 100f), Offset(657f, 100f), Offset(657f, 140f), Offset(580f, 140f), Offset(500f, 140f))
        val runs = clipInkToPage(points, List(points.size) { 1f }, w, h)
        assertEquals(2, runs.size)
        assertEquals(listOf(Offset(500f, 100f), Offset(580f, 100f), Offset(600f, 100f)), runs[0].points)
        assertEquals(listOf(Offset(600f, 140f), Offset(580f, 140f), Offset(500f, 140f)), runs[1].points)
        // Nothing runs along the edge: no point between the exit and the re-entry is kept.
        assertTrue(runs.flatMap { it.points }.none { it.x == 600f && it.y > 100f && it.y < 140f })
    }

    @Test fun edgePointPressureIsInterpolated() {
        val runs = clipInkToPage(listOf(Offset(580f, 50f), Offset(620f, 50f)), listOf(0.2f, 0.6f), w, h)
        assertEquals(1, runs.size)
        assertEquals(0.4f, runs[0].pressures.last(), 1e-5f)
        assertEquals(Offset(600f, 50f), runs[0].points.last())
    }

    @Test fun strokeEntirelyOffThePageIsDropped() {
        assertTrue(clipInkToPage(listOf(Offset(-10f, 5f), Offset(-40f, 50f)), listOf(1f, 1f), w, h).isEmpty())
    }

    @Test fun fastStrokeCrossingThePageBetweenTwoOutsideSamplesKeepsTheCrossing() {
        val runs = clipInkToPage(listOf(Offset(-50f, 400f), Offset(650f, 400f)), listOf(1f, 1f), w, h)
        assertEquals(1, runs.size)
        assertEquals(listOf(Offset(0f, 400f), Offset(600f, 400f)), runs[0].points)
    }
}
