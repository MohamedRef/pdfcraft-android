package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenDials
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenDynamics
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureRange
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #611: the end of a line is drawn to a point while it is written, so the lift changes nothing
 * that can be seen. "Before" is the last picture with the pen down, after its last sample; "after"
 * the stroke as it is saved. The handwriting is [PenSheet]'s.
 */
class EndTaperPreviewTest {
    private val hand = PressureRange(0.07f, 0.15f)
    private val pointed = listOf(InkStyle.Fountain, InkStyle.Brush)

    /** The width of the line [fromEnd] points of length before its end (linear between points). */
    private fun widthAt(points: List<Offset>, widths: List<Float>, fromEnd: Float): Float {
        var d = 0f
        for (i in points.lastIndex downTo 1) {
            val step = (points[i] - points[i - 1]).getDistance()
            if (d + step >= fromEnd) return if (step <= 0f) widths[i] else widths[i] + (widths[i - 1] - widths[i]) * ((fromEnd - d) / step)
            d += step
        }
        return widths.first()
    }

    private fun length(points: List<Offset>) = points.zipWithNext { a, b -> (b - a).getDistance() }.sum()

    @Test fun theLiftChangesNoWidthAnywhere() {
        for (style in InkStyle.entries.filter { it != InkStyle.Highlighter }) for (finger in listOf(false, true)) for ((dpPerPt, pen) in listOf(0.61f to 1.5f, 0.61f to 13.8f, 1.5f to 2f)) {
            for ((name, stroke) in PenSheet.strokes(finger, dpPerPt)) {
                // a dot gets its even, full width on lift, by design (#601). The bound is 0.1 pt, and 2 % of
                // the pen's width for a broad pen (0.28 pt at 13.8 pt): the pen's very last point gets
                // its own width on lift, which the picture before it can only take from the point before.
                if (name == "dot") continue
                val line = PenSheet.draw(style, stroke, finger, pen, dpPerPt, hand)
                val total = length(line.points)
                assertEquals("$style $name: the line drawn reaches as far as the line saved", total, length(line.downPoints), 0.06f)
                var worst = 0f
                var d = 0f
                while (d <= total) {
                    worst = maxOf(worst, kotlin.math.abs(widthAt(line.points, line.widths, d) - widthAt(line.downPoints, line.downWidths, d)))
                    d += 0.05f
                }
                assertTrue("$style $name finger=$finger, $pen pt at $dpPerPt dp/pt: a width changes by $worst pt on lift", worst < maxOf(0.1f, pen * 0.02f))
            }
        }
    }

    @Test fun theLiftDoesNotMakeADotGrow() {
        // #661: the sheet's full stop (60 ms) with the owner's pens, 13.8 and 15.2 pt, and a fine one
        for (style in pointed) for (finger in listOf(false, true)) for ((dpPerPt, pen) in listOf(0.65f to 13.8f, 0.65f to 15.2f, 1.5f to 2f)) {
            val line = PenSheet.draw(style, PenSheet.strokes(finger, dpPerPt).getValue("dot"), finger, pen, dpPerPt, hand)
            val down = line.downWidths.max()
            val lifted = line.widths.max()
            // within a tenth: the very last sample is not a point of the line until the lift (it was five times before)
            assertTrue("$style finger=$finger, $pen pt: the dot is $down pt with the pen down and $lifted pt lifted", kotlin.math.abs(lifted - down) < maxOf(0.1f, lifted * 0.12f))
            assertEquals(line.widths.max(), line.widths.min(), 1e-4f)
        }
    }

    @Test fun withoutThePreviewTheLiftDidChangeTheEnd() {
        // what the issue measured: the line drawn at its full width to the end, then tapered on lift
        val line = PenSheet.draw(InkStyle.Fountain, PenSheet.strokes(false, 0.61f).getValue("word"), false, 1.5f, 0.61f, hand)
        assertTrue(line.live.last() - line.widths.last() > 0.3f)
        assertTrue(line.downWidths.last() < line.live.last() * 0.5f)
    }

    @Test fun onlyTheLastStretchIsWalked() {
        for (style in pointed) {
            val pen = 1.5f
            val line = PenSheet.draw(style, PenSheet.strokes(false, 0.61f).getValue("word"), false, pen, 0.61f, hand)
            // the points from downFrom on are the pointed end and at most one point before it
            val stretch = length(line.points.subList(line.downFrom, line.points.size))
            val taper = PenDials.default(style).taperEnd * pen
            assertTrue("$style: ${line.points.size - line.downFrom} of ${line.points.size} points, $stretch pt", stretch < taper + 2f && line.downFrom > line.points.size * 3 / 4)
            // and before it the line drawn is the line as it stands
            assertEquals(line.live.subList(0, line.downFrom), line.downWidths.subList(0, line.downFrom))
        }
    }

    @Test fun aLineOfOneWidthAndADotHaveNoPreview() {
        fun dyn(style: InkStyle) = PenDynamics(PenDials.default(style), PressureSource.Pen, hand, 2f)
        // a ballpoint has no pointed end
        val ball = dyn(InkStyle.Ballpoint)
        val pts = List(20) { Offset(it * 1f, 0f) }
        val scales = pts.mapIndexed { i, p -> ball.next(p, i * 4L, 0.11f) }
        assertNull(ball.endPreview(pts, scales))
        // a fountain pen that has moved less than its width is still a dot
        val fountain = dyn(InkStyle.Fountain)
        val dot = List(5) { Offset(it * 0.1f, 0f) }
        val dotScales = dot.mapIndexed { i, p -> fountain.next(p, i * 4L, 0.11f) }
        assertNull(fountain.endPreview(dot, dotScales))
        // and once it has moved on it ends in a point, the saved widths untouched
        val more = dot + List(40) { Offset(0.5f + it * 0.5f, 0f) }
        val moreScales = dotScales + more.drop(5).mapIndexed { i, p -> fountain.next(p, 20L + i * 4L, 0.11f) }
        val kept = moreScales.toList()
        val end = assertNotNullAnd(fountain.endPreview(more, moreScales, tip = Offset(21f, 0f)))
        assertEquals(kept, moreScales)
        assertEquals(listOf(Offset(21f, 0f)), end.extra)
        assertTrue(end.scales.last() < kept.last() * 0.5f)
        assertEquals(more.size - end.from + 1, end.scales.size)
    }

    private fun <T> assertNotNullAnd(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
