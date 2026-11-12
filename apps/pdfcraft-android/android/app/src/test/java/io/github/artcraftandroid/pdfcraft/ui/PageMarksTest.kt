package io.github.artcraftandroid.pdfcraft.ui

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMarksTest {
    private val day = LocalDate.of(2026, 3, 7)

    @Test fun pageRanges() {
        assertEquals(listOf(1, 2, 3, 5), parsePageRange("1-3, 5", 9))
        assertEquals(listOf(8, 9), parsePageRange("8-", 9))
        assertEquals(listOf(1, 2), parsePageRange("-2", 9))
        assertEquals(listOf(3, 2, 1), parsePageRange("3-1", 9))
        assertEquals(listOf(2, 3), parsePageRange("2,2,3", 9))
        assertNull(parsePageRange("0", 9))
        assertNull(parsePageRange("4-10", 9))
        assertNull(parsePageRange("x", 9))
        assertNull(parsePageRange(" , ", 9))
    }

    @Test fun tokensExpandAsTheEngineDoes() {
        // the bridge test page_marks_are_listed_updated_and_removed sees the same on pages 2 and 3
        assertEquals("Page 5 of 3", expandMarkTokens("<<Page 1 of n>>", 0, 3, 5, day))
        assertEquals("Page 6 of 3", expandMarkTokens("<<Page 1 of n>>", 1, 3, 5, day))
        assertEquals("2/9 · 3/7/2026 · 2026-03-07 · 07.03.2026", expandMarkTokens("<<1/n>> · <<m/d/yyyy>> · <<yyyy-mm-dd>> · <<dd.mm.yyyy>>", 1, 9, 1, day))
        assertEquals("March 7, 2026", expandMarkTokens("<<mmmm d, yyyy>>", 0, 1, 1, day))
        assertEquals("ABC000012-X", expandMarkTokens("<<Bates Number#6#10#ABC#-X>>", 2, 9, 1, day))
        assertEquals("000003", expandMarkTokens("<<Bates Number#6#1##>>", 2, 9, 1, day))
        // not tokens: left as written, and a later token is still found
        assertEquals("<3>", expandMarkTokens("<<<1>>>", 2, 9, 1, day))
        assertEquals("<<x>> 3", expandMarkTokens("<<x>> <<1>>", 2, 9, 1, day))
        assertEquals("<<1", expandMarkTokens("<<1", 0, 9, 1, day))
    }

    @Test fun headerFooterArgs() {
        val spec = HeaderFooterSpec(slots = listOf("", "", "", "", "<<1>>", ""), font = MarkFont.Times, startNumber = 0)
        assertTrue(spec.usable)
        assertFalse(HeaderFooterSpec(slots = List(6) { " " }).usable)
        val a = markArgs(spec, listOf(2, 3), replace = true)
        assertEquals("<<1>>", a["footer_center"])
        assertFalse(a.containsKey("header_left"))
        assertEquals("Times", a["font"])
        assertEquals(1, a["start_number"])
        assertEquals(listOf(36.0, 36.0, 72.0, 72.0), a["margins"])
        assertEquals(listOf(2, 3), a["pages"])
        assertEquals(true, a["replace"])
        assertFalse(markArgs(spec, null, replace = false).containsKey("pages"))
        assertFalse(markArgs(spec, null, replace = false).containsKey("replace"))
    }

    @Test fun watermarkAndBackgroundArgs() {
        val text = markArgs(WatermarkSpec(text = " DRAFT ", opacity = 1.5f), null, false)
        assertEquals("DRAFT", text["text"])
        assertEquals(1.0, text["opacity"])
        assertFalse(text.containsKey("file"))
        assertFalse(text.containsKey("scale"))
        val picture = markArgs(WatermarkSpec(imagePath = "/x/logo.png", scale = 0.3f), null, false)
        assertEquals("/x/logo.png", picture["file"])
        assertFalse(picture.containsKey("text"))
        assertFalse(WatermarkSpec(text = "  ").usable)
        val colour = markArgs(BackgroundSpec(color = "#FFFFCC"), listOf(1), false)
        assertEquals("#FFFFCC", colour["color"])
        assertFalse(colour.containsKey("file"))
        val image = markArgs(BackgroundSpec(imagePath = "/x/paper.jpg", scale = 2f), null, false)
        assertEquals("/x/paper.jpg", image["file"])
        assertEquals(1.0, image["scale"])
        assertFalse(image.containsKey("color"))
    }
}
