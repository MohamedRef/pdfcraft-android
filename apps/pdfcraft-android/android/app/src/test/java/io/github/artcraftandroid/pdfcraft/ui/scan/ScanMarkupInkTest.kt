package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanFilter
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Markup's ink (#520): what the engine is asked to draw, what the screen draws, Remove marks. */
class ScanMarkupInkTest {
    private fun stroke(tool: ScanMarkTool, vararg xy: Float, width: Float = 0.01f): ScanStrokeMark {
        val p = FloatArray(xy.size / 2 * 3)
        for (i in 0 until xy.size / 2) {
            p[i * 3] = xy[i * 2]
            p[i * 3 + 1] = xy[i * 2 + 1]
            p[i * 3 + 2] = 1f
        }
        return ScanStrokeMark(tool, 0, width, p)
    }

    private val pen1 = stroke(ScanMarkTool.Pen, 0.1f, 0.1f, 0.4f, 0.1f)
    private val pen2 = stroke(ScanMarkTool.Pen, 0.1f, 0.5f, 0.4f, 0.5f)
    private val paper = stroke(ScanMarkTool.Erase, 0.2f, 0.2f, 0.3f, 0.3f, width = 0.04f)
    private val hl = stroke(ScanMarkTool.Highlighter, 0.6f, 0.6f, 0.9f, 0.6f, width = 0.03f)

    @Test
    fun penAndHighlighterAloneNeverAskTheEngine() {
        assertEquals(emptyList<ScanStrokeMark>(), scanMarkupEngineStrokes(listOf(pen1, pen2, hl), ink = true))
        // a Color fill is drawn on the screen too
        assertEquals(emptyList<ScanStrokeMark>(), scanMarkupEngineStrokes(listOf(stroke(ScanMarkTool.EraseColour, 0f, 0f, 1f, 1f)), ink = true))
        // off: every stroke, as before
        assertEquals(listOf(pen1, pen2, hl), scanMarkupEngineStrokes(listOf(pen1, pen2, hl), ink = false))
    }

    @Test
    fun aPaperFillTakesTheStrokesUnderItToTheEngine() {
        val all = listOf(pen1, paper, pen2, hl)
        // the pen stroke before the paper is under it, as it will be saved
        assertEquals(listOf(pen1, paper), scanMarkupEngineStrokes(all, ink = true))
        assertEquals(listOf(pen2, hl), scanMarkupOnScreen(listOf(pen1, paper), all, ink = true))
    }

    @Test
    fun undoAndClearShowAtOnceWhenTheEnginesPartIsTheSame() {
        // the engine drew nothing; Undo takes the last pen stroke away in the same frame
        assertEquals(listOf(pen1), scanMarkupOnScreen(emptyList(), listOf(pen1), ink = true))
        assertEquals(emptyList<ScanStrokeMark>(), scanMarkupOnScreen(emptyList(), emptyList(), ink = true))
        // the engine's drawing still has a stroke undone: the strokes after the shared part are drawn
        assertEquals(listOf(pen2), scanMarkupOnScreen(listOf(pen1, paper), listOf(pen1, pen2), ink = true))
        // off: nothing over a drawing that is not where the strokes started (the old rule)
        assertEquals(emptyList<ScanStrokeMark>(), scanMarkupOnScreen(listOf(pen1, paper), listOf(pen1), ink = false))
    }

    @Test
    fun removeMarksTakesOnlyOnesOwnStrokes() {
        val all = listOf(pen1, paper, pen2, hl)
        // on the first pen line
        assertEquals(setOf(0), scanStrokesTouched(all, 0.25f, 0.1f, 0.01f, 1f))
        // on the paper fill only: nothing, the paper is not one's own stroke
        assertEquals(emptySet<Int>(), scanStrokesTouched(all, 0.25f, 0.25f, 0.005f, 1f))
        // the highlighter's width counts
        assertEquals(setOf(3), scanStrokesTouched(all, 0.75f, 0.61f, 0.001f, 1f))
        // far from everything
        assertEquals(emptySet<Int>(), scanStrokesTouched(all, 0.9f, 0.95f, 0.01f, 1f))
        assertTrue(scanMarkRemovable(ScanMarkTool.EraseColour))
        assertFalse(scanMarkRemovable(ScanMarkTool.Erase))
        assertFalse(scanMarkRemovable(ScanMarkTool.EraseEdge))
    }

    @Test
    fun distancesAreMeasuredOnThePageNotInShares() {
        // a tall page, half as wide as it is high: a share of the width is half a share of the long side
        val vertical = listOf(stroke(ScanMarkTool.Pen, 0.5f, 0.1f, 0.5f, 0.9f, width = 0.002f))
        // 0.1 of the width away is 0.05 of the long side
        assertEquals(emptySet<Int>(), scanStrokesTouched(vertical, 0.6f, 0.5f, 0.04f, 0.5f))
        assertEquals(setOf(0), scanStrokesTouched(vertical, 0.6f, 0.5f, 0.06f, 0.5f))
    }

