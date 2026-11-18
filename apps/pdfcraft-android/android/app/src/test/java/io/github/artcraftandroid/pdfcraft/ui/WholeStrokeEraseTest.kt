package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.ErasableInkStroke
import io.github.artcraftandroid.pdfcraft.ui.viewer.WholeStrokeHits
import io.github.artcraftandroid.pdfcraft.ui.viewer.appendEraserPoint
import io.github.artcraftandroid.pdfcraft.ui.viewer.erasableInkStroke
import io.github.artcraftandroid.pdfcraft.ui.viewer.eraserTouchesStroke
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkEraseCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole-stroke eraser hides each saved stroke it touches while it is still down (#655). */
class WholeStrokeEraseTest {
    /** A saved stroke through [xy] (page points), [half] its half width at each point. */
    private fun line(index: Int, vararg xy: Float, half: Float = 1f) =
        ErasableInkStroke(index, 0, xy.toList().chunked(2).map { Offset(it[0], it[1]) }, List(xy.size / 2) { half })

    @Test
    fun aStrokeIsTouchedWhereTheEraserReachesItsPaintedEdge() {
        // the edit's measure: the eraser's radius plus half the stroke's width at each point
        val s = line(0, 0f, 0f, 100f, 0f, half = 2f)
        assertTrue(eraserTouchesStroke(s, Offset(50f, 7.9f), Offset(50f, 7.9f), 6f))
        assertFalse(eraserTouchesStroke(s, Offset(50f, 8.1f), Offset(50f, 8.1f), 6f))
        // pressure ink: thin at its start, wide at its end
        val p = ErasableInkStroke(1, 0, listOf(Offset(0f, 0f), Offset(100f, 0f)), listOf(0.5f, 4f))
        assertFalse(eraserTouchesStroke(p, Offset(0f, 7f), Offset(0f, 7f), 6f))
        assertTrue(eraserTouchesStroke(p, Offset(100f, 9.5f), Offset(100f, 9.5f), 6f))
        // a dot
        val dot = line(2, 40f, 40f, half = 1f)
        assertTrue(eraserTouchesStroke(dot, Offset(30f, 40f), Offset(30f, 50f), 9.5f))
        assertFalse(eraserTouchesStroke(dot, Offset(30f, 40f), Offset(30f, 50f), 8.5f))
    }

    @Test
    fun aStrokeThatCrossesAnErasedOneStaysWhole() {
        // an X: the eraser on one arm, away from where they cross, takes that stroke only
        val a = line(0, 0f, 0f, 100f, 100f)
        val b = line(1, 0f, 100f, 100f, 0f)
        val far = line(2, 300f, 300f, 400f, 300f)
        val hits = WholeStrokeHits(5f)
        hits.read(listOf(a, b, far), 0L)
        hits.passed(Offset(10f, 10f), 1L)
        hits.passed(Offset(20f, 20f), 2L)
        assertEquals(listOf(0), hits.hidden.map { it.index })
        assertTrue(hits.isHidden(0))
        assertFalse(hits.isHidden(1))
        // the stroke it crosses is left drawn where they meet; one far away has nothing to keep
        assertTrue(b.meets(a, 0f))
        assertFalse(far.meets(a, 0f))
    }

    @Test
    fun theLiveViewHidesWhatTheEditGetsOnLift() {
        // Draw hides, while the eraser is down, the strokes the one edit on lift removes: each
        // step of the path the edit gets is tested, also with the strokes read part way through
        val radius = 8f
        val strokes = listOf(
            line(0, 0f, 50f, 200f, 50f),
            // 20 pt from the eraser's line: out of its reach (8 + 1)
            line(1, 0f, 70f, 200f, 70f),
            // 9.5 pt away: reached only by a step the path skips (closer than radius / 8)
            line(2, 100f, 59.5f, 110f, 59.5f),
        )
        val moves = (0..40).map { Offset(it * 5f, 50f) }.toMutableList()
        moves.add(21, Offset(100f, 50.9f))
        // that step alone would take the third stroke: the edit never gets it
        assertTrue(eraserTouchesStroke(strokes[2], Offset(100f, 50f), Offset(100f, 50.9f), radius))

        val points = arrayListOf(moves[0])
        val hits = WholeStrokeHits(radius)
        hits.passed(moves[0], 0L)
        for ((k, m) in moves.drop(1).withIndex()) {
            if (k == 10) hits.read(strokes, k.toLong())
            val before = points.last()
            // one edit for the gesture: the path never fills a command before lift
            assertNull(appendEraserPoint(points, m, radius))
            if (points.last() != before) hits.passed(points.last(), k.toLong())
        }

        val command = inkEraseCommand(0, points, radius.toDouble(), whole = true)
        assertEquals(true, command["whole"])
        @Suppress("UNCHECKED_CAST")
        val path = (command["path"] as List<List<Double>>).map { Offset(it[0].toFloat(), it[1].toFloat()) }
        // the engine's segments: the path's steps, or one point
        val steps = if (path.size == 1) listOf(path[0] to path[0]) else path.zipWithNext()
        val removed = strokes.filter { s -> steps.any { (a, b) -> eraserTouchesStroke(s, a, b, radius) } }.map { it.index }
        assertEquals(listOf(0), removed)
        assertEquals(removed, hits.hidden.map { it.index }.sorted())
    }

    @Test
    fun theLogSaysHowLongATouchedStrokeWaited() {
        // touched at 100, before the strokes were read (400) and the page under them drawn (450)
        val hits = WholeStrokeHits(5f)
        hits.passed(Offset(10f, 10f), 100L)
        assertTrue(hits.read(listOf(line(0, 0f, 10f, 50f, 10f)), 400L))
        hits.passed(Offset(10f, 300f), 500L)
        assertFalse(hits.read(listOf(line(0, 0f, 300f, 50f, 300f)), 600L))
        assertEquals(1, hits.hidden.size)
        assertEquals(350L, hits.longestWait(450L))
        // touched once the view was ready: gone on the next frame
        val late = WholeStrokeHits(5f)
        late.read(listOf(line(0, 0f, 10f, 50f, 10f)), 0L)
        assertTrue(late.passed(Offset(10f, 10f), 600L))
        assertEquals(0L, late.longestWait(450L))
    }

    @Test
    fun strokesFromTheEngineAreCheckedBeforeUse() {
        val s = erasableInkStroke(3, 1, listOf(1f, 2f, 3f, 4f), listOf(0.5f, 1.5f))
        assertNotNull(s)
        assertEquals(listOf(Offset(1f, 2f), Offset(3f, 4f)), s!!.points)
        assertEquals(1.5f, s.reach, 0f)
        assertEquals(3 to 1, s.index to s.stroke)
        assertNull(erasableInkStroke(0, 0, listOf(1f, 2f, 3f), listOf(0.5f, 1.5f)))
        assertNull(erasableInkStroke(0, 0, emptyList(), emptyList()))
        assertNull(erasableInkStroke(0, 0, listOf(1f, Float.NaN), listOf(1f)))
    }
}
