package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkSmoother
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.PEN_MAX_TAPER_SLOPE
import io.github.artcraftandroid.pdfcraft.ui.viewer.PEN_MAX_WIDTH_SLOPE
import io.github.artcraftandroid.pdfcraft.ui.viewer.PEN_STILL_CREEP_DP_PER_S
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenDials
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenDynamics
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureRange
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureSource
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkStylePointWidths
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkTailOpacity
import io.github.artcraftandroid.pdfcraft.ui.viewer.quantile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #601: a stroke is one line, not a blob. The input is [PenSheet]'s recorded-like handwriting, made
 * to the numbers of the owner's 0.0.36-beta log (an S Pen at 550 samples a second and a light hand,
 * a finger at 180), at his pen widths and page sizes.
 */
class PenBlobTest {
    private val varying = listOf(InkStyle.Ballpoint, InkStyle.Fountain, InkStyle.Brush, InkStyle.Pencil)

    /** The owner's light hand: the band his log measured. */
    private val hand = PressureRange(0.08f, 0.18f)

    private fun spread(w: List<Float>) = w.max() / w.min()

    /** Widths away from both ends, where neither taper reaches. */
    private fun middle(w: List<Float>) = w.subList(w.size / 5, w.size - w.size / 5)

    @Test fun theWidthNeverJumpsAlongTheLine() {
        for (style in varying) for (finger in listOf(false, true)) for (penWidth in listOf(1.5f, 13.8f)) for (dpPerPt in listOf(0.61f, 1.5f)) {
            for ((name, stroke) in PenSheet.strokes(finger, dpPerPt)) {
                // every sample kept: the bound is on the path the pen took, which a thinned line's
                // straight pieces cut short where the path doubles back
                val line = PenSheet.draw(style, stroke, finger, penWidth, dpPerPt, hand, spacingDp = 0f)
                for (lifted in listOf(false, true)) {
                    val w = if (lifted) line.widths else line.live
                    val dials = PenDials.default(style)
                    val length = line.points.zipWithNext { a, b -> (b - a).getDistance() }.sum()
                    var along = 0f
                    for (i in 1 until w.size) {
                        val step = (line.points[i] - line.points[i - 1]).getDistance()
                        along += step
                        val dt = (line.times[i] - line.times[i - 1]).coerceAtLeast(0L) / 1000f
                        // a tapered end may come to its point more steeply than the line's body changes
                        val inTaper = (dials.taperStart > 0f && along - step < dials.taperStart * penWidth) ||
                            (lifted && dials.taperEnd > 0f && length - along < minOf(dials.taperEnd * penWidth, length / 3f))
                        val room = (if (inTaper) PEN_MAX_TAPER_SLOPE else PEN_MAX_WIDTH_SLOPE) * (step + PEN_STILL_CREEP_DP_PER_S * dt / dpPerPt)
                        assertTrue(
                            "$style $name finger=$finger pen=$penWidth at ${dpPerPt}dp/pt lifted=$lifted: width ${w[i - 1]} -> ${w[i]} over $step pt",
                            kotlin.math.abs(w[i] - w[i - 1]) <= room + 1e-3f,
                        )
                    }
                }
            }
        }
    }

    @Test fun aHarderPressOrAPauseIsNotABlob() {
        // the thickest part of a stroke stays near the rest of it, at a thin pen and at the
        // owner's 13.8 pt one
        for (penWidth in listOf(1.5f, 13.8f)) for (finger in listOf(false, true)) {
            for (name in listOf("press", "pause", "word")) {
                val stroke = PenSheet.strokes(finger, 0.61f).getValue(name)
                val fountain = middle(PenSheet.draw(InkStyle.Fountain, stroke, finger, penWidth, 0.61f, hand).widths)
                val brush = middle(PenSheet.draw(InkStyle.Brush, stroke, finger, penWidth, 0.61f, hand).widths)
                assertTrue("fountain $name finger=$finger pen=$penWidth: ${spread(fountain)}", spread(fountain) <= 3.2f)
                assertTrue("brush $name finger=$finger pen=$penWidth: ${spread(brush)}", spread(brush) <= 7.5f)
                assertTrue(fountain.max() <= quantile(fountain, 0.5f)!! * 2f)
            }
        }
    }

