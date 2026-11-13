package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanFilter
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.ScanPreviewKey
import io.github.artcraftandroid.pdfcraft.ui.scanPageRequest
import io.github.artcraftandroid.pdfcraft.ui.scanTestPageJson
import io.github.artcraftandroid.pdfcraft.ui.withoutOutput
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Save PDF that only writes the file, and text recognition only when asked (#504). */
class ScanSaveAheadTest {
    private val corners = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    @Test
    fun theOptionIsOnAndOffIsSaveAsBefore() {
        val lab = ScanLab()
        assertTrue(lab.fullPagesAhead)
        assertFalse(lab.copy(fullPagesAhead = false).asDesigned)
        // kept in the preferences, the log and a kept scan's page file
        assertEquals(lab.copy(fullPagesAhead = false), ScanLab.decode(lab.copy(fullPagesAhead = false).encode()))
        assertTrue(ScanLab.decode("").fullPagesAhead)
        assertTrue(lab.diag().toMap()["full_pages_ahead"] as Boolean)
        assertFalse(lab.copy(fullPagesAhead = false).json().getBoolean("full_pages_ahead"))
    }

    @Test
    fun aPageMadeAheadIsTakenOnlyWhenSaveWouldAskTheSame() {
        val lab = ScanLab()
        val ahead = scanPageRequest("/a.jpg", corners, ScanFilter.CleanScan, false, lab, "/cache/scan-full/page-1", turns = 1)
        // Save writes elsewhere: the same page all the same
        val save = scanPageRequest("/a.jpg", corners, ScanFilter.CleanScan, false, lab, "/cache/scan-full/page-2", turns = 1)
        assertEquals(ahead.withoutOutput(), save.withoutOutput())
        // a turn the multi-page rule gives on Save, another look or strength, or an option: made again
        assertNotEquals(ahead.withoutOutput(), scanPageRequest("/a.jpg", corners, ScanFilter.CleanScan, false, lab, "", turns = 2).withoutOutput())
        assertNotEquals(ahead.withoutOutput(), scanPageRequest("/a.jpg", corners, ScanFilter.CleanScan, false, lab, "", turns = 1, strength = 30).withoutOutput())
        assertNotEquals(ahead.withoutOutput(), scanPageRequest("/a.jpg", corners, ScanFilter.CleanScan, false, lab.copy(sharpen = 0), "", turns = 1).withoutOutput())
        assertNotEquals(ahead.withoutOutput(), scanPageRequest("/a.jpg", floatArrayOf(0.1f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), ScanFilter.CleanScan, false, lab, "", turns = 1).withoutOutput())
    }

    @Test
    fun eachPageMadeAheadSaysWhy() {
        val key = ScanPreviewKey("/a", corners.toList(), ScanFilter.CleanScan, false, ScanLab())
        assertEquals("new", scanFullWhy(null, key))
        assertEquals("turn", scanFullWhy(key, key))
        assertEquals("look", scanFullWhy(key, key.copy(strength = 30)))
        assertEquals("borders", scanFullWhy(key, key.copy(corners = listOf(0.1f, 0f, 1f, 0f, 1f, 1f, 0f, 1f))))
        assertEquals("options", scanFullWhy(key, key.copy(lab = ScanLab(sharpen = 0))))
        val line = scanFullReadyDiag(2, 840, 1_200_000, "new").toMap()
        assertEquals(3, line["index"])
        assertEquals(840L, line["ms"])
        assertEquals(1_200_000L, line["bytes"])
    }

    @Test
    fun saveFinishesOnlyThePagesNotReady() {
        assertEquals(listOf(1, 3), scanPagesToFinish(listOf(true, false, true, false)))
        assertEquals(emptyList<Int>(), scanPagesToFinish(listOf(true, true)))
        assertEquals(listOf(0, 1), scanPagesToFinish(listOf(false, false)))
    }

    @Test
    fun textRecognitionRunsOnlyWhenAsked() {
        assertEquals(ScanOcrOutcome.Off, scanOcrOutcome(on = false, available = true, skipped = false))
        assertEquals(ScanOcrOutcome.Unavailable, scanOcrOutcome(on = true, available = false, skipped = false))
        assertEquals(ScanOcrOutcome.Skipped, scanOcrOutcome(on = true, available = true, skipped = true))
        assertEquals(ScanOcrOutcome.Done, scanOcrOutcome(on = true, available = true, skipped = false))
        val line = scanSaveWaitDiag(true, 5, 900, ScanOcrOutcome.Off, 0).toMap()
        assertEquals(true, line["full_pages_ahead"])
        assertEquals(5, line["pages_ready"])
        assertEquals(900L, line["wait_ms"])
        assertEquals("off", line["ocr"])
        assertEquals(0L, line["ocr_ms"])
    }

    @Test
    fun aKeptPageSaysWhetherItWasMadeBeforeSave() {
        assertEquals(true, scanMadeFullBeforeSave(ahead = true, ready = true))
        assertEquals(false, scanMadeFullBeforeSave(ahead = true, ready = false))
        assertNull(scanMadeFullBeforeSave(ahead = false, ready = true))
        fun json(made: Boolean?) = JSONObject(scanTestPageJson("1.0 (1)", true, corners, null, null, "auto", 60, false, ScanLab(), null, null, madeFullBeforeSave = made))
        assertTrue(json(true).getBoolean("made_full_before_save"))
        assertFalse(json(false).getBoolean("made_full_before_save"))
        assertTrue(json(null).isNull("made_full_before_save"))
    }
}
