package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.core.TextRange
import io.github.artcraftandroid.pdfcraft.ui.viewer.MAX_MARKUP_GLYPHS
import io.github.artcraftandroid.pdfcraft.ui.viewer.MAX_MARKUP_LINE_RECTS
import io.github.artcraftandroid.pdfcraft.ui.viewer.markupLineRects
import io.github.artcraftandroid.pdfcraft.ui.viewer.markupWordRange
import io.github.artcraftandroid.pdfcraft.ui.viewer.markupEndpointNearGlyph
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
        assertFalse(markupEndpointNearGlyph(54f, 110f, listOf(62f, 100f, 50f, 118f), 4f).not())
    }

}
