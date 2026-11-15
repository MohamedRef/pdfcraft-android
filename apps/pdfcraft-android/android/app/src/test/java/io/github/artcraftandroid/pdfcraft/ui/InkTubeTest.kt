package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.tubeCentreline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkTubeTest {
    @Test fun aStraightOutlineGivesItsMiddleAndItsWidth() {
        // a 10 pt wide band from x 0 to 100 around y 100; the pen's path runs 2 pt off its middle
        val outline = listOf(Offset(0f, 95f), Offset(100f, 95f), Offset(100f, 105f), Offset(0f, 105f))
        val path = List(11) { Offset(it * 10f, 98f) }
        val (pts, scales) = tubeCentreline(path, listOf(outline), base = 5f)!!
        assertEquals(path.size, pts.size)
        assertTrue(pts.all { kotlin.math.abs(it.y - 100f) < 1e-3f })
        assertTrue(scales.all { kotlin.math.abs(it - 2f) < 1e-3f })
    }

    @Test fun aWideningOutlineGivesWideningWidths() {
        // a wedge: 2 pt wide at x 0, 12 pt wide at x 100
        val outline = listOf(Offset(0f, 99f), Offset(100f, 94f), Offset(100f, 106f), Offset(0f, 101f))
        val path = List(9) { Offset(10f + it * 10f, 100f) }
        val (_, scales) = tubeCentreline(path, listOf(outline), base = 5f)!!
        assertTrue(scales.zipWithNext().all { (a, b) -> b > a })
        assertEquals(3f / 5f, scales[0], 0.01f)
        assertEquals(11f / 5f, scales.last(), 0.01f)
    }

    @Test fun aPointOutsideTheOutlineTakesTheThinnestWidth() {
        val outline = listOf(Offset(0f, 95f), Offset(50f, 95f), Offset(50f, 105f), Offset(0f, 105f))
        val path = listOf(Offset(10f, 100f), Offset(30f, 100f), Offset(80f, 100f))
        val (pts, scales) = tubeCentreline(path, listOf(outline), base = 10f)!!
        assertEquals(Offset(80f, 100f), pts[2])
        assertTrue(scales.all { kotlin.math.abs(it - 1f) < 1e-3f })
    }

    @Test fun nothingToMeasureGivesNothing() {
        assertNull(tubeCentreline(emptyList(), listOf(listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(0f, 1f))), 1f))
        assertNull(tubeCentreline(listOf(Offset(0f, 0f)), emptyList(), 1f))
        assertNull(tubeCentreline(listOf(Offset(0f, 0f)), listOf(listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(0f, 1f))), 0f))
    }
}
