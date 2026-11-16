package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.core.TextRange
import io.github.artcraftandroid.pdfcraft.ui.viewer.MAX_MARKUP_GLYPHS
import io.github.artcraftandroid.pdfcraft.ui.viewer.MAX_MARKUP_LINE_RECTS
import io.github.artcraftandroid.pdfcraft.ui.viewer.markupLineRects
import io.github.artcraftandroid.pdfcraft.ui.viewer.MARKUP_SIDE_REACH_PT
import io.github.artcraftandroid.pdfcraft.ui.viewer.markupLetterRange
import io.github.artcraftandroid.pdfcraft.ui.viewer.markupNearLine
import io.github.artcraftandroid.pdfcraft.ui.viewer.CommentPalette
import io.github.artcraftandroid.pdfcraft.ui.viewer.MARKUP_QUICK_COLORS
import io.github.artcraftandroid.pdfcraft.ui.viewer.followUpBarTop
import io.github.artcraftandroid.pdfcraft.ui.viewer.markupBounds
import io.github.artcraftandroid.pdfcraft.ui.viewer.markupQuickColorCount
import io.github.artcraftandroid.pdfcraft.ui.viewer.toArgbInt
import org.junit.Assert.*
import org.junit.Test

class MarkupSelectionTest {
    // ---- #657: a drag marks the letters it covered, not whole words

    @Test fun aLineWithNoHeightOrWidthIsLeftOutNotTheWholeSelection() {
        // three lines; the middle one is only a space with no width, one has no height (#657)
        val rects = markupLineRects(listOf(10f, 20f, 120f, 35f, 50f, 40f, 50f, 55f, 10f, 60f, 90f, 60f, 10f, 80f, 75f, 95f))
        assertEquals(2, rects.size)
        assertArrayEquals(floatArrayOf(10f, 20f, 120f, 35f), rects[0], 0f)
        assertArrayEquals(floatArrayOf(10f, 80f, 75f, 95f), rects[1], 0f)
    }

    @Test fun aDragMarksFromItsFirstLetterToItsLastInEitherDirection() {
        // forward: glyph 12 (inside a word) to glyph 41 (inside another word, lines later)
        assertEquals(TextRange(3u, 12u, 42u), markupLetterRange(3u, 12u, 41u))
        // backward, upward or right to left: the same letters
        assertEquals(TextRange(3u, 12u, 42u), markupLetterRange(3u, 41u, 12u))
        // one letter
        assertEquals(TextRange(0u, 7u, 8u), markupLetterRange(0u, 7u, 7u))
    }

    @Test fun anOversizedLetterRangeIsRefused() {
        assertNull(markupLetterRange(0u, 0u, MAX_MARKUP_GLYPHS))
        assertEquals(TextRange(0u, 0u, MAX_MARKUP_GLYPHS), markupLetterRange(0u, 0u, MAX_MARKUP_GLYPHS - 1u))
    }

    @Test fun wordRangesKeepMultilinePdfQuadsInOrder() {
        val data = listOf(10f, 20f, 120f, 35f, 10f, 40f, 75f, 55f)
        val rects = markupLineRects(data)
        assertEquals(2, rects.size)
        assertArrayEquals(floatArrayOf(10f, 20f, 120f, 35f), rects[0], 0f)
        assertArrayEquals(floatArrayOf(10f, 40f, 75f, 55f), rects[1], 0f)
    }

