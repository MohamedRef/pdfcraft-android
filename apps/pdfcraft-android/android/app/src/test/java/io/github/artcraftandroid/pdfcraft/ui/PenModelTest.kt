package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.FloatSampleList
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.PEN_TAPER_FLOOR
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenDials
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenDynamics
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenStrokeStats
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureCalibration
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureRange
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureSource
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkStylePointWidths
import io.github.artcraftandroid.pdfcraft.ui.viewer.penDialsOf
import io.github.artcraftandroid.pdfcraft.ui.viewer.quantile
import io.github.artcraftandroid.pdfcraft.ui.viewer.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * #569: every pen is one model with its own dials, and on a fixed test sheet of recorded strokes
 * each pen's line can be told from the others by numbers (the issue's section 6).
 */
class PenModelTest {
    /** One recorded sample: page point, time, pen pressure. */
    private data class S(val p: Offset, val t: Long, val pressure: Float)

    private val penWidth = 4f

    /** A straight stroke from [from] to [to] in [n] samples [dtMs] apart, pressure from [p0] to [p1]. */
    private fun line(from: Offset, to: Offset, n: Int, dtMs: Long, p0: Float, p1: Float) = List(n) { i ->
        val f = i / (n - 1f)
        S(from + (to - from) * f, i * dtMs, p0 + (p1 - p0) * f)
    }

    /** The same handwriting-like wave ("a word"), written in [ms] milliseconds at pressure 0.6. */
    private fun word(ms: Long): List<S> {
        val n = 160
        return List(n) { i ->
            val f = i / (n - 1f)
            S(Offset(f * 120f, 20f * sin(f * 6f * PI.toFloat())), (f * ms).toLong(), 0.6f)
        }
    }

    // the sheet
    private val lightToHard = line(Offset(0f, 0f), Offset(240f, 0f), 160, 8L, 0.05f, 1f)
    private val slowWord = word(3000L)
    private val fastWord = word(500L)
    private val down = line(Offset(0f, 0f), Offset(0f, 80f), 60, 8L, 0.6f, 0.6f)
    private val side = line(Offset(0f, 0f), Offset(80f, 0f), 60, 8L, 0.6f, 0.6f)

    private fun widths(style: InkStyle, stroke: List<S>, source: PressureSource = PressureSource.Pen, finish: Boolean = false): List<Float> {
        val dyn = PenDynamics(PenDials.default(style), source, PressureRange(), penWidth)
        val scales = stroke.map { dyn.next(it.p, it.t, it.pressure) }.toMutableList()
        if (finish) dyn.finish(stroke.map { it.p }, scales)
        return inkStylePointWidths(scales, penWidth, style)
    }

    /** Widths away from both ends, where neither taper reaches. */
    private fun middle(w: List<Float>) = w.subList(w.size / 10, w.size - w.size / 10)

    private fun spread(w: List<Float>) = w.max() / w.min()

    @Test fun ballpointIsNearlyConstant() {
        assertTrue(spread(widths(InkStyle.Ballpoint, lightToHard, finish = true)) <= 1.25f)
    }

    @Test fun fountainAndBrushAnswerPressureFourAndTenTimes() {
        assertTrue(spread(middle(widths(InkStyle.Fountain, lightToHard))) >= 4f)
        assertTrue(spread(middle(widths(InkStyle.Brush, lightToHard))) >= 10f)
    }

    @Test fun fountainThinsWhenFast() {
        val slow = quantile(middle(widths(InkStyle.Fountain, slowWord)), 0.5f)!!
        val fast = quantile(middle(widths(InkStyle.Fountain, fastWord)), 0.5f)!!
        assertTrue("fast $fast < slow $slow", fast < slow * 0.9f)
        // a ballpoint does not care
        val bSlow = quantile(middle(widths(InkStyle.Ballpoint, slowWord)), 0.5f)!!
        val bFast = quantile(middle(widths(InkStyle.Ballpoint, fastWord)), 0.5f)!!
        assertEquals(bSlow, bFast, 0.01f)
    }

    @Test fun fountainDownstrokesAreThickerThanSidestrokes() {
        val d = quantile(middle(widths(InkStyle.Fountain, down)), 0.5f)!!
        val s = quantile(middle(widths(InkStyle.Fountain, side)), 0.5f)!!
        assertTrue("down $d > side $s", d > s * 1.3f)
        // the ballpoint's tip is round
        assertEquals(quantile(middle(widths(InkStyle.Ballpoint, down)), 0.5f)!!, quantile(middle(widths(InkStyle.Ballpoint, side)), 0.5f)!!, 0.01f)
    }

    @Test fun aFingerStillTellsFountainFromBallpoint() {
        fun ratio(style: InkStyle) =
            quantile(middle(widths(style, fastWord, PressureSource.Speed)), 0.5f)!! / quantile(middle(widths(style, slowWord, PressureSource.Speed)), 0.5f)!!
        val ball = ratio(InkStyle.Ballpoint)
        val fountain = ratio(InkStyle.Fountain)
        assertTrue("fountain $fountain much below ballpoint $ball", fountain < ball - 0.2f)
        assertTrue(ball > 0.8f)
    }

