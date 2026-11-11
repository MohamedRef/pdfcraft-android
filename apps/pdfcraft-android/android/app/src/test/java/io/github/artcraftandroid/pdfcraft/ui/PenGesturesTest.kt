package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenGesture
import io.github.artcraftandroid.pdfcraft.ui.viewer.TextMarkup
import io.github.artcraftandroid.pdfcraft.ui.viewer.classifyPenGesture
import io.github.artcraftandroid.pdfcraft.ui.viewer.lineGestureMarkup
import io.github.artcraftandroid.pdfcraft.ui.viewer.scratchCovers
import io.github.artcraftandroid.pdfcraft.ui.viewer.scratchCoversRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PenGesturesTest {
    @Test
    fun zigZagWithFourReversalsIsAScratchOut() {
        val zig = (0..8).map { i -> Offset(100f + if (i % 2 == 0) 0f else 30f, 200f + i * 1.5f) }
        assertEquals(PenGesture.ScratchOut, classifyPenGesture(zig))
        val two = (0..2).map { i -> Offset(100f + if (i % 2 == 0) 0f else 30f, 200f + i) }
        assertEquals(PenGesture.None, classifyPenGesture(two))
    }

    @Test
    fun straightHorizontalStrokeIsALineButHandwritingIsNot() {
        val line = (0..20).map { i -> Offset(100f + i * 5f, 300f + (i % 2) * 0.6f) }
        assertEquals(PenGesture.Line, classifyPenGesture(line))
        val steep = (0..20).map { i -> Offset(100f + i * 5f, 300f + i * 2f) }
        assertEquals(PenGesture.None, classifyPenGesture(steep))
        val curve = (0..20).map { i -> Offset(100f + i * 5f, 300f + (i - 10) * (i - 10) * 0.2f) }
        assertEquals(PenGesture.None, classifyPenGesture(curve))
        assertEquals(PenGesture.None, classifyPenGesture(listOf(Offset(0f, 0f), Offset(5f, 0f))))
    }

    @Test
    fun lineOverTextHighlightsAndJustBelowUnderlines() {
        assertEquals(TextMarkup.Highlight, lineGestureMarkup(marker = true, strokeY = 105f, lineTop = 100f, lineBottom = 112f))
        assertNull(lineGestureMarkup(marker = true, strokeY = 116f, lineTop = 100f, lineBottom = 112f))
        assertEquals(TextMarkup.Underline, lineGestureMarkup(marker = false, strokeY = 114f, lineTop = 100f, lineBottom = 112f))
        assertNull(lineGestureMarkup(marker = false, strokeY = 120f, lineTop = 100f, lineBottom = 112f))
        assertNull(lineGestureMarkup(marker = false, strokeY = 105f, lineTop = 100f, lineBottom = 112f))
    }

    @Test
    fun scratchDeletesOnlyInkMostlyUnderIt() {
        val area = Rect(100f, 100f, 200f, 140f)
        assertTrue(scratchCovers(area, listOf(Offset(110f, 110f), Offset(150f, 120f), Offset(190f, 130f))))
        assertFalse(scratchCovers(area, listOf(Offset(110f, 110f), Offset(250f, 120f), Offset(290f, 130f))))
        assertTrue(scratchCoversRect(area, listOf(120f, 105f, 180f, 135f)))
        assertFalse(scratchCoversRect(area, listOf(150f, 105f, 400f, 135f)))
    }
}