    @Test
    fun thePensWidthIsTheEngines() {
        assertEquals(10f, scanPenWidth(10f, 1f), 1e-5f)
        assertEquals(3.5f, scanPenWidth(10f, 0f), 1e-5f)
        // the engine's constant, read from its source so the two cannot drift apart
        val source = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "apps/pdfcraft-android/scan/src/markup.rs") }.firstOrNull { it.isFile }
        if (source != null) {
            val line = source.readLines().first { it.contains("pub const PEN_MIN_WIDTH") }
            assertEquals(SCAN_PEN_MIN_WIDTH, line.substringAfter("=").trim().removeSuffix(";").toFloat(), 0f)
        }
    }

    @Test
    fun theLiveStrokeGrowsWithoutCopies() {
        val p = ScanInkPoints()
        repeat(1000) { p.add(it.toFloat(), 0f, 1f) }
        assertEquals(1000, p.count)
        assertEquals(3000, p.toArray().size)
        assertEquals(999f, p.toArray()[2997], 0f)
    }

    @Test
    fun removeMarksIsOfferedOnlyWithTheInk() {
        assertFalse(ScanMarkupTool.RemoveMarks in scanMarkupTools(ScanFilter.CleanScan, ink = false))
        assertEquals(
            listOf(ScanMarkupTool.Erase, ScanMarkupTool.RemoveMarks, ScanMarkupTool.Pen, ScanMarkupTool.Highlighter, ScanMarkupTool.Text),
            scanMarkupTools(ScanFilter.CleanScan, ink = true),
        )
        assertEquals(emptyList<Int>(), scanMarkupSwatches(ScanMarkupTool.RemoveMarks, ScanEraseFill.Paper, 0x123456, false))
        assertTrue(ScanLab().markupInk)
        val off = ScanLab(markupInk = false)
        assertFalse(off.asDesigned)
        assertEquals(off, ScanLab.decode(off.encode()))
    }

    @Test
    fun theLogLines() {
        val s = ScanInkStats().apply { engineDraws = 1; removed = 2; undoMsMax = 4 }
        val m = s.diag(true).toMap()
        assertEquals(1, m["engine_draws"])
        assertEquals(2, m["marks_removed"])
        assertEquals(4L, m["undo_ms_max"])
        val stroke = scanInkStrokeDiag("pen", 120, 1000, true, 0).toMap()
        assertEquals(120L, stroke["rate_hz"])
        assertEquals("normal", stroke["path"])
    }

    @Test
    fun onlyThePenGoesToTheFrontBufferWithLowLatencyInk() {
        assertTrue(scanMarkupFront(ScanMarkupTool.Pen, ink = true, lowLatency = true))
        assertFalse(scanMarkupFront(ScanMarkupTool.Pen, ink = true, lowLatency = false))
        assertFalse(scanMarkupFront(ScanMarkupTool.Pen, ink = false, lowLatency = true))
        // translucent ink and the eraser's veil would double up where the pieces meet
        assertFalse(scanMarkupFront(ScanMarkupTool.Highlighter, ink = true, lowLatency = true))
        assertFalse(scanMarkupFront(ScanMarkupTool.Erase, ink = true, lowLatency = true))
    }

    @Test
    fun theSliderRunsPastTheDotsBothWays() {
        val pen = scanMarkupSizes(ScanMarkupTool.Pen)
        val range = scanMarkupWidthRange(pen)
        assertTrue(range.start < pen.first() && range.endInclusive > pen.last())
        assertTrue(pen.all { it in range })
        assertTrue(scanMarkupSlider(ScanMarkupTool.Erase, ink = true))
        // Text's size is the text dialog's; without the ink, the three dots as before
        assertFalse(scanMarkupSlider(ScanMarkupTool.Text, ink = true))
        assertFalse(scanMarkupSlider(ScanMarkupTool.Pen, ink = false))
    }

    @Test
    fun aPatchIsLaidIntoACopyOfThePage() {
        val base = IntArray(4 * 3) { 0xFFFFFFFF.toInt() }
        // a 2 × 1 patch at (1, 2): red, then blue
        val patch = byteArrayOf(255.toByte(), 0, 0, 255.toByte(), 0, 0, 255.toByte(), 255.toByte())
        val out = scanLayPatch(base, 4, 1, 2, 2, 1, patch)
        assertEquals(0xFFFF0000.toInt(), out[2 * 4 + 1])
        assertEquals(0xFF0000FF.toInt(), out[2 * 4 + 2])
        assertEquals(0xFFFFFFFF.toInt(), out[2 * 4 + 3])
        assertEquals(10, out.count { it == 0xFFFFFFFF.toInt() })
        // the page drawn once stays as it was, for the next patch
        assertTrue(base.all { it == 0xFFFFFFFF.toInt() })
    }

    @Test
    fun theLogSaysThePathPatchesAndWidth() {
        val m = ScanInkStats().apply { patches = 3; patchMsMax = 7; frontStrokes = 2 }.diag(true).toMap()
        assertEquals(3, m["patches"])
        assertEquals(7L, m["patch_ms_max"])
        assertEquals(2, m["front_strokes"])
        val stroke = scanInkStrokeDiag("pen", 10, 100, true, 0, front = true, width = 0.0051234f).toMap()
        assertEquals("front", stroke["path"])
        assertEquals(0.00512, stroke["width"] as Double, 1e-9)
    }
}
