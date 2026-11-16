package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.PEN_PRESSURE_DEFAULT
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenDials
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureRange
import io.github.artcraftandroid.pdfcraft.ui.viewer.penPressureGain
import io.github.artcraftandroid.pdfcraft.ui.viewer.penPressureSetting
import io.github.artcraftandroid.pdfcraft.ui.viewer.quantile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #608: a light hand's ordinary writing sits around the middle of each pen, and the pen's Pressure
 * setting says how much the line answers pressure. The handwriting is [PenSheet]'s word, made to
 * the owner's log, with the band it measured for his hand (raw pressure 0.07…0.15).
 */
class PenPressureTest {
    private val hand = PressureRange(0.07f, 0.15f)
    private val dpPerPt = 0.61f
    private val pen = 2f

    private fun <T> middle(w: List<T>) = w.subList(w.size / 5, w.size - w.size / 5)

    private fun word(style: InkStyle, setting: Float = PEN_PRESSURE_DEFAULT, densities: MutableList<Float>? = null) =
        middle(PenSheet.draw(style, PenSheet.strokes(false, dpPerPt).getValue("word"), false, pen, dpPerPt, hand, densities = densities, pressureSetting = setting).widths)

    private fun spread(w: List<Float>) = quantile(w, 0.9f)!! / quantile(w, 0.1f)!!

    @Test fun theChosenWidthIsAboutWhatALightHandGets() {
        val median = quantile(word(InkStyle.Fountain), 0.5f)!!
        assertTrue("a $pen pt fountain pen writes $median pt", median in pen * 0.85f..pen * 1.15f)
    }

    @Test fun aBrushIsPlainlyABrushNextToTheFountainPen() {
        val fountain = spread(word(InkStyle.Fountain))
        val brush = spread(word(InkStyle.Brush))
        assertTrue("brush varies $brush times, fountain $fountain", brush >= fountain * 1.3f)
    }

    @Test fun thePencilIsNotPale() {
        val densities = ArrayList<Float>()
        word(InkStyle.Pencil, densities = densities)
        val median = quantile(middle(densities), 0.5f)!!
        assertTrue("the pencil lays down $median of its ink", median >= 0.6f)
    }

    @Test fun thePressureSettingAtItsLowestKeepsOneWidth() {
        for (style in listOf(InkStyle.Ballpoint, InkStyle.Brush)) {
            val w = word(style, 0f)
            assertTrue("$style still varies ${w.max() / w.min()} times", w.max() / w.min() < 1.02f)
        }
        // a fountain pen keeps its flat nib: down thicker than across, and nothing else
        val fountain = word(InkStyle.Fountain, 0f)
        assertTrue(fountain.max() / fountain.min() < 1f / (1f - PenDials.default(InkStyle.Fountain).nib) + 0.02f)
        // a pencil stays a pencil: one density, below solid ink
        val densities = ArrayList<Float>()
        word(InkStyle.Pencil, 0f, densities)
        assertTrue(densities.max() - densities.min() < 1e-4f && densities.max() < 0.9f)
    }

    @Test fun moreOfThePressureSettingIsMoreChangeAroundTheSameWidth() {
        for (style in listOf(InkStyle.Fountain, InkStyle.Brush)) {
            val spreads = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { spread(word(style, it)) }
            assertTrue("$style: $spreads", spreads.zipWithNext().all { (a, b) -> b > a })
            val medians = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { quantile(word(style, it), 0.5f)!! }
            // the brush's curve bends upwards, so more change is also a little more width
            assertTrue("$style writes $medians pt with a $pen pt pen", medians.all { it in pen * 0.75f..pen * 1.25f })
        }
    }

    @Test fun thePressureSettingIsTheSameForAFinger() {
        fun finger(setting: Float) = middle(PenSheet.draw(InkStyle.Brush, PenSheet.strokes(true, dpPerPt).getValue("word"), true, pen, dpPerPt, hand, pressureSetting = setting).widths)
        assertTrue(finger(0f).let { it.max() / it.min() } < 1.02f)
        assertTrue(spread(finger(1f)) > spread(finger(0.5f)))
    }

    @Test fun aStoredPressureSettingIsKeptInsideItsRange() {
        assertEquals(0f, penPressureSetting(-3f), 0f)
        assertEquals(1f, penPressureSetting(7f), 0f)
        assertEquals(0.35f, penPressureSetting(0.34f), 1e-6f)
        assertEquals(PEN_PRESSURE_DEFAULT, penPressureSetting(Float.NaN), 0f)
        assertEquals(1f, penPressureGain(PEN_PRESSURE_DEFAULT), 0f)
        assertEquals(0f, penPressureGain(0f), 0f)
        assertEquals(2f, penPressureGain(1f), 0f)
    }
}
