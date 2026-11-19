package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.core.CommentInfo
import io.github.artcraftandroid.pdfcraft.ui.viewer.MARKUP_ROW_SWITCH_DP
import io.github.artcraftandroid.pdfcraft.ui.viewer.TextMarkup
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerTool
import io.github.artcraftandroid.pdfcraft.ui.viewer.annotateToolName
import io.github.artcraftandroid.pdfcraft.ui.viewer.erasableMarkups
import io.github.artcraftandroid.pdfcraft.ui.viewer.eraserTouchesBox
import io.github.artcraftandroid.pdfcraft.ui.viewer.markupIndicesNow
import io.github.artcraftandroid.pdfcraft.ui.viewer.markupKey
import io.github.artcraftandroid.pdfcraft.ui.viewer.markupQuickColorCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #660: the text markups one tap from the pens and back, and the eraser removing a text markup. */
class AnnotateSwitchTest {
    private fun comment(subtype: String, index: Int, rect: List<Float>, lines: List<Float> = emptyList(), page: Int = 0, locked: Boolean = false, fillSign: Boolean = false, hidden: Boolean = false) = CommentInfo(
        page = page.toUInt(), index = index.toUInt(), subtype = subtype, kind = subtype, author = null, contents = null,
        modified = null, rect = rect, color = null, name = "c$index", replyTo = null, state = null,
        locked = locked, fillSign = fillSign, opacity = 1f, width = null, hidden = hidden, points = emptyList(), lines = lines,
    )

    @Test fun theEraserTakesTextMarkupsButNotOtherComments() {
        val box = listOf(10f, 10f, 100f, 22f)
        val comments = listOf(
            comment("Highlight", 0, box, box),
            comment("Underline", 1, box),
            comment("StrikeOut", 2, box),
            comment("Squiggly", 3, box),
            comment("Ink", 4, box),
            comment("Text", 5, box),
            comment("Highlight", 6, box, locked = true),
            comment("Highlight", 7, box, fillSign = true),
            comment("Highlight", 8, box, hidden = true),
            comment("Highlight", 9, box, page = 1),
        )
        assertEquals(listOf(0, 1, 2, 3), erasableMarkups(comments, 0).map { it.index })
    }

    @Test fun eachLineIsItsOwnBoxSoTheBlankEndOfAShortLineDoesNotCount() {
        // two lines: a long one, then a short one ending at x = 40; the rectangle covers both to x = 100
        val lines = listOf(10f, 10f, 100f, 22f, 10f, 24f, 40f, 36f)
        val m = erasableMarkups(listOf(comment("Highlight", 0, listOf(10f, 10f, 100f, 36f), lines)), 0).single()
        assertEquals(2, m.boxes.size)
        // the eraser in the blank end of the short line, 2 pt wide: no line touched
        assertFalse(m.boxes.any { eraserTouchesBox(Offset(80f, 30f), Offset(90f, 30f), 2f, it) })
        // over the short line itself
        assertTrue(m.boxes.any { eraserTouchesBox(Offset(30f, 30f), Offset(30f, 30f), 2f, it) })
    }

    @Test fun theEraserTouchesABoxWhenItsPathComesWithinItsRadius() {
        val box = floatArrayOf(10f, 10f, 100f, 22f)
        // crossing straight through, without a point inside
        assertTrue(eraserTouchesBox(Offset(50f, 0f), Offset(50f, 40f), 1f, box))
        // passing above, within the radius
        assertTrue(eraserTouchesBox(Offset(0f, 5f), Offset(200f, 5f), 6f, box))
        // passing above, beyond it
        assertFalse(eraserTouchesBox(Offset(0f, 5f), Offset(200f, 5f), 4f, box))
        // a dot (a tap) inside, and one to the side
        assertTrue(eraserTouchesBox(Offset(20f, 15f), Offset(20f, 15f), 1f, box))
        assertFalse(eraserTouchesBox(Offset(120f, 15f), Offset(120f, 15f), 5f, box))
        // a diagonal that misses the corner
        assertFalse(eraserTouchesBox(Offset(95f, 40f), Offset(120f, 25f), 1f, box))
        assertFalse(eraserTouchesBox(Offset(20f, 15f), Offset(20f, 15f), Float.NaN, box))
    }

    @Test fun aMarkupIsFoundAgainIfTheIndicesMovedDuringTheGesture() {
        val a = comment("Highlight", 3, listOf(10f, 10f, 100f, 22f))
        val keys = setOf(markupKey(a))
        // an ink stroke before it went: it is now index 2
        val now = listOf(comment("Highlight", 2, listOf(10f, 10f, 100f, 22f)).copy(name = a.name))
        assertEquals(listOf(2), markupIndicesNow(now, 0, keys))
        assertEquals(emptyList<Int>(), markupIndicesNow(now, 1, keys))
    }

    @Test fun theSwitchIsLoggedWithTheToolLeftAndTaken() {
        assertEquals("pen", annotateToolName(ViewerTool.Draw, TextMarkup.Highlight, eraser = false, lasso = false))
        assertEquals("eraser", annotateToolName(ViewerTool.Draw, TextMarkup.Highlight, eraser = true, lasso = false))
        assertEquals("lasso", annotateToolName(ViewerTool.Draw, TextMarkup.Highlight, eraser = false, lasso = true))
        assertEquals("strikethrough", annotateToolName(ViewerTool.Markup, TextMarkup.Strikethrough, eraser = false, lasso = false))
    }

    @Test fun highlightsRowMakesRoomForThePenAndTheEraser() {
        // a 384 dp phone: the pen and the eraser take the quick colours' room; the colour button stays
        assertEquals(0, markupQuickColorCount(384 - MARKUP_ROW_SWITCH_DP))
        assertEquals(1, markupQuickColorCount(450 - MARKUP_ROW_SWITCH_DP))
        assertEquals(5, markupQuickColorCount(600 - MARKUP_ROW_SWITCH_DP))
    }

    @Test fun drawsBarHasTheFourTextMarkupsAndHighlightsRowHasThePenAndEraser() {
        val viewer = File("src/main/java/io/github/artcraftandroid/pdfcraft/ui/viewer")
        val draw = File(viewer, "DrawToolbar.kt").readText()
        for (m in listOf("Highlight", "Underline", "Strikethrough", "Squiggly")) assertTrue(m, "onMarkup(TextMarkup.$m)" in draw)
        // the sub-toolbars moved to ToolPickers.kt (#730)
        val tools = File(viewer, "ToolPickers.kt").readText()
        assertTrue("onPen = { switchAnnotateTool(vm, doc) }" in tools)
        assertTrue("onEraser = { switchAnnotateTool(vm, doc, eraser = true) }" in tools)
    }
}
