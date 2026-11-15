package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.eraseInkRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkDraftEraseTest {
    private val line = listOf(Offset(0f, 100f), Offset(100f, 100f), Offset(200f, 100f))
    private val scales = listOf(1f, 1.5f, 1f)
    private val half = listOf(1f, 1f, 1f)

    @Test fun anEraserThatMissesLeavesTheDraftAlone() {
        assertNull(eraseInkRun(line, scales, half, Offset(100f, 150f), Offset(150f, 150f), 8f))
        // just past the painted edge: radius 8 plus half the width 1
        assertNull(eraseInkRun(line, scales, half, Offset(100f, 109.5f), Offset(100f, 120f), 8f))
    }

    @Test fun anEraserAcrossTheMiddleCutsTheDraftInTwo() {
        val runs = eraseInkRun(line, scales, half, Offset(100f, 50f), Offset(100f, 150f), 8f)!!
        assertEquals(2, runs.size)
        val (left, right) = runs
        // the cut is where the eraser reaches the painted edge, and nothing inside it is left
        assertTrue(left.points.last().x <= 91f && left.points.last().x > 85f)
        assertTrue(right.points.first().x >= 109f && right.points.first().x < 115f)
        assertTrue(runs.all { r -> r.points.none { kotlin.math.abs(it.x - 100f) <= 9f } })
        // the ends keep their own points, and each point keeps its width
        assertEquals(Offset(0f, 100f), left.points.first())
        assertEquals(Offset(200f, 100f), right.points.last())
        assertTrue(runs.all { it.points.size == it.scales.size })
        assertTrue(left.scales.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun anEraserTouchingTheEdgeOfAThickStrokeCutsIt() {
        // a thick stroke (half width 6) is reached 13 pt from its centre line with radius 8
        val thick = listOf(6f, 6f, 6f)
        assertTrue(eraseInkRun(line, scales, thick, Offset(100f, 113f), Offset(100f, 130f), 8f) != null)
    }

    @Test fun anEraserOverTheWholeDraftLeavesNothing() {
        val runs = eraseInkRun(line, scales, half, Offset(-20f, 100f), Offset(220f, 100f), 8f)!!
        assertTrue(runs.isEmpty())
        // a dot is erased by a tap on it
        assertTrue(eraseInkRun(listOf(Offset(5f, 5f)), listOf(1f), listOf(1f), Offset(6f, 6f), Offset(6f, 6f), 4f)!!.isEmpty())
    }

    @Test fun aFastEraserJumpingOverAStrokeStillCutsIt() {
        // two samples far apart on either side of the line, with the line between them
        val runs = eraseInkRun(line, scales, half, Offset(50f, 0f), Offset(50f, 200f), 4f)!!
        assertEquals(2, runs.size)
    }

    @Test fun badInputErasesNothing() {
        assertNull(eraseInkRun(line, scales.dropLast(1), half, Offset(100f, 100f), Offset(100f, 100f), 8f))
        assertNull(eraseInkRun(line, scales, half, Offset(100f, 100f), Offset(100f, 100f), Float.NaN))
        assertNull(eraseInkRun(emptyList(), emptyList(), emptyList(), Offset(0f, 0f), Offset(0f, 0f), 8f))
    }
}