    /**
     * By width alone. The pencil's own look (opacity by pressure, tilt, grain) is step 3 of #569, so
     * its width is still close to the ballpoint's and that pair is left out until then.
     */
    @Test fun everyPenOnTheSheetIsUnlikeEveryOther() {
        val pens = listOf(InkStyle.Ballpoint, InkStyle.Fountain, InkStyle.Brush, InkStyle.Pencil)
        val prints = pens.associateWith { style ->
            listOf(lightToHard, slowWord, fastWord, down, side).flatMap { stroke ->
                val w = middle(widths(style, stroke))
                listOf(w.min(), quantile(w, 0.5f)!!, w.max())
            }
        }
        for (a in pens) for (b in pens) if (a < b && setOf(a, b) != setOf(InkStyle.Ballpoint, InkStyle.Pencil)) {
            val diff = prints.getValue(a).zip(prints.getValue(b)).maxOf { (x, y) -> kotlin.math.abs(x - y) / maxOf(x, y) }
            assertTrue("$a and $b differ by ${diff * 100} %", diff > 0.15f)
        }
    }

    /** What is drawn under the pen is what stays: a sample's width never changes as the stroke grows. */
    @Test fun widthsDoNotChangeAsTheStrokeGrows() {
        for (style in InkStyle.entries) {
            val full = widths(style, slowWord)
            val half = widths(style, slowWord.subList(0, 70))
            assertEquals(half, full.subList(0, 70))
        }
    }

    @Test fun theEndTaperGoesSmoothlyToAPoint() {
        val w = widths(InkStyle.Fountain, lightToHard, finish = true)
        val untapered = widths(InkStyle.Fountain, lightToHard)
        assertEquals(untapered.last() * PEN_TAPER_FLOOR, w.last(), 1e-4f)
        // no jump where the taper begins: it starts gently and only ever narrows towards the end
        val ratio = w.indices.map { w[it] / untapered[it] }
        val first = ratio.indexOfFirst { it < 1f - 1e-6f }
        assertTrue(first > 0 && ratio[first] >= 0.9f)
        assertTrue(ratio.subList(first, ratio.size).zipWithNext().all { (a, b) -> b <= a + 1e-6f })
        // the start is pointed as it is drawn
        assertTrue(untapered.first() < untapered[20] * 0.3f)
    }

    @Test fun aDotKeepsItsFullWidth() {
        val dot = listOf(S(Offset(5f, 5f), 0L, 0.7f), S(Offset(5.2f, 5f), 8L, 0.7f))
        val w = widths(InkStyle.Brush, dot, finish = true)
        val plain = PenDials.default(InkStyle.Brush).let { it.minScale + (it.maxScale - it.minScale) * Math.pow(0.7, it.curve.toDouble()).toFloat() }
        assertTrue("dot ${w.first()} vs ${plain * penWidth}", w.first() > plain * penWidth * 0.8f)
    }

    @Test fun theMarkerKeepsOneWidth() {
        val w = widths(InkStyle.Highlighter, lightToHard, finish = true)
        assertTrue(w.all { kotlin.math.abs(it - penWidth * 2.25f) < 1e-4f })
        assertFalse(PenDials.default(InkStyle.Highlighter).varies)
    }

    @Test fun theMeasuredRangeIsStretchedOverThePen() {
        var c = PressureCalibration()
        val writing = List(40) { 0.3f + 0.2f * (it % 10) / 9f } // a hand that writes between 0.3 and 0.5
        repeat(PressureCalibration.MIN_STROKES - 1) { c = c.learn(writing, 1000L) }
        assertEquals(PressureRange(), c.range) // not trusted yet
        c = c.learn(writing, 2000L)
        assertEquals(PressureCalibration.MIN_STROKES, c.strokes)
        assertEquals(2000L, c.measuredAt)
        val r = c.range
        assertTrue(r.lo in 0.29f..0.33f && r.hi in 0.47f..0.51f)
        assertEquals(0f, r.stretch(0.2f), 1e-6f)
        assertEquals(1f, r.stretch(0.6f), 1e-6f)
        assertEquals(0.5f, r.stretch((r.lo + r.hi) / 2f), 1e-4f)
        // a short stroke and a pen with one fixed pressure measure nothing
        assertEquals(c, c.learn(listOf(0.4f, 0.4f), 3000L))
        assertEquals(c, c.learn(List(40) { 1f }, 3000L))
        // a too narrow band is not stretched
        assertEquals(0.42f, PressureRange(0.4f, 0.45f).stretch(0.42f), 1e-6f)
    }

    @Test fun storedDialsCannotBreakAStroke() {
        val bad = penDialsOf(List(10) { Float.NaN }).sanitized()
        val dyn = PenDynamics(bad, PressureSource.Pen, PressureRange(), penWidth)
        val w = slowWord.map { dyn.next(it.p, it.t, it.pressure) }
        assertTrue(w.all { it.isFinite() && it > 0f })
        val swapped = PenDials(2f, 0.5f).sanitized()
        assertTrue(swapped.maxScale >= swapped.minScale)
        for (style in InkStyle.entries) assertEquals(PenDials.default(style), penDialsOf(PenDials.default(style).toList()))
    }

    @Test fun strokeStatsStayBounded() {
        val list = FloatSampleList(cap = 100)
        repeat(10_000) { list.add(it.toFloat()) }
        assertTrue(list.toList().size <= 100)
        assertEquals(0f, list.toList().first(), 0f)
        val stats = PenStrokeStats(InkStyle.Fountain, PressureSource.Pen, penWidth, PenDials.default(InkStyle.Fountain), 1f)
        val dyn = PenDynamics(PenDials.default(InkStyle.Fountain), PressureSource.Pen, PressureRange(), penWidth, stats)
        lightToHard.forEach { dyn.next(it.p, it.t, it.pressure) }
        assertEquals(lightToHard.size, stats.widths.size)
        assertEquals(10f, stats.taperStartPt, 1e-4f)
        assertEquals(0.05f, stats.raw.toList().first(), 1e-6f)
        // 240 pt in 1.27 s: about 189 pt/s once the speed has settled
        assertEquals(189f, quantile(stats.speeds.toList(), 0.9f)!!, 15f)
    }
}