    @Test fun theChosenWidthIsTheWidthOfOrdinaryWriting() {
        for (style in listOf(InkStyle.Fountain, InkStyle.Brush)) for (finger in listOf(false, true)) {
            val stroke = PenSheet.strokes(finger, 0.61f).getValue("word")
            val w = middle(PenSheet.draw(style, stroke, finger, 2f, 0.61f, hand).widths)
            val median = quantile(w, 0.5f)!!
            assertTrue("$style finger=$finger writes $median pt with a 2 pt pen", median in 1.2f..2.6f)
        }
    }

    @Test fun aShortStrokeWithAThickPenDoesNotSwellOnLift() {
        // 25 pt of line with a 13.8 pt fountain pen: shorter than the old 34.5 pt start taper
        val stroke = PenSheet.strokes(false, 0.61f).getValue("short")
        val line = PenSheet.draw(InkStyle.Fountain, stroke, false, 13.8f, 0.61f, hand)
        // what was under the pen stays: only the end may change on lift, and only to narrow
        for (i in line.widths.indices) assertTrue("point $i grew on lift: ${line.live[i]} -> ${line.widths[i]}", line.widths[i] <= line.live[i] + 1e-4f)
        val untouched = line.widths.size * 6 / 10
        assertEquals(line.live.subList(0, untouched), line.widths.subList(0, untouched))
    }

    @Test fun aDotIsEvenAndNotASpeck() {
        for (style in listOf(InkStyle.Fountain, InkStyle.Brush)) {
            val dot = PenSheet.strokes(false, 0.61f).getValue("dot")
            val w = PenSheet.draw(style, dot, false, 2f, 0.61f, hand).widths
            assertEquals(w.min(), w.max(), 1e-5f)
            assertTrue("$style dot ${w[0]} pt", w[0] >= 2f * PenDials.default(style).minScale)
        }
    }

    @Test fun aFingerKeepsToTheMiddleOfThePen() {
        val d = PenDials.default(InkStyle.Fountain)
        val stroke = PenSheet.strokes(true, 0.61f).getValue("pause")
        val w = PenSheet.draw(InkStyle.Fountain, stroke, true, 4f, 0.61f, hand).live
        // never the pen's thinnest nor its thickest line, however fast or still the finger is
        val span = d.maxScale - d.minScale
        assertTrue(w.drop(w.size / 5).min() >= 4f * (d.minScale + span * 0.1f) * 0.74f) // the nib may thin it
        assertTrue(w.max() <= 4f * (d.maxScale - span * 0.1f))
        // and the stroke does not begin at its thickest: the first tenth is no wider than the middle
        assertTrue(w.take(w.size / 10).max() <= quantile(middle(w), 0.9f)!! * 1.05f)
    }

    @Test fun aSampleWithoutPressureIsNotTheHardestPress() {
        fun widths(gap: Float): List<Float> {
            val dyn = PenDynamics(PenDials.default(InkStyle.Fountain).copy(taperStart = 0f), PressureSource.Pen, PressureRange(), 4f)
            return List(200) { i -> dyn.next(Offset(i * 0.5f, 0f), i * 2L, if (i in 100..104) gap else 0.3f) }
        }
        val steady = widths(0.3f)
        for (gap in listOf(0f, Float.NaN, -1f)) assertEquals(steady, widths(gap))
        // a pen that never gives a pressure writes at the middle of the range
        val dyn = PenDynamics(PenDials(0.5f, 1.5f, dampingMs = 0f), PressureSource.Pen, PressureRange(), 4f)
        assertEquals(1f, dyn.next(Offset.Zero, 0L, 0f), 1e-5f)
    }

    @Test fun aPencilsTailIsAsLightAsItsLine() {
        assertEquals(0.35f, inkTailOpacity(1f, 0.35f), 1e-6f)
        assertEquals(0.4f, inkTailOpacity(0.5f, 0.8f), 1e-6f)
        // solid ink, and numbers that cannot be right, leave the pen's own opacity
        assertEquals(0.7f, inkTailOpacity(0.7f, null), 1e-6f)
        assertEquals(1f, inkTailOpacity(Float.NaN, Float.NaN), 1e-6f)
    }

    @Test fun smoothingDoesNotOvershootACorner() {
        // a right-angle turn: the smoothed line stays inside the corner's box, never past it
        val pts = List(80) { i -> if (i < 40) Offset(i * 0.5f, 0f) else Offset(20f, (i - 40) * 0.5f) }
        val s = InkSmoother(0.5f, 0.61f)
        val out = pts.mapIndexed { i, p -> s.next(p, i * 2L) }
        assertTrue(out.all { it.x <= 20f + 1e-3f && it.y >= -1e-3f && it.y <= 20f + 1e-3f })
    }
}
