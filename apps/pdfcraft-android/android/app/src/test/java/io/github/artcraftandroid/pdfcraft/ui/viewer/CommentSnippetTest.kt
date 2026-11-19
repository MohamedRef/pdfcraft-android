package io.github.artcraftandroid.pdfcraft.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #735: a text markup's preview in the Comments list shows what is marked and nothing beside it. */
class CommentSnippetTest {
    // a highlight of "honour the spacing": 120 pt of a 12 pt line on a 612 × 792 page, at 2 px a point
    private val line = listOf(100f, 200f, 220f, 212f)

    @Test
    fun aOneLineMarkIsAsWideAsTheWordsItMarks() {
        val tiles = markupSnippetTiles(line, rect = line, scale = 2f, pageWidth = 612f, pageHeight = 792f, maxWidth = 2000, gap = 8)
        // not to the card's edge (2000 px) or the page's (1224 px): only the marked 120 pt
        assertEquals(listOf(SnippetTile(200, 400, 240, 24)), tiles)
    }

    @Test
    fun aMarkOverTwoLinesShowsTheMarkedPartOfEachInReadingOrder() {
        // the end of one line and the start of the next; the rectangle around both also covers their unmarked ends
        val lines = listOf(400f, 200f, 560f, 212f, 60f, 214f, 150f, 226f)
        val rect = listOf(60f, 200f, 560f, 226f)
        val tiles = markupSnippetTiles(lines, rect, scale = 2f, pageWidth = 612f, pageHeight = 792f, maxWidth = 2000, gap = 8)
        assertEquals(listOf(SnippetTile(800, 400, 320, 24), SnippetTile(120, 428, 180, 24)), tiles)
        assertEquals("lines are shown as tall as the tallest", 12f, markupLineHeight(lines, rect))
    }

    @Test
    fun theStripIsCutToTheCardsWidth() {
        val lines = listOf(50f, 100f, 550f, 112f, 50f, 114f, 550f, 126f)
        val tiles = markupSnippetTiles(lines, lines.take(4), scale = 2f, pageWidth = 612f, pageHeight = 792f, maxWidth = 1500, gap = 10)
        // the first line whole (1000 px), then the gap and what is left of the second
        assertEquals(listOf(SnippetTile(100, 200, 1000, 24), SnippetTile(100, 228, 490, 24)), tiles)
        assertEquals(1500, tiles.sumOf { it.width } + 10 * (tiles.size - 1))
        // no room for even a pixel after the gap: the strip stops there
        assertEquals(1, markupSnippetTiles(lines, lines.take(4), 2f, 612f, 792f, maxWidth = 1005, gap = 10).size)
    }

    @Test
    fun withoutLinesTheMarksOwnRectangleIsUsedAtItsOwnWidth() {
        val tiles = markupSnippetTiles(emptyList(), rect = line, scale = 2f, pageWidth = 612f, pageHeight = 792f, maxWidth = 2000, gap = 8)
        assertEquals(listOf(SnippetTile(200, 400, 240, 24)), tiles)
    }

    @Test
    fun aBoxPastThePageIsKeptOnItAndAnEmptyOneIsLeftOut() {
        val lines = listOf(580f, 780f, 640f, 800f, 300f, 300f, 300f, 312f)
        val tiles = markupSnippetTiles(lines, emptyList(), scale = 1f, pageWidth = 612f, pageHeight = 792f, maxWidth = 2000, gap = 4)
        assertEquals(listOf(SnippetTile(580, 780, 32, 12)), tiles)
        assertTrue(markupSnippetTiles(emptyList(), emptyList(), 1f, 612f, 792f, 2000, 4).isEmpty())
        assertEquals(1f, markupLineHeight(emptyList(), emptyList()))
    }
}
