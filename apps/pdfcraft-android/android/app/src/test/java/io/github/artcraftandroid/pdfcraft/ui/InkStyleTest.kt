package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.InkPreset
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkStyleOpacity
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkStyleWidthRuns
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkStyleEngineType
import io.github.artcraftandroid.pdfcraft.ui.viewer.MAX_INK_WIDTH_RUNS
import org.junit.Assert.*
import org.junit.Test

class InkStyleTest {
    @Test fun aConstantStrokeIsOneRunAtThePenWidth() {
        val scales = listOf(0.2f, 0.8f, 0.4f)
        for (style in listOf(InkStyle.Ballpoint, InkStyle.Fountain, InkStyle.Pencil, InkStyle.Brush)) {
            assertEquals(4f, inkStyleWidthRuns(scales, 4f, false, style).single().width, 0.001f)
        }
        // the marker is broader, and constant even when the stroke would vary
        assertEquals(9f, inkStyleWidthRuns(scales, 4f, true, InkStyle.Highlighter).single().width, 0.001f)
    }

    @Test fun aVaryingStrokeSplitsWhereItsWidthChanges() {
        val scales = listOf(0.2f, 0.2f, 0.2f, 1f, 1f, 1f)
        val runs = inkStyleWidthRuns(scales, 5f, true, InkStyle.Fountain)
        assertTrue(runs.size > 1)
        assertTrue(runs.first().width < runs.last().width)
        assertEquals(5f, runs.last().width, 0.001f)
    }

    @Test fun styledInkStillHasBoundedNativeWidthRuns() {
        val scales = List(4097) { if (it % 8 < 4) 0.1f else 0.95f }
        for (style in InkStyle.entries) {
            val runs = inkStyleWidthRuns(scales, 8f, true, style)
            assertTrue(runs.size in 1..MAX_INK_WIDTH_RUNS)
            assertEquals(0, runs.first().start)
            assertEquals(scales.lastIndex, runs.last().end)
            assertTrue(runs.all { it.width.isFinite() && it.width > 0f })
        }
    }

    @Test fun lowOpacityAndEngineHighlightAreExplicit() {
        assertEquals(0.4f, inkStyleOpacity(1f, InkStyle.Highlighter), 0.00001f)
        assertEquals(0.4f, inkStyleOpacity(0.5f, InkStyle.Pencil), 0.00001f)
        assertEquals("freehand_highlight", inkStyleEngineType(InkStyle.Highlighter))
        assertEquals("ink", inkStyleEngineType(InkStyle.Brush))
        assertEquals(1f, inkStyleOpacity(Float.NaN, InkStyle.Ballpoint), 0.00001f)
    }

    @Test fun legacyFavoritesDecodeAndNewStyleFavoritesRoundTrip() {
        val legacy = InkPreset.decode("v1|ff0000ff|3.0|0.75")
        assertEquals(InkStyle.Ballpoint, legacy?.style)
        for (style in InkStyle.entries) {
            val preset = InkPreset.create(0xff13579b.toInt(), 3f, 0.65f, style)
            assertNotNull(preset)
            assertEquals(preset, InkPreset.decode(preset!!.encode()))
        }
        assertNull(InkPreset.decode("v2|ff0000ff|2.0|0.5|nonexistent"))
        assertNull(InkPreset.decode("v2|ff0000ff|NaN|0.5|brush"))
        assertNull(InkPreset.decode("v3|ff0000ff|2.0|0.5|brush"))
        assertNull(InkPreset.decode("v2|ff0000ff|2.0|0.5|brush|extra"))
    }
}
