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
        // the cut is exactly where the eraser reaches the painted edge (radius 8, half width 1),
        // and nothing inside it is left
        assertEquals(91f, left.points.last().x, 1e-3f)
        assertEquals(109f, right.points.first().x, 1e-3f)
        assertTrue(runs.all { r -> r.points.none { kotlin.math.abs(it.x - 100f) < 9f - 1e-3f } })
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

    @Test fun aLongSegmentIsCutExactlyAtTheEraserEdge() {
        // the engine's the_eraser_cuts_a_long_segment_exactly_at_its_edge, for a draft: one 200 pt
        // segment, half width 1, radius 5. Sampling every 2.5 pt cut it at 192.5 and 207.5.
        val runs = eraseInkRun(listOf(Offset(100f, 400f), Offset(300f, 400f)), listOf(1f, 1f), listOf(1f, 1f), Offset(200f, 450f), Offset(200f, 350f), 5f)!!
        assertEquals(2, runs.size)
        assertEquals(listOf(100f, 194f), runs[0].points.map { it.x }.let { listOf(it.first(), it.last()) }.map { kotlin.math.round(it * 1000f) / 1000f })
        assertEquals(listOf(206f, 300f), runs[1].points.map { it.x }.let { listOf(it.first(), it.last()) }.map { kotlin.math.round(it * 1000f) / 1000f })
        // two points a piece: nothing added along the segment
        assertEquals(listOf(2, 2), runs.map { it.points.size })
    }

    @Test fun aCurvedWideningStrokeIsCutOnItsReach() {
        // an arc whose half width grows from 0.5 to 2 pt; the width factor is the half width here
        val n = 40
        val arc = (0..n).map { k -> val t = Math.PI * k / n; Offset((300 - 100 * kotlin.math.cos(t)).toFloat(), (400 + 60 * kotlin.math.sin(t)).toFloat()) }
        val half = (0..n).map { k -> 0.5f + 1.5f * k / n }
        val a = Offset(300f, 500f)
        val b = Offset(300f, 420f)
        val runs = eraseInkRun(arc, half, half, a, b, 6f)!!
        assertEquals(2, runs.size)
        // each cut end lies on the reach there (radius and half width at the cut) within 0.05 pt
        for ((run, end) in listOf(runs[0] to runs[0].points.lastIndex, runs[1] to 0)) {
            val reach = 6f + run.scales[end]
            assertEquals(reach, distanceToSegmentForTest(run.points[end], a, b), 0.05f)
        }
        // nothing left under the eraser, and the widths still grow along the arc
        assertTrue(runs.all { r -> r.points.indices.none { distanceToSegmentForTest(r.points[it], a, b) < 6f + r.scales[it] - 1e-3f } })
        assertTrue((runs[0].scales + runs[1].scales).zipWithNext().all { (x, y) -> y >= x })
    }

    @Test fun anEraserThatOnlyTouchesACutEndLeavesIt() {
        // the eraser's next step starts where it cut: the piece's end is on its reach, not inside
        val first = eraseInkRun(listOf(Offset(0f, 100f), Offset(200f, 100f)), listOf(1f, 1f), listOf(1f, 1f), Offset(100f, 150f), Offset(100f, 100f), 8f)!!
        val left = first[0]
        assertNull(eraseInkRun(left.points, left.scales, listOf(1f, 1f), Offset(100f, 100f), Offset(100f, 60f), 8f))
    }

    private fun distanceToSegmentForTest(p: Offset, a: Offset, b: Offset) = io.github.artcraftandroid.pdfcraft.ui.viewer.distanceToSegment(p, a, b)

    @Test fun badInputErasesNothing() {
        assertNull(eraseInkRun(line, scales.dropLast(1), half, Offset(100f, 100f), Offset(100f, 100f), 8f))
        assertNull(eraseInkRun(line, scales, half, Offset(100f, 100f), Offset(100f, 100f), Float.NaN))
        assertNull(eraseInkRun(emptyList(), emptyList(), emptyList(), Offset(0f, 0f), Offset(0f, 0f), 8f))
    }
}
