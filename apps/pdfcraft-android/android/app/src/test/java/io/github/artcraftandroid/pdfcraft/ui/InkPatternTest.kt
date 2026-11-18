package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkAppearance
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkPattern
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkSaveStroke
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkPatternApplies
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkPatternMask
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkPatternRuns
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkSaveCommandGroups
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkSaveStrokeGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #682: dashed and dotted lines, cut on lift and saved as one annotation. */
class InkPatternTest {
    /** A 100 pt straight line with a point every point, widths from [from] to [to]. */
    private fun line(length: Int = 100, from: Float = 1f, to: Float = 1f): Pair<List<Offset>, List<Float>> =
        List(length + 1) { Offset(it.toFloat(), 50f) } to List(length + 1) { from + (to - from) * it / length }

    @Test fun dashesAreThreeWidthsWithGapsOfTwo() {
        val (pts, w) = line()
        val runs = inkPatternRuns(pts, w, InkPattern.Dashed, 2f)
        // centrelines 4 pt long (a dash of 6 with its round ends), every 10 pt from the start
        assertEquals(10, runs.size)
        runs.forEachIndexed { k, r ->
            assertEquals(10f * k, r.points.first().x, 1e-3f)
            assertEquals(10f * k + 4f, r.points.last().x, 1e-3f)
            // the line's own points inside the dash stay
            assertEquals(5, r.points.size)
            assertEquals(r.points.size, r.pressures.size)
        }
        // what is drawn: the dash plus half a width at each end, the gap less that
        val drawn = runs[0].points.last().x - runs[0].points.first().x + 2f
        val gap = runs[1].points.first().x - runs[0].points.last().x - 2f
        assertEquals(6f, drawn, 1e-3f)
        assertEquals(4f, gap, 1e-3f)
    }

    @Test fun dotsArePenWideAndSpacedTwoWidthsApart() {
        val (pts, w) = line()
        val runs = inkPatternRuns(pts, w, InkPattern.Dotted, 2f)
        // centres 4.4 pt apart from the start: 0, 4.4 … 96.8
        assertEquals(23, runs.size)
        runs.forEachIndexed { k, r ->
            assertEquals(2, r.points.size)
            assertEquals(4.4f * k, r.points.first().x, 1e-2f)
            assertTrue((r.points.last() - r.points.first()).getDistance() <= 0.011f)
        }
    }

    @Test fun theWidthsAreCarriedThroughTheCut() {
        val (pts, w) = line(from = 1f, to = 2f)
        val runs = inkPatternRuns(pts, w, InkPattern.Dashed, 2f)
        // the dash from 50 to 54: widths 1.5 to 1.54, the cut ends interpolated
        val mid = runs[5]
        assertEquals(1.5f, mid.pressures.first(), 1e-4f)
        assertEquals(1.54f, mid.pressures.last(), 1e-4f)
        assertTrue(runs.flatMap { it.pressures }.zipWithNext().all { (a, b) -> b >= a - 1e-6f })
    }

    @Test fun aLineShorterThanADashOrSolidIsItself() {
        val (pts, w) = line(length = 3)
        assertEquals(listOf(pts), inkPatternRuns(pts, w, InkPattern.Dashed, 2f).map { it.points })
        val (long, lw) = line()
        assertEquals(listOf(long), inkPatternRuns(long, lw, InkPattern.Solid, 2f).map { it.points })
        // bad input is left as it is
        assertEquals(1, inkPatternRuns(long, lw.drop(1), InkPattern.Dashed, 2f).size)
        assertEquals(1, inkPatternRuns(long, lw, InkPattern.Dashed, Float.NaN).size)
    }

    @Test fun aCrumbOfADashAtTheEndIsLeftOff() {
        // the last dash would start at 90 and the line ends at 90.3: under a quarter width
        val pts = List(91) { Offset(it.toFloat(), 0f) } + Offset(90.3f, 0f)
        val runs = inkPatternRuns(pts, List(pts.size) { 1f }, InkPattern.Dashed, 2f)
        assertEquals(9, runs.size)
        // a longer remainder is a shorter last dash
        val (pts2, w2) = line(length = 92)
        val last = inkPatternRuns(pts2, w2, InkPattern.Dashed, 2f).last()
        assertEquals(90f, last.points.first().x, 1e-3f)
        assertEquals(92f, last.points.last().x, 1e-3f)
    }

    @Test fun theMaskWhileWritingMatchesTheCut() {
        val dash = inkPatternMask(InkPattern.Dashed, 2f)!!
        // visible dashes of 6 and gaps of 4, starting a half width before the line (its round end)
        assertEquals(6f, dash.on, 1e-6f)
        assertEquals(4f, dash.off, 1e-6f)
        assertEquals(1f, dash.phase, 1e-6f)
        assertFalse(dash.round)
        val dot = inkPatternMask(InkPattern.Dotted, 2f)!!
        assertTrue(dot.round)
        assertEquals(4.4f, dot.on + dot.off, 1e-5f)
        assertNull(inkPatternMask(InkPattern.Solid, 2f))
        assertFalse(inkPatternApplies(InkStyle.Highlighter))
        assertTrue(inkPatternApplies(InkStyle.Fountain))
        assertEquals(InkPattern.Solid, InkPattern.fromKey("not a pattern"))
    }

    /** The pieces of one line are one annotation with a path per piece; the next stroke is its own. */
    @Test fun aDashedLineIsSavedAsOneAnnotation() {
        val (pts, w) = line(from = 1f, to = 2f)
        val look = InkAppearance(0xFF000000.toInt(), 1f, 2f, "Ada", InkStyle.Fountain)
        val group = Any()
        val pieces = inkPatternRuns(pts, w, InkPattern.Dashed, 2f).map { InkSaveStroke(0, look, it.points, it.pressures, true, group = group) }
        val solid = InkSaveStroke(0, look, pts, w, true)
        val strokes = pieces + solid
        assertEquals(listOf(10, 1), inkSaveStrokeGroups(strokes).map { it.size })
        val groups = inkSaveCommandGroups(strokes) { "#000000" }
        assertEquals(2, groups.size)
        val line = groups[0].single()
        @Suppress("UNCHECKED_CAST")
        assertEquals(10, (line["strokes"] as List<*>).size)
        @Suppress("UNCHECKED_CAST")
        assertEquals(10, (line["widths"] as List<*>).size)
        assertEquals(1, groups[1].size)
        // two lines of the same look, one after the other, stay two annotations
        val second = Any()
        val two = pieces + pieces.map { it.copy(group = second) }
        assertEquals(listOf(10, 10), inkSaveStrokeGroups(two).map { it.size })
    }
}
