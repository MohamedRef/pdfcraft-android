package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanFilter
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.ScanPage
import io.github.artcraftandroid.pdfcraft.ui.ScanPreviewKey
import io.github.artcraftandroid.pdfcraft.ui.ScanSession
import io.github.artcraftandroid.pdfcraft.ui.scanPageRequest
import io.github.artcraftandroid.pdfcraft.ui.scanTestPageJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Markup on a scanned page (#495): the marks as data, kept with the scan, logged as counts only. */
class ScanMarksTest {
    private val pen = ScanStrokeMark(ScanMarkTool.Pen, 0x1565C0, 0.005f, floatArrayOf(0.1f, 0.2f, 1f, 0.5f, 0.25f, 0.6f))
    private val rub = ScanStrokeMark(ScanMarkTool.Erase, 0, 0.04f, floatArrayOf(0.7f, 0.7f, 1f))
    private val mark = ScanStrokeMark(ScanMarkTool.Highlighter, 0xFFEB3B, 0.025f, floatArrayOf(0.1f, 0.5f, 1f, 0.9f, 0.5f, 1f))
    private val note = ScanTextBox("Paid | 13 Nov;\nتم الدفع: 100", 0.5f, 0.9f, 0.026f, 0xC62828)
    private val marks = ScanMarks(1, listOf(pen, rub, mark), listOf(note))

    @Test
    fun marksSurviveTheirLineExactly() {
        val back = ScanMarks.decode(marks.encode())
        assertEquals(marks, back)
        // one line: nothing in it can break a session's lines or fields
        val line = marks.encode()
        assertFalse(line.contains('\n') || line.contains('\t'))
        // the text, with its own separators and Arabic, comes back as written
        assertEquals(note.text, back!!.texts.single().text)
    }

    @Test
    fun aMalformedLineIsNoMarks() {
        assertNull(ScanMarks.decode(null))
        assertNull(ScanMarks.decode("nonsense"))
        assertNull(ScanMarks.decode("m9|0||"))
        assertNull(ScanMarks.decode("m1|7||"))
        // no marks at all is no marks
        assertNull(ScanMarks.decode(ScanMarks().encode()))
        // a broken stroke or box is left out, the rest kept
        val kept = ScanMarks.decode("m1|0|p:000000:500:1000,1000,100;x:000000:500:1,1,1;p:zz:500:1,1,1|1:2:3")
        assertEquals(1, kept!!.strokes.size)
        assertTrue(kept.texts.isEmpty())
    }

    @Test
    fun theMarksGoWithTheScanSession() {
        val crop = ScanPage.WHOLE
        val s = ScanSession(null, null, false, "Scan", listOf(ScanSession.Page("/c/a.jpg", crop, null, look = ScanPageLook(ScanFilter.Grayscale), marks = marks), ScanSession.Page("/c/b.jpg", crop, null)))
        val back = ScanSession.decode(s.encode())!!
        assertEquals(marks, back.pages[0].marks)
        // the look line after it still belongs to the same page
        assertEquals(ScanFilter.Grayscale, back.pages[0].look?.filter)
        assertNull(back.pages[1].marks)
        // a session written before #495 has no marks line and reads as before
        val old = s.copy(pages = s.pages.map { ScanSession.Page(it.path, it.corners, it.found, look = it.look) }).encode()
        assertFalse(old.contains("marks\t"))
        assertNull(ScanSession.decode(old)!!.pages[0].marks)
    }

    @Test
    fun aQuarterTurnMovesASharePointClockwise() {
        assertEquals(0.8f to 0.1f, scanTurnShare(0.1f, 0.2f, 1))
        assertEquals(0.9f to 0.8f, scanTurnShare(0.1f, 0.2f, 2))
        assertEquals(0.1f to 0.2f, scanTurnShare(0.1f, 0.2f, 4))
        assertEquals(scanTurnShare(0.1f, 0.2f, 3), scanTurnShare(0.1f, 0.2f, -1))
        assertEquals(2, scanShownTotalTurns(2, 1))
        assertEquals(1, scanShownTotalTurns(null, 1))
    }

    @Test
    fun pointsCloserThanAStepAreDropped() {
        val many = FloatArray(300) { i -> if (i % 3 == 2) 1f else (i / 3) * 0.0001f }
        val thin = scanThinPoints(many, 0.002f, 1f)
        assertTrue(thin.size < many.size / 5)
        // the first and last points stay
        assertEquals(many[0], thin[0])
        assertEquals(many[many.size - 3], thin[thin.size - 3])
        val two = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
        assertTrue(two.contentEquals(scanThinPoints(two, 0.5f, 1f)))
    }

    @Test
    fun undoAndRedoGoBackAndForthWholeStates() {
        val h = ScanMarkupHistory(ScanMarks())
        assertFalse(h.canUndo)
        h.change(ScanMarks(0, listOf(pen)))
        h.change(ScanMarks(0, listOf(pen, rub)))
        h.undo()
        assertEquals(listOf(pen), h.now.strokes)
        assertTrue(h.canRedo)
        h.redo()
        assertEquals(listOf(pen, rub), h.now.strokes)
        // a new change after an undo forgets what could be redone
        h.undo()
        h.change(ScanMarks(0, listOf(pen, mark)))
        assertFalse(h.canRedo)
        // clearing is one change, and Undo brings everything back
        h.change(ScanMarks())
        h.undo()
        assertEquals(listOf(pen, mark), h.now.strokes)
    }

    @Test
    fun onlyStrokesTheEngineHasNotDrawnAreDrawnOnTop() {
        assertEquals(listOf(rub), scanMarkupPending(listOf(pen), listOf(pen, rub)))
        assertEquals(emptyList<ScanStrokeMark>(), scanMarkupPending(listOf(pen, rub), listOf(pen)))
        assertEquals(emptyList<ScanStrokeMark>(), scanMarkupPending(listOf(rub), listOf(pen, rub)))
        assertEquals(emptyList<ScanStrokeMark>(), scanMarkupPending(null, listOf(pen)))
    }

    @Test
    fun aBlackAndWhitePageHasNoHighlighter() {
        assertFalse(ScanMarkupTool.Highlighter in scanMarkupTools(ScanFilter.LightText))
        assertTrue(ScanMarkupTool.Highlighter in scanMarkupTools(ScanFilter.CleanScan))
        for (t in ScanMarkupTool.entries) assertEquals(3, scanMarkupSizes(t).size)
        assertTrue(scanMarkupColours(ScanMarkupTool.Erase).isEmpty())
    }

    @Test
    fun theOptionIsOnByDefaultAndKept() {
        assertTrue(ScanLab().markup)
        assertFalse(ScanLab.decode(ScanLab(markup = false).encode()).markup)
        assertTrue(ScanLab.decode("").markup)
        assertFalse(ScanLab(markup = false).asDesigned)
        assertEquals(true, ScanLab().diag().toMap()["markup"])
        assertTrue(ScanLab().json().getBoolean("markup"))
    }

    @Test
    fun theRequestCarriesTheMarksAndAPreviewOfAnotherMarkingIsTheSamePage() {
        val markup = io.github.artcraftandroid.pdfcraft.core.ScanMarkup(1u, emptyList(), emptyList())
        assertEquals(markup, scanPageRequest("/c/a.jpg", ScanPage.WHOLE, ScanFilter.CleanScan, false, ScanLab(), "", markup = markup).markup)
        assertNull(scanPageRequest("/c/a.jpg", ScanPage.WHOLE, ScanFilter.CleanScan, false, ScanLab(), "").markup)
        val key = ScanPreviewKey("/c/a.jpg", ScanPage.WHOLE.toList(), ScanFilter.CleanScan, false, ScanLab())
        val marked = key.copy(marks = marks)
        assertFalse(key == marked)
        // while the page with its new marks is made, the page as it was stays on screen
        assertTrue(key.samePage(marked))
    }

    @Test
    fun marksAreLoggedAndKeptAsCountsOnly() {
        val fields = scanMarksDiag(true, marks, 0.0123f, 40L).toMap()
        assertEquals(4, fields["marks"])
        assertEquals(1, fields["marks_pen"])
        assertEquals(1, fields["marks_highlighter"])
        assertEquals(1, fields["marks_erase"])
        assertEquals(1, fields["marks_text"])
        assertEquals(0.0123, fields["erased_share"])
        val none = scanMarksDiag(true, null, null, null).toMap()
        assertEquals(0, none["marks"])
        val done = scanMarkupDoneDiag(0, marks, 0.5f, 1200L, cancelled = false, changed = true).toMap()
        assertEquals(1, done["index"])
        assertEquals(2, done["strokes"])
        assertEquals(false, done["cancelled"])
        val json = JSONObject(scanTestPageJson("1.0 (1)", true, ScanPage.WHOLE, null, null, "auto", 60, false, ScanLab(), null, null, marks = marks.counts.json(0.0123)))
        val kept = json.getJSONObject("marks")
        assertEquals(4, kept.getInt("total"))
        assertEquals(0.0123, kept.getDouble("erased_share"), 1e-9)
        // the words of a text box are never written
        assertFalse(json.toString().contains("Paid"))
        assertTrue(JSONObject(scanTestPageJson("1.0 (1)", true, ScanPage.WHOLE, null, null, "auto", 60, false, ScanLab(), null, null)).isNull("marks"))
        assertNotNull(marks.counts)
    }

    @Test
    fun aSessionTooLongForTheSavedStateGoesIntoAFile() {
        val file = java.io.File.createTempFile("scan-state", ".txt").apply { deleteOnExit() }
        assertEquals("short", scanStateWrite("short", file))
        assertEquals("short", scanStateRead("short", file))
        val long = "x".repeat(SCAN_STATE_MAX_CHARS + 1)
        val kept = scanStateWrite(long, file)
        assertTrue(kept.length < 200)
        assertEquals(long, scanStateRead(kept, file))
        // a file that is gone, or another file's name, is no session
        file.delete()
        assertNull(scanStateRead(kept, file))
    }
}
