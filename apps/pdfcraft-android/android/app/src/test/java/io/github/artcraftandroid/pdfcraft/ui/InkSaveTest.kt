package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkAppearance
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkSaveStroke
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.MAX_PRESSURE_INK_POINTS
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkSaveCommandGroups
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkSaveCommands
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkStylePointWidths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkSaveTest {
    private val pen = InkAppearance(0xFF0000FF.toInt(), 0.8f, 4f, "me", InkStyle.Pencil)
    private fun line(n: Int) = List(n) { Offset(it.toFloat(), 10f) }
    private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

    @Suppress("UNCHECKED_CAST")
    private fun widths(c: Map<String, Any?>) = c["widths"] as List<List<Double>>

    @Test fun aPressureStrokeIsOneAnnotationWithAWidthPerPoint() {
        val pressures = listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f, 1f)
        val cmds = inkSaveCommands(listOf(InkSaveStroke(0, pen, line(6), pressures, true)), ::hex)
        assertEquals(1, cmds.size)
        val w = widths(cmds[0])
        assertEquals(1, w.size)
        assertEquals(6, w[0].size)
        assertTrue("widths grow with pressure", w[0].zipWithNext().all { (a, b) -> b > a })
        assertEquals(inkStylePointWidths(pressures, 4f, InkStyle.Pencil).map { it.toDouble() }, w[0])
        assertEquals("ink", cmds[0]["type"])
        assertEquals(1, cmds[0]["page"])
    }

    @Test fun adjacentPressureStrokesOfOneAppearanceShareAnAnnotation() {
        val a = InkSaveStroke(0, pen, line(3), listOf(0.2f, 0.5f, 1f), true)
        val b = InkSaveStroke(0, pen.copy(width = 6f), line(3), listOf(1f, 0.5f, 0.2f), true)
        val other = InkSaveStroke(1, pen, line(3), listOf(1f, 1f, 1f), true)
        val cmds = inkSaveCommands(listOf(a, b, other), ::hex)
        assertEquals(2, cmds.size)
        assertEquals(2, widths(cmds[0]).size)
        assertEquals(2, cmds[1]["page"])
    }

    @Test fun touchStrokesAndTheHighlighterKeepTheirConstantWidth() {
        val touch = InkSaveStroke(0, pen, line(4), List(4) { 0.5f }, false)
        val marker = InkSaveStroke(0, pen.copy(style = InkStyle.Highlighter), line(4), List(4) { 0.5f }, true)
        val cmds = inkSaveCommands(listOf(touch, marker), ::hex)
        assertEquals(2, cmds.size)
        assertFalse(cmds.any { it.containsKey("widths") })
        assertEquals("freehand_highlight", cmds[1]["type"])
    }

    @Test fun aPressureStrokeAndAConstantOneAreNotMixed() {
        val press = InkSaveStroke(0, pen, line(3), listOf(0.2f, 0.6f, 1f), true)
        val touch = InkSaveStroke(0, pen, line(3), List(3) { 1f }, false)
        val cmds = inkSaveCommands(listOf(press, touch), ::hex)
        assertEquals(2, cmds.size)
        assertTrue(cmds[0].containsKey("widths"))
        assertNull(cmds[1]["widths"])
    }

    @Test fun aVeryLongStrokeIsSplitContinuously() {
        val n = MAX_PRESSURE_INK_POINTS * 2 + 10
        val cmds = inkSaveCommands(listOf(InkSaveStroke(0, pen, line(n), List(n) { 0.5f }, true)), ::hex)
        assertEquals(1, cmds.size)
        @Suppress("UNCHECKED_CAST")
        val strokes = cmds[0]["strokes"] as List<List<List<Double>>>
        assertEquals(3, strokes.size)
        assertTrue(strokes.all { it.size <= MAX_PRESSURE_INK_POINTS })
        for (i in 1 until strokes.size) assertEquals(strokes[i - 1].last(), strokes[i].first())
        assertEquals(strokes.map { it.size }, widths(cmds[0]).map { it.size })
    }

    @Test fun invalidPressureNeverMakesAZeroWidth() {
        val w = inkStylePointWidths(listOf(Float.NaN, 0f, -1f, 2f), 4f, InkStyle.Fountain)
        assertTrue(w.all { it.isFinite() && it > 0f })
    }

    /** #72: one undo step per stroke, however the drafts are saved (eraser, pause, Done). */
    @Test fun everyStrokeIsSavedAsItsOwnGroupOfCommands() {
        val a = InkSaveStroke(0, pen, line(3), listOf(0.2f, 0.5f, 1f), true)
        val b = InkSaveStroke(0, pen, line(3), listOf(1f, 0.5f, 0.2f), true)
        val c = InkSaveStroke(0, pen, line(3), List(3) { 1f }, false)
        val groups = inkSaveCommandGroups(listOf(a, b, c), ::hex)
        assertEquals(3, groups.size)
        assertTrue(groups.all { it.size == 1 })
        @Suppress("UNCHECKED_CAST")
        assertTrue(groups.all { g -> (g[0]["strokes"] as List<*>).size == 1 })
        // The commands themselves are what saving these strokes one at a time always gave.
        assertEquals(listOf(a, b, c).map { inkSaveCommands(listOf(it), ::hex) }, groups)
    }

    @Test fun aVeryLongStrokeStaysOneGroup() {
        val n = MAX_PRESSURE_INK_POINTS * 2 + 10
        val groups = inkSaveCommandGroups(listOf(InkSaveStroke(0, pen, line(n), List(n) { 0.5f }, true)), ::hex)
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].size)
    }
}
