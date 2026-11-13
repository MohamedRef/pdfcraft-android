package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.ScanPage
import io.github.artcraftandroid.pdfcraft.ui.scanTestPageJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Colours in the scan's Markup (#503): any colour, the eyedropper's mean, and the eraser's three fills. */
class ScanMarkupColoursTest {
    private val point = floatArrayOf(0.5f, 0.5f, 1f)

    @Test
    fun theNewFillsAndAnyColourSurviveTheirLine() {
        val marks = ScanMarks(
            0,
            listOf(
                ScanStrokeMark(ScanMarkTool.EraseColour, 0x3A7F2C, 0.04f, point),
                ScanStrokeMark(ScanMarkTool.EraseEdge, 0, 0.08f, point),
                ScanStrokeMark(ScanMarkTool.Pen, 0x123456, 0.005f, point),
            ),
            listOf(ScanTextBox("x", 0.5f, 0.5f, 0.026f, 0xABCDEF)),
        )
        val line = marks.encode()
        assertEquals(marks, ScanMarks.decode(line))
        // a stroke whose tool a build does not know is left out, and the others kept (as a build from before #503 does with `c` and `g`)
        val unknown = line.replace("|c:", "|z:")
        assertEquals(listOf(ScanMarkTool.EraseEdge, ScanMarkTool.Pen), ScanMarks.decode(unknown)!!.strokes.map { it.tool })
        assertEquals(setOf('e', 'c', 'g', 'p', 'h'), ScanMarkTool.entries.map { it.letter }.toSet())
        assertTrue(ScanMarkTool.entries.filter { it.isEraser }.containsAll(listOf(ScanMarkTool.Erase, ScanMarkTool.EraseColour, ScanMarkTool.EraseEdge)))
    }

    @Test
    fun customColoursAndFillsAreCountedLoggedAndKept() {
        val marks = ScanMarks(
            0,
            listOf(
                ScanStrokeMark(ScanMarkTool.Erase, 0, 0.04f, point),
                ScanStrokeMark(ScanMarkTool.EraseColour, 0xFFFFFF, 0.04f, point),
                ScanStrokeMark(ScanMarkTool.EraseColour, 0x3A7F2C, 0.04f, point),
                ScanStrokeMark(ScanMarkTool.EraseEdge, 0, 0.04f, point),
                ScanStrokeMark(ScanMarkTool.Pen, 0x000000, 0.005f, point),
                ScanStrokeMark(ScanMarkTool.Pen, 0x123456, 0.005f, point),
                ScanStrokeMark(ScanMarkTool.Highlighter, 0xFFEB3B, 0.025f, point),
            ),
            listOf(ScanTextBox("a", 0.5f, 0.5f, 0.026f, 0xC62828), ScanTextBox("b", 0.5f, 0.5f, 0.026f, 0xABCDEF)),
        )
        val c = marks.counts
        assertEquals(4, c.erase)
        assertEquals(2, c.eraseColour)
        assertEquals(1, c.eraseEdge)
        // the green Color fill, the pen in #123456 and the text in #ABCDEF are not their tool's own swatches
        assertEquals(3, c.customColours)
        val done = scanMarkupDoneDiag(0, marks, 0.1f, 900L, cancelled = false, changed = true, eyedropper = 2, eraseFill = "edge").toMap()
        assertEquals(3, done["custom_colours"])
        assertEquals(2, done["eyedropper"])
        assertEquals(2, done["erase_colour"])
        assertEquals(1, done["erase_edge"])
        assertEquals("edge", done["erase_fill"])
        val line = scanMarksDiag(true, marks, 0.1f, 20L).toMap()
        assertEquals(2, line["marks_erase_colour"])
        assertEquals(1, line["marks_erase_edge"])
        assertEquals(3, line["marks_custom_colours"])
        val kept = JSONObject(scanTestPageJson("1.0 (1)", true, ScanPage.WHOLE, null, null, "auto", 60, false, ScanLab(), null, null, marks = c.json(0.1, eyedropper = 2))).getJSONObject("marks")
        assertEquals(1, kept.getInt("erase_paper"))
        assertEquals(2, kept.getInt("erase_colour"))
        assertEquals(1, kept.getInt("erase_edge"))
        assertEquals(3, kept.getInt("custom_colours"))
        assertEquals(2, kept.getInt("eyedropper"))
        // counts only: no colour is written
        assertFalse(kept.toString().contains("3a7f2c", ignoreCase = true))
    }

