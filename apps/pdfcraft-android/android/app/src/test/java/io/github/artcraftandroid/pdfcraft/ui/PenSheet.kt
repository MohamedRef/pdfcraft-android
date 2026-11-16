package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkPointThinner
import io.github.artcraftandroid.pdfcraft.ui.viewer.INK_POINT_SPACING_DP
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkPointThinner
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkSmoother
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.PEN_PRESSURE_DEFAULT
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenDials
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenDynamics
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureRange
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureSource
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkStylePointWidths
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A fixed sheet of handwriting for the pen tests and pictures (#601). No stroke here was recorded
 * on a phone: each is made, the same every run, to the numbers of the owner's 0.0.36-beta log
 * (SM-S928B). An S Pen gives about 550 samples a second, a finger about 180; the light hand's raw
 * pressure is 0.07…0.25 with a soft landing and lift; the pen moves at 150…350 dp/s, a finger at
 * 600…950 dp/s. Movements are laid out on the glass (dp) and put on a page shown at `dpPerPt`.
 */
internal object PenSheet {
    /** One sample: page point, time (ms), the pen's raw pressure. */
    data class S(val p: Offset, val t: Long, val pressure: Float)

    /** A drawn stroke: its points, their times, the widths (pt) under the pen and after the lift. */
    class Line(val points: List<Offset>, val times: List<Long>, val live: List<Float>, val widths: List<Float>)

    /**
     * A movement on the glass: [path] gives the position (dp) at 0…1 along it, [ms] how long it
     * takes, [press] the pressure the hand means at 0…1 (before landing and lift), [pace] how the
     * hand's progress runs with time (0…1 to 0…1; a flat part is a pause).
     */
    private fun stroke(
        finger: Boolean, dpPerPt: Float, ms: Int,
        path: (Float) -> Offset, press: (Float) -> Float = { 0.14f }, pace: (Float) -> Float = { it },
    ): List<S> {
        val hz = if (finger) 180f else 550f
        val n = maxOf(2, (ms * hz / 1000f).toInt())
        return List(n) { i ->
            val time = i / (n - 1f)
            val f = pace(time).coerceIn(0f, 1f)
            val tMs = time * ms
            // the pen lands and lifts over about 12 ms; its pressure wavers a little, sample to sample
            val landing = (tMs / 12f).coerceIn(0.1f, 1f) * ((ms - tMs) / 12f).coerceIn(0.1f, 1f)
            val waver = 1f + 0.06f * sin(i * 1.7f) * cos(i * 0.31f)
            S(path(f) / dpPerPt, tMs.toLong(), if (finger) 1f else (press(f) * landing * waver).coerceIn(0.005f, 1f))
        }
    }

    /** Cursive loops, `l` after `l`: [count] loops [height] dp tall. */
    private fun loops(count: Int, height: Float): (Float) -> Offset = { f ->
        val a = f * count * 2f * PI.toFloat()
        Offset(f * count * height * 0.55f + height * 0.22f * sin(a), height * 0.5f * (1f - cos(a)))
    }

    /** Every stroke of the sheet by name, for a pen or a [finger], on a page shown at [dpPerPt]. */
    fun strokes(finger: Boolean, dpPerPt: Float): Map<String, List<S>> {
        val quick = if (finger) 0.4f else 1f // a finger covers the same ground in less time
        fun t(ms: Int) = (ms * quick).toInt()
        return linkedMapOf(
            // a word: loops, heavier on the way down (y grows downwards)
            "word" to stroke(finger, dpPerPt, t(1400), loops(5, 26f), press = { f -> 0.14f + 0.05f * sin(f * 10f * PI.toFloat() - 0.6f) }),
            // one line with a harder press in its middle, written slowly there
            "press" to stroke(
                finger, dpPerPt, t(900), { f -> Offset(f * 90f, 6f * sin(f * PI.toFloat())) },
                press = { f -> if (f in 0.4f..0.6f) 0.14f + 0.31f * sin((f - 0.4f) * 5f * PI.toFloat()) else 0.14f },
                pace = { x -> x + 0.7f * sin(2f * PI.toFloat() * x) / (2f * PI.toFloat()) },
            ),
            // a corner where the hand stops for a moment, pressing on
            "pause" to stroke(
                finger, dpPerPt, t(1000), { f -> if (f < 0.5f) Offset(f * 100f, 0f) else Offset(50f, (f - 0.5f) * 80f) },
                press = { f -> 0.13f + 0.12f * (1f - kotlin.math.abs(f - 0.5f) * 2f) },
                pace = { x -> if (x < 0.35f) x / 0.35f * 0.5f else if (x < 0.6f) 0.5f else 0.5f + (x - 0.6f) / 0.4f * 0.5f },
            ),
            // a short downstroke, the stem of an `i`: 15 dp
            "short" to stroke(finger, dpPerPt, t(110), { f -> Offset(2f * f, 15f * f) }),
            // a full stop
            "dot" to stroke(finger, dpPerPt, t(60), { f -> Offset(0.15f * f, 0.1f * f) }),
            // a fast flourish
            "fast" to stroke(finger, dpPerPt, t(260), { f -> Offset(f * 150f, 18f * sin(f * 2f * PI.toFloat())) }, press = { f -> 0.1f + 0.1f * f }),
        )
    }

    /**
     * [stroke] written with [style] at [penWidth] pt as the app writes it: smoothed, widths as it
     * grows, its points kept [spacingDp] apart on the glass (#609; 0 keeps every sample), then lifted.
     * [dials] are the pen's and [pressureSetting] its Pressure setting; [densities] takes the ink density of each point kept while writing.
     */
    fun draw(
        style: InkStyle, stroke: List<S>, finger: Boolean, penWidth: Float, dpPerPt: Float, range: PressureRange,
        spacingDp: Float = INK_POINT_SPACING_DP, dials: PenDials = PenDials.default(style),
        densities: MutableList<Float>? = null, pressureSetting: Float = PEN_PRESSURE_DEFAULT,
    ): Line {
        val dyn = PenDynamics(dials, if (finger) PressureSource.Speed else PressureSource.Pen, if (finger) PressureRange() else range, penWidth, dpPerPt = dpPerPt, pressureSetting = pressureSetting)
        val smoother = InkSmoother(dials.smoothing, dpPerPt)
        val points = ArrayList<Offset>()
        val times = ArrayList<Long>()
        val scales = ArrayList<Float>()
        val thinner = if (spacingDp > 0f) inkPointThinner(dpPerPt, penWidth, spacingDp) else InkPointThinner(0f)
        for (s in stroke) {
            val q = smoother.next(s.p, s.t)
            thinner.add(q, dyn.next(q, s.t, s.pressure), dyn.density) { point, scale, density ->
                densities?.add(density)
                points += point
                times += s.t
                scales += scale
            }
        }
        thinner.flush()?.let { (q, scale, _) ->
            points += q
            times += stroke.last().t
            scales += scale
        }
        smoother.end()?.let { end ->
            points += end
            times += stroke.last().t
            scales += dyn.next(end, stroke.last().t, stroke.last().pressure)
        }
        val live = inkStylePointWidths(scales, penWidth, style)
        dyn.finish(points, scales)
        return Line(points, times, live, inkStylePointWidths(scales, penWidth, style))
    }
}
