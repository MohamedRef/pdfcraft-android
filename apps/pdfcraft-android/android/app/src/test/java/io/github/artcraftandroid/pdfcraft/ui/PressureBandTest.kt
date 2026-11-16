package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureCalibration
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureRange
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkStrokeMeasuresPressure
import io.github.artcraftandroid.pdfcraft.ui.viewer.quantile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** #607: the pressure stretch has no cliff at a band 0.08 wide, which is the owner's band. */
class PressureBandTest {
    @Test fun aBandJustUnderAndJustOverTheOldCliffWriteTheSameLine() {
        val under = PressureRange(0.07f, 0.149f)
        val over = PressureRange(0.07f, 0.151f)
        for (i in 1..100) assertEquals(under.stretch(i / 100f), over.stretch(i / 100f), 0.01f)
        for (style in listOf(InkStyle.Fountain, InkStyle.Brush, InkStyle.Pencil)) {
            val stroke = PenSheet.strokes(false, 0.61f).getValue("word")
            val a = PenSheet.draw(style, stroke, false, 1.5f, 0.61f, under).widths
            val b = PenSheet.draw(style, stroke, false, 1.5f, 0.61f, over).widths
            for (i in a.indices) assertEquals("$style point $i", a[i], b[i], a[i] * 0.02f)
            assertEquals(quantile(a, 0.5f)!!, quantile(b, 0.5f)!!, quantile(a, 0.5f)!! * 0.02f)
        }
    }

    /** A hand whose strokes average 0.07…0.15, a little different each stroke: 400 strokes of it. */
    @Test fun theOwnersBandStaysInUse() {
        val random = Random(607)
        var c = PressureCalibration()
        var switches = 0
        var inUse = false
        var used = 0
        repeat(400) { n ->
            val lo = (0.07 + random.nextGaussian() * 0.008).toFloat().coerceIn(0.01f, 0.5f)
            val hi = (0.15 + random.nextGaussian() * 0.012).toFloat().coerceIn(lo + 0.005f, 0.9f)
            c = c.learn(List(40) { lo + (hi - lo) * it / 39f }, n.toLong())
            val now = c.range != PressureRange()
            if (c.strokes > PressureCalibration.MIN_STROKES && now != inUse) switches++
            inUse = now
            if (now) used++
        }
        assertEquals(0, switches)
        assertEquals(400 - (PressureCalibration.MIN_STROKES - 1), used)
    }

    @Test fun onlyOrdinaryPenWritingMeasuresTheBand() {
        assertTrue(inkStrokeMeasuresPressure(stylus = true, InkStyle.Fountain, trial = false))
        assertTrue(inkStrokeMeasuresPressure(stylus = true, InkStyle.Pencil, trial = false))
        // the marker, the Pen lab's strip and a finger do not
        assertFalse(inkStrokeMeasuresPressure(stylus = true, InkStyle.Highlighter, trial = false))
        assertFalse(inkStrokeMeasuresPressure(stylus = true, InkStyle.Fountain, trial = true))
        assertFalse(inkStrokeMeasuresPressure(stylus = false, InkStyle.Fountain, trial = false))
    }

    @Test fun aPenWithOneFixedPressureIsLeftAlone() {
        var c = PressureCalibration()
        repeat(20) { c = c.learn(List(40) { 1f }, it.toLong()) }
        assertEquals(PressureRange(), c.range)
        assertEquals(0.3f, c.range.stretch(0.3f), 1e-6f)
    }
}