    @Test
    fun theEyedropperTakesTheMeanOfADisc() {
        // a 10 × 10 picture: left half red, right half blue
        val w = 10
        val px = IntArray(w * w) { i -> if (i % w < 5) 0xFFC80000.toInt() else 0xFF0000C8.toInt() }
        assertEquals(0xC80000, scanDiscMean(px, w, w, 2f, 5f, 1.5f))
        assertEquals(0x0000C8, scanDiscMean(px, w, w, 8f, 5f, 1.5f))
        // across the border, half of each
        assertEquals(0x640064, scanDiscMean(px, w, w, 5f, 5f, 2f))
        // a disc partly off the picture counts only what is on it; one wholly off is nothing
        assertEquals(0xC80000, scanDiscMean(px, w, w, 0f, 0f, 2f))
        assertNull(scanDiscMean(px, w, w, -20f, -20f, 2f))
        assertNull(scanDiscMean(px, w, w, Float.NaN, 5f, 2f))
        assertNull(scanDiscMean(IntArray(3), w, w, 5f, 5f, 2f))
        assertEquals(1.5f, scanDropperRadius(100, 200))
        assertEquals(12f, scanDropperRadius(3000, 2000))
    }

    @Test
    fun theRowShowsTheColourInUseAndEraseOffersItsFills() {
        // a colour from the sheet or the page joins the row, so it is seen
        assertEquals(scanMarkupColours(ScanMarkupTool.Pen) + 0x123456, scanMarkupSwatches(ScanMarkupTool.Pen, ScanEraseFill.Paper, 0x123456, false))
        assertEquals(scanMarkupColours(ScanMarkupTool.Pen), scanMarkupSwatches(ScanMarkupTool.Pen, ScanEraseFill.Paper, 0x1565C0, false))
        assertTrue(scanMarkupSwatches(ScanMarkupTool.Erase, ScanEraseFill.Paper, 0x123456, false).isEmpty())
        assertEquals(listOf(0xFFFFFF, 0x000000, 0x123456), scanMarkupSwatches(ScanMarkupTool.Erase, ScanEraseFill.Colour, 0x123456, false))
        // a black-and-white page: Erase's colour is black or paper only
        assertEquals(SCAN_ERASE_COLOURS, scanMarkupSwatches(ScanMarkupTool.Erase, ScanEraseFill.Colour, 0x123456, true))
        assertEquals(ScanEraseFill.entries, scanEraseFills(ScanLab()))
        assertEquals(listOf(ScanEraseFill.Paper, ScanEraseFill.Edge), scanEraseFills(ScanLab(markupColours = false)))
        assertEquals(listOf(ScanEraseFill.Paper), scanEraseFills(ScanLab(markupColours = false, markupEdge = false)))
        assertEquals(listOf(ScanMarkTool.Erase, ScanMarkTool.EraseColour, ScanMarkTool.EraseEdge), ScanEraseFill.entries.map { it.tool })
    }

    @Test
    fun theHintSaysWhatEraseDoes() {
        assertEquals(R.string.scan_markup_erase_hint, scanMarkupHint(ScanMarkupTool.Erase, ScanEraseFill.Paper, false))
        assertEquals(R.string.scan_markup_erase_colour_hint, scanMarkupHint(ScanMarkupTool.Erase, ScanEraseFill.Colour, false))
        assertEquals(R.string.scan_markup_erase_edge_hint, scanMarkupHint(ScanMarkupTool.Erase, ScanEraseFill.Edge, false))
        assertEquals(R.string.scan_markup_hint, scanMarkupHint(ScanMarkupTool.Pen, ScanEraseFill.Colour, false))
        assertEquals(R.string.scan_markup_dropper_hint, scanMarkupHint(ScanMarkupTool.Pen, ScanEraseFill.Paper, true))
    }

    @Test
    fun theTwoOptionsAreOnByDefaultAndKept() {
        assertTrue(ScanLab().markupColours && ScanLab().markupEdge)
        val off = ScanLab.decode(ScanLab(markupColours = false, markupEdge = false).encode())
        assertFalse(off.markupColours || off.markupEdge)
        assertTrue(ScanLab.decode("").markupColours && ScanLab.decode("").markupEdge)
        assertFalse(ScanLab(markupEdge = false).asDesigned)
        assertEquals(true, ScanLab().diag().toMap()["markup_colours"])
        assertEquals(true, ScanLab().diag().toMap()["markup_edge"])
        assertTrue(ScanLab().json().getBoolean("markup_colours") && ScanLab().json().getBoolean("markup_edge"))
    }
}