    @Test fun invalidAndOversizedQuadsAreRejectedWithoutPartialHighlight() {
        assertTrue(markupLineRects(emptyList()).isEmpty())
        assertTrue(markupLineRects(listOf(1f, 2f, 3f)).isEmpty())
        assertTrue(markupLineRects(listOf(1f, 2f, Float.NaN, 5f)).isEmpty())
        assertTrue(markupLineRects(listOf(1f, 2f, 1f, 4f)).isEmpty())
        assertTrue(markupLineRects(listOf(1f, 2f, 4f, 5f, 1f, 2f, Float.POSITIVE_INFINITY, 5f)).isEmpty())
        assertTrue(markupLineRects(List((MAX_MARKUP_LINE_RECTS + 1) * 4) { it.toFloat() }).isEmpty())
    }
    @Test fun aDragBesideALineTakesItsNearestLetterButNotAFarOne() {
        val glyph = listOf(72f, 100f, 78f, 112f) // first letter of a line, 72 pt from the page edge
        val reach = MARKUP_SIDE_REACH_PT
        // in the left margin, level with the line
        assertTrue(markupNearLine(10f, 106f, glyph, reach, 9f))
        // just above or below the line, as between two lines
        assertTrue(markupNearLine(75f, 95f, glyph, reach, 9f))
        assertTrue(markupNearLine(75f, 118f, glyph, reach, 9f))
        // too far above or below: another paragraph, blank space under the text
        assertFalse(markupNearLine(75f, 80f, glyph, reach, 9f))
        assertFalse(markupNearLine(75f, 140f, glyph, reach, 9f))
        // beyond the reach sideways: another column
        assertFalse(markupNearLine(200f, 106f, glyph, reach, 9f))
        // invalid input
        assertFalse(markupNearLine(Float.NaN, 106f, glyph, reach, 9f))
        assertFalse(markupNearLine(75f, 106f, glyph, reach, Float.NaN))
        assertFalse(markupNearLine(75f, 106f, glyph, -1f, 9f))
        assertFalse(markupNearLine(75f, 106f, glyph.take(3), reach, 9f))
        assertFalse(markupNearLine(75f, 106f, listOf(72f, Float.POSITIVE_INFINITY, 78f, 112f), reach, 9f))
        assertFalse(markupNearLine(75f, 106f, listOf(72f, 100f, 72f, 112f), reach, 9f))
        // a glyph box given right to left or bottom to top
        assertTrue(markupNearLine(75f, 106f, listOf(78f, 112f, 72f, 100f), reach, 9f))
    }

    // ---- #650: the markup just made stays in hand, and colours are a tap away

    @Test fun aMarkupsBoundsCoverAllItsLines() {
        val b = markupBounds(listOf(floatArrayOf(72f, 100f, 300f, 112f), floatArrayOf(40f, 114f, 180f, 126f)))
        assertArrayEquals(floatArrayOf(40f, 100f, 300f, 126f), b, 0f)
        assertNull(markupBounds(emptyList()))
        assertNull(markupBounds(listOf(floatArrayOf(1f, 2f, Float.NaN, 4f))))
        assertNull(markupBounds(listOf(floatArrayOf(1f, 2f, 3f))))
    }

    @Test fun afterTheSelectionMenuTheBarGoesBelowWhenItFits() {
        // a markup from 400 to 430 px, a 96 px bar, 24 px apart, the page area from 120 to 1600
        assertEquals(454f, followUpBarTop(400f, 430f, 96f, 24f, 120f, 1600f, preferAbove = false), 0f)
        // near the foot there is no room below: above
        assertEquals(1380f, followUpBarTop(1500f, 1530f, 96f, 24f, 120f, 1600f, preferAbove = false), 0f)
    }

    @Test fun inTheHighlightToolTheBarGoesAboveClearOfTheLinesBelow() {
        assertEquals(280f, followUpBarTop(400f, 430f, 96f, 24f, 120f, 1600f, preferAbove = true), 0f)
        // just under the top bar there is no room above: below
        assertEquals(214f, followUpBarTop(160f, 190f, 96f, 24f, 120f, 1600f, preferAbove = true), 0f)
    }

    @Test fun theBarNeverLeavesThePageArea() {
        // a markup scrolled above the page area, or below it
        assertEquals(120f, followUpBarTop(-500f, -470f, 96f, 24f, 120f, 1600f, preferAbove = false), 0f)
        assertEquals(1504f, followUpBarTop(2400f, 2430f, 96f, 24f, 120f, 1600f, preferAbove = true), 0f)
        // a page area shorter than the bar: its top, never a place above it
        assertEquals(120f, followUpBarTop(130f, 140f, 96f, 24f, 120f, 180f, preferAbove = true), 0f)
    }

    @Test fun quickColoursAreAsManyAsTheRowHasRoomFor() {
        val count = ::markupQuickColorCount
        assertEquals(0, count(300))
        assertEquals(1, count(320))
        assertEquals(2, count(360))
        assertEquals(3, count(384))
        assertEquals(5, count(600))
        assertEquals(5, count(1280))
        assertEquals(0, count(0))
    }

    @Test fun everyQuickColourIsANamedColourOfThePalette() {
        val palette = CommentPalette.map { it.first.toArgbInt() }
        for (argb in MARKUP_QUICK_COLORS) assertTrue("%08X".format(argb), argb.toInt() in palette)
        assertEquals(5, MARKUP_QUICK_COLORS.toSet().size)
    }
}
