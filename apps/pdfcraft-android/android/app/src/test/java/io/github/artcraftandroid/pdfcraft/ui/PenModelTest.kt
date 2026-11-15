package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.FloatSampleList
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkSmoother
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.PEN_TAPER_FLOOR
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenDials
import io.github.artcraftandroid.pdfcraft.ui.viewer.PencilGrain
import io.github.artcraftandroid.pdfcraft.ui.viewer.pencilGrainLevels
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenTuning
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenDynamics
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenStrokeStats
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureCalibration
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureCurve
import io.github.artcraftandroid.pdfcraft.ui.viewer.nibWidth
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureRange
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureSource
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkStylePointWidths
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkTailPoints
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
        // the band takes the middle of the pen's range: a wide band's ends land at 10 % and 90 %
        val wide = PressureRange(0.2f, 0.7f)
        assertEquals(PressureRange.BAND_LOW, wide.stretch(0.2f), 1e-5f)
        assertEquals(PressureRange.BAND_HIGH, wide.stretch(0.7f), 1e-5f)
        assertEquals(0.5f, r.stretch((r.lo + r.hi) / 2f), 1e-4f)
        // a short stroke and a pen with one fixed pressure measure nothing
        assertEquals(c, c.learn(listOf(0.4f, 0.4f), 3000L))
        assertEquals(c, c.learn(List(40) { 1f }, 3000L))
        // a too narrow band is not stretched
        assertEquals(0.42f, PressureRange(0.4f, 0.45f).stretch(0.42f), 1e-6f)
    }

    @Test fun aLightHandsNarrowBandIsNotStretchedIntoSuddenThickLines() {
        // 0.0.33 log, Galaxy S24 Ultra: the hand wrote at 0.07…0.15, and 0.22 drew the thickest line
        val r = PressureRange(0.07f, 0.15f)
        val gain = (r.stretch(0.15f) - r.stretch(0.07f)) / (0.15f - 0.07f)
        assertTrue(gain <= PressureRange.BAND_HIGH / PressureRange.MIN_STRETCH_SPAN + 1e-3f)
        assertTrue(r.stretch(0.22f) < 0.65f)
        // still more pressure, still a thicker line, never backwards
        val steps = List(200) { r.stretch((it + 1) / 200f) }
        assertTrue(steps.zipWithNext().all { (a, b) -> b > a })
        // Fountain: the usual light and hard ends of the band stay within about twice each other
        fun width(p: Float) = PenDynamics(PenDials.default(InkStyle.Fountain).copy(dampingMs = 0f, taperStart = 0f, nib = 0f, speedThin = 0f), PressureSource.Pen, r, penWidth).next(Offset.Zero, 0L, p)
        assertTrue(width(0.15f) / width(0.07f) < 2.5f)
        assertTrue(width(0.15f) > width(0.07f) * 1.2f)
    }

    @Test fun aLighterOrHarderTouchThanUsualStillChangesTheLine() {
        val r = PressureRange(0.3f, 0.5f)
        // below and above the band the line keeps answering, up to the pen's real limits
        assertTrue(r.stretch(0.1f) < r.stretch(0.2f) && r.stretch(0.2f) < r.stretch(0.3f))
        assertTrue(r.stretch(0.5f) < r.stretch(0.7f) && r.stretch(0.7f) < r.stretch(0.9f))
        assertEquals(1f, r.stretch(1f), 1e-6f)
        assertTrue(r.stretch(0.001f) < 0.01f)
        // never backwards, and no flat stretch anywhere between the lightest and the hardest touch
        val steps = List(200) { r.stretch((it + 1) / 200f) }
        assertTrue(steps.zipWithNext().all { (a, b) -> b > a })
        // Fountain: pressing harder than the usual hardest still thickens the line
        fun width(p: Float) = PenDynamics(PenDials.default(InkStyle.Fountain).copy(dampingMs = 0f, taperStart = 0f), PressureSource.Pen, r, penWidth).next(Offset.Zero, 0L, p)
        assertTrue(width(0.9f) > width(0.5f) * 1.05f)
        assertTrue(width(1f) > width(0.5f) * 1.1f)
        assertTrue(width(0.1f) < width(0.3f) * 0.95f)
        // the unmeasured range, and a band that reaches the pen's limit, leave no room to invent
        assertEquals(0.37f, PressureRange().stretch(0.37f), 1e-6f)
        assertEquals(1f, PressureRange(0.4f, 1f).stretch(1f), 1e-6f)
        assertEquals(PressureRange.BAND_LOW, PressureRange(0.4f, 1f).stretch(0.4f), 1e-6f)
    }

    @Test fun storedDialsCannotBreakAStroke() {
        val bad = penDialsOf(List(PenTuning.KEYS.size) { Float.NaN }).sanitized()
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

    /** A slow line with a 0.6 pt hand tremor across it, sampled every 8 ms. */
    private fun shakyLine(ptPerS: Float, n: Int = 120) = List(n) { i ->
        val x = ptPerS * i * 8 / 1000f
        S(Offset(x, if (i % 2 == 0) 0.6f else -0.6f), i * 8L, 0.6f)
    }

    @Test fun smoothingTakesOutTheTremorOfASlowHand() {
        val shaky = shakyLine(60f)
        val s = InkSmoother(0.5f)
        val out = shaky.map { s.next(it.p, it.t) }
        val wobble = out.subList(out.size / 10, out.size - out.size / 10).maxOf { kotlin.math.abs(it.y) }
        assertTrue("wobble $wobble", wobble < 0.6f * 0.35f)
        // nothing is smoothed at 0
        val off = InkSmoother(0f)
        assertEquals(shaky.map { it.p }, shaky.map { off.next(it.p, it.t) })
    }

    @Test fun aFastLineDoesNotTrailThePen() {
        val fast = line(Offset(0f, 0f), Offset(400f, 0f), 50, 8L, 0.6f, 0.6f) // 1000 pt/s
        val s = InkSmoother(0.5f)
        val out = fast.map { s.next(it.p, it.t) }
        val lag = (fast.last().p - out.last()).getDistance()
        assertTrue("lag $lag pt", lag < 6f) // under one sample's travel
        // on lift the line reaches where the pen left the page
        assertEquals(fast.last().p, s.end())
        assertEquals(null, InkSmoother(0.5f).also { it.next(Offset(1f, 1f), 0L) }.end())
    }

    @Test fun smoothingSurvivesBadSamples() {
        val s = InkSmoother(1f)
        s.next(Offset(0f, 0f), 0L)
        s.next(Offset(Float.NaN, 3f), 8L)
        s.next(Offset(5f, 0f), 8L) // same time
        val p = s.next(Offset(10f, 0f), 16L)
        assertTrue(p.x.isFinite() && p.y.isFinite())
        assertTrue(InkSmoother(Float.NaN).next(Offset(2f, 2f), 0L) == Offset(2f, 2f))
    }

    @Test fun theNibIsAnEllipse() {
        assertEquals(0.65f, nibWidth(0f, 0.35f), 1e-5f) // along the long axis: thin
        assertEquals(1f, nibWidth((PI / 2).toFloat(), 0.35f), 1e-5f) // across it: full
        assertEquals(1f, nibWidth(1.234f, 0f), 1e-5f) // a round tip
        // it turns smoothly: no kink where the stroke crosses the long axis
        val e = 1e-3f
        val slope = (nibWidth(e, 0.35f) - nibWidth(0f, 0.35f)) / e
        assertTrue("slope $slope", kotlin.math.abs(slope) < 0.01f)
    }

    @Test fun thePressureCurveRisesAndStaysInside() {
        assertTrue(PressureCurve.LINEAR.linear)
        for (i in 0..20) assertEquals(i / 20f, PressureCurve.LINEAR.map(i / 20f), 1e-4f)
        val soft = PressureCurve(listOf(0.55f, 0.8f, 0.92f)) // a light hand: thick early
        val xs = (0..200).map { it / 200f }
        val ys = xs.map { soft.map(it) }
        assertTrue(ys.zipWithNext().all { (a, b) -> b >= a - 1e-6f })
        assertTrue(ys.all { it in 0f..1f })
        assertEquals(0.55f, soft.map(0.25f), 1e-4f)
        assertEquals(0f, soft.map(0f), 1e-6f)
        assertEquals(1f, soft.map(1f), 1e-6f)
        // stored nonsense is made sane: in range, rising
        val bad = PressureCurve(listOf(Float.NaN, 2f, 0.1f))
        assertEquals(listOf(0.25f, 1f, 1f), bad.ys)
        // and the curve reaches the stroke
        val dyn = PenDynamics(PenDials(0f + 0.05f, 1f), PressureSource.Pen, PressureRange(), penWidth, null, soft)
        val plain = PenDynamics(PenDials(0.05f, 1f), PressureSource.Pen, PressureRange(), penWidth)
        assertTrue(dyn.next(Offset.Zero, 0L, 0.25f) > plain.next(Offset.Zero, 0L, 0.25f) * 1.5f)
    }

    /** The same pen movement on the glass ([dp], in dp), on a page shown at [dpPerPt] dp a point. */
    private fun onPage(dp: List<S>, dpPerPt: Float) = dp.map { S(it.p / dpPerPt, it.t, it.pressure) }

    @Test fun theSameHandGivesTheSameLineAtEveryZoom() {
        // one movement of the hand: a word 120 dp long written in half a second, with a small tremor
        val hand = fastWord.mapIndexed { i, s -> S(s.p + Offset(0f, if (i % 2 == 0) 0.2f else -0.2f), s.t, s.pressure) }
        fun line(dpPerPt: Float): Pair<List<Offset>, List<Float>> {
            val smoother = InkSmoother(0.5f, dpPerPt)
            val dyn = PenDynamics(PenDials.default(InkStyle.Fountain), PressureSource.Pen, PressureRange(), penWidth, dpPerPt = dpPerPt)
            val points = ArrayList<Offset>()
            val scales = ArrayList<Float>()
            for (s in onPage(hand, dpPerPt)) {
                val q = smoother.next(s.p, s.t)
                points += q * dpPerPt // back on the glass, to compare
                scales += dyn.next(q, s.t, s.pressure)
            }
            return points to scales
        }
        // fit-width on a phone, fit-width on a tablet, and zoomed in: the page differs sevenfold
        val (phonePoints, phoneScales) = line(0.6f)
        for (dpPerPt in listOf(1.3f, 4f)) {
            val (points, scales) = line(dpPerPt)
            // the start taper is counted in pen widths on the page, so compare past it
            val from = points.size / 2
            for (i in from until points.size) {
                assertEquals("width at $i, $dpPerPt dp/pt", phoneScales[i], scales[i], 0.01f)
                assertTrue("point $i, $dpPerPt dp/pt", (points[i] - phonePoints[i]).getDistance() < 0.01f)
            }
        }
        // and a finger's pressure, made from speed, is the same too
        fun fingerWidths(dpPerPt: Float): List<Float> {
            val dyn = PenDynamics(PenDials.default(InkStyle.Ballpoint).copy(minScale = 0.3f, maxScale = 1.5f), PressureSource.Speed, PressureRange(), penWidth, dpPerPt = dpPerPt)
            return onPage(fastWord, dpPerPt).map { dyn.next(it.p, it.t, 1f) }
        }
        fingerWidths(0.6f).zip(fingerWidths(4f)).forEach { (a, b) -> assertEquals(a, b, 0.01f) }
    }

    @Test fun speedIsMeasuredOnTheGlass() {
        // 240 pt in 1.27 s on a page shown at 2 dp a point: about 378 dp/s
        val dyn = PenDynamics(PenDials.default(InkStyle.Fountain), PressureSource.Pen, PressureRange(), penWidth, dpPerPt = 2f)
        lightToHard.forEach { dyn.next(it.p, it.t, it.pressure) }
        assertEquals(378f, dyn.speedDpPerS, 30f)
        // a size that cannot be right counts as 1 dp a point
        val broken = PenDynamics(PenDials.default(InkStyle.Fountain), PressureSource.Pen, PressureRange(), penWidth, dpPerPt = Float.NaN)
        lightToHard.forEach { broken.next(it.p, it.t, it.pressure) }
        assertEquals(189f, broken.speedDpPerS, 15f)
    }

    @Test fun theTailReachesThePen() {
        val fast = line(Offset(0f, 0f), Offset(400f, 0f), 50, 8L, 0.6f, 0.6f)
        val s = InkSmoother(0.5f)
        val out = fast.map { s.next(it.p, it.t) }
        // the smoothed line stops short of the pen; the tip is where the pen is
        assertTrue(out.last().x < fast.last().p.x)
        assertEquals(fast.last().p, s.tip)
        // the tail: from the line's end to the pen, then on to where the pen is going
        assertEquals(listOf(out.last(), fast.last().p, fast.last().p + Offset(3f, 1f)), inkTailPoints(out.last(), s.tip, 3f to 1f))
        assertEquals(listOf(out.last(), fast.last().p), inkTailPoints(out.last(), s.tip, null))
        // nothing to draw when the line is at the pen and no prediction is known
        assertEquals(emptyList<Offset>(), inkTailPoints(Offset(1f, 1f), Offset(1f, 1f), null))
        assertEquals(emptyList<Offset>(), inkTailPoints(Offset(1f, 1f), null, 0f to 0f))
        // an unsmoothed line still gets its predicted tail, and bad numbers draw nothing extra
        assertEquals(listOf(Offset(1f, 1f), Offset(4f, 1f)), inkTailPoints(Offset(1f, 1f), null, 3f to 0f))
        assertEquals(emptyList<Offset>(), inkTailPoints(Offset(1f, 1f), Offset(Float.NaN, 0f), Float.NaN to 0f))
        assertEquals(emptyList<Offset>(), inkTailPoints(Offset(Float.NaN, 1f), Offset(2f, 2f), 1f to 1f))
        assertEquals(null, InkSmoother(0.5f).tip)
    }

    @Test fun thePencilGrainIsTheEnginesGrain() {
        // the engine's grain_hash gives these words (crates/annot tests check the same)
        assertEquals(listOf(0, 0x5D139822, 0x65EF3B87), listOf(PencilGrain.hash(0), PencilGrain.hash(1), PencilGrain.hash(1023)))
        val cells = PencilGrain.TILE * PencilGrain.TILE
        for (level in 1..PencilGrain.LEVELS) {
            var on = 0
            for (gy in 0 until PencilGrain.TILE) for (gx in 0 until PencilGrain.TILE) {
                if (PencilGrain.on(gx, gy, level)) {
                    on++
                    if (level < PencilGrain.LEVELS) assertTrue(PencilGrain.on(gx, gy, level + 1))
                }
                assertEquals(PencilGrain.on(gx, gy, level), PencilGrain.on(gx - 3 * PencilGrain.TILE, gy - PencilGrain.TILE, level))
            }
            assertEquals(cells * level / PencilGrain.LEVELS, on)
        }
        assertEquals(listOf(1, 2, 8, 8), listOf(0.0, 0.31, 0.94, Double.NaN).map(PencilGrain::level))
        assertEquals(0.31, PencilGrain.saved(0.3149f), 1e-9)
    }

    @Test fun thePencilIsLighterWhenLightAndWhenLeaned() {
        val dials = PenDials.default(InkStyle.Pencil)
        assertTrue(dials.grain && dials.varies)
        fun density(pressure: Float, tilt: Float = Float.NaN): Float {
            val dyn = PenDynamics(dials, PressureSource.Pen, PressureRange(), penWidth)
            repeat(30) { dyn.next(Offset(it.toFloat(), 0f), it * 8L, pressure, tilt) }
            return dyn.density
        }
        assertTrue(density(0.05f) < 0.45f)
        assertTrue(density(1f) > 0.85f)
        assertTrue("leaned ${density(0.6f, 1.4f)} < upright ${density(0.6f, 0.1f)}", density(0.6f, 1.4f) < density(0.6f, 0.1f) * 0.75f)
        // held as for writing (the owner's S Pen: 41°…50° from upright) is not shading
        assertEquals(density(0.6f, 0.1f), density(0.6f, 0.85f), 1e-6f)
        // and broader when leaned
        fun width(tilt: Float): Float {
            val dyn = PenDynamics(dials, PressureSource.Pen, PressureRange(), penWidth)
            var w = 0f
            repeat(30) { w = dyn.next(Offset(it.toFloat(), 0f), it * 8L, 0.6f, tilt) }
            return w
        }
        assertTrue(width(1.4f) > width(0.1f) * 2.5f)
        assertEquals(width(0.1f), width(0.85f), 1e-6f)
        // the other pens stay solid
        val ball = PenDynamics(PenDials.default(InkStyle.Ballpoint), PressureSource.Pen, PressureRange(), penWidth)
        ball.next(Offset.Zero, 0L, 0.2f, 1.2f)
        assertEquals(1f, ball.density, 0f)
    }

    @Test fun aPencilStrokeIsDrawnPerGrainStep() {
        val pts = List(5) { Offset(it * 10f, 0f) }
        val radii = List(5) { 3f }
        // light, light, dense, dense, solid
        val (disc, seg) = pencilGrainLevels(listOf(0.3f, 0.3f, 0.8f, 0.8f, 1f))
        // (0.3+0.8)/2 = 0.55 -> 4; (0.8+1)/2 = 0.9 -> 7
        assertEquals(listOf(2, 4, 6, 7), (disc + seg).distinct().sorted())
        assertEquals(4, seg.size)
        // a point takes the lighter of its two segments
        assertEquals(minOf(seg[1], seg[2]), disc[2])
        assertTrue(pencilGrainLevels(List(5) { 1f }).let { (d, s) -> (d + s).all { it >= io.github.artcraftandroid.pdfcraft.ui.viewer.PencilGrain.LEVELS } })
        assertEquals(radii.size, disc.size)
    }
}
