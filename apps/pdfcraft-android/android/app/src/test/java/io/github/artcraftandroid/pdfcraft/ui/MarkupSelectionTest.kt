package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.core.TextRange
import io.github.artcraftandroid.pdfcraft.ui.viewer.MAX_MARKUP_GLYPHS
import io.github.artcraftandroid.pdfcraft.ui.viewer.MAX_MARKUP_LINE_RECTS
import io.github.artcraftandroid.pdfcraft.ui.viewer.markupLineRects
import io.github.artcraftandroid.pdfcraft.ui.viewer.markupWordRange
import io.github.artcraftandroid.pdfcraft.ui.viewer.markupEndpointNearGlyph
import io.github.artcraftandroid.pdfcraft.ui.viewer.CommentPalette
import io.github.artcraftandroid.pdfcraft.ui.viewer.MARKUP_QUICK_COLORS
import io.github.artcraftandroid.pdfcraft.ui.viewer.followUpBarTop
import io.github.artcraftandroid.pdfcraft.ui.viewer.markupBounds
import io.github.artcraftandroid.pdfcraft.ui.viewer.markupQuickColorCount
import io.github.artcraftandroid.pdfcraft.ui.viewer.toArgbInt
import org.junit.Assert.*
import org.junit.Test

class MarkupSelectionTest {
    @Test fun tappingMarksWholeWord() {
        val word = TextRange(0u, 14u, 20u)
        assertEquals(word, markupWordRange(word, word))
    }

    @Test fun forwardAndReverseDraggingSelectEntireWordsOnOnePage() {
        val first = TextRange(3u, 10u, 13u)
        val last = TextRange(3u, 40u, 47u)
        val expected = TextRange(3u, 10u, 47u)
        assertEquals(expected, markupWordRange(first, last))
        assertEquals(expected, markupWordRange(last, first))
    }

    @Test fun pageCrossingAndInvalidEndpointsNeverProduceASelection() {
        val word = TextRange(0u, 10u, 12u)
        assertNull(markupWordRange(word, TextRange(1u, 20u, 25u)))
        assertNull(markupWordRange(word, null))
        assertNull(markupWordRange(TextRange(0u, 8u, 8u), word))
        assertNull(markupWordRange(word, TextRange(0u, 5u, 4u)))
        assertNull(markupWordRange(TextRange(0u, 0u, 1u), TextRange(0u, MAX_MARKUP_GLYPHS, MAX_MARKUP_GLYPHS + 2u)))
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
        assertTrue(markupLineRects(listOf(1f, 2f, 4f, 4f, 1f, 2f, Float.POSITIVE_INFINITY, 5f)).isEmpty())
        assertTrue(markupLineRects(List((MAX_MARKUP_LINE_RECTS + 1) * 4) { it.toFloat() }).isEmpty())
    }
    @Test fun smallOutsideLineDragSnapsButDistantOrInvalidTargetsDoNot() {
        val glyph = listOf(50f, 100f, 62f, 118f)
        assertTrue(markupEndpointNearGlyph(63f, 110f, glyph, 4f))
        assertTrue(markupEndpointNearGlyph(54f, 120f, glyph, 4f))
        assertFalse(markupEndpointNearGlyph(90f, 110f, glyph, 4f))
        assertFalse(markupEndpointNearGlyph(54f, 160f, glyph, 4f))
        assertFalse(markupEndpointNearGlyph(Float.NaN, 110f, glyph, 4f))
        assertFalse(markupEndpointNearGlyph(54f, 110f, glyph, Float.NaN))
        assertFalse(markupEndpointNearGlyph(54f, 110f, glyph, -1f))
        assertFalse(markupEndpointNearGlyph(54f, 110f, glyph.take(3), 4f))
        assertFalse(markupEndpointNearGlyph(54f, 110f, listOf(50f, Float.POSITIVE_INFINITY, 62f, 118f), 4f))
        assertTrue(markupEndpointNearGlyph(54f, 110f, listOf(62f, 100f, 50f, 118f), 4f))
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
