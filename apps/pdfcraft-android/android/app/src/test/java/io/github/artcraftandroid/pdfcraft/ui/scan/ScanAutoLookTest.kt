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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The automatic look that decides every page by itself (#466). */
class ScanAutoLookTest {
    private val flattenOn = ScanLab(flattenAuto = true)

    @Test
    fun suggestionsAreAppliedAndFlatteningIsDecidedByDefault() {
        assertTrue(ScanLab().autoLook)
        assertTrue(ScanLab().flattenAuto)
        assertFalse(ScanLab(autoLook = false).asDesigned)
        assertEquals(ScanLab(autoLook = false), ScanLab.decode(ScanLab(autoLook = false).encode()))
        // saved before the option existed
        assertTrue(ScanLab.decode("keep=1;bg_pages=1").autoLook)
        assertEquals(false, ScanLab(autoLook = false).diag().toMap()["auto_look"])
        assertTrue(ScanLab().json().getBoolean("auto_look"))
    }

    @Test
    fun flatteningIsDecidedOnlyForAPageWithNoChoice() {
        assertTrue(scanAutoFlattens(null, flattenOn))
        // the switch (or Undo on the chip) is the person's override for one page
        assertFalse(scanAutoFlattens(false, flattenOn))
        assertFalse(scanAutoFlattens(true, flattenOn))
        assertFalse(scanAutoFlattens(null, ScanLab(flattenAuto = false)))
    }

    @Test
    fun theDecisionGoesThroughTheGuardedCheck() {
        val decided = scanPageRequest("/a", ScanPage.WHOLE, ScanFilter.CleanScan, false, flattenOn, "", autoFlatten = true)
        assertTrue(decided.flatten)
        assertTrue(decided.flattenCheck)
        // a page the person switched off is not flattened, even with the automatic mode on
        val undone = scanPageRequest("/a", ScanPage.WHOLE, ScanFilter.CleanScan, false, flattenOn, "", autoFlatten = scanAutoFlattens(false, flattenOn))
        assertFalse(undone.flatten)
        assertFalse(undone.flattenCheck)
        val off = scanPageRequest("/a", ScanPage.WHOLE, ScanFilter.CleanScan, false, ScanLab(flattenAuto = false), "")
        assertFalse(off.flatten)
        assertFalse(off.flattenCheck)
        // the person's switch flattens without the check, as before
        val chosen = scanPageRequest("/a", ScanPage.WHOLE, ScanFilter.CleanScan, true, flattenOn, "", autoFlatten = false)
        assertTrue(chosen.flatten)
        assertFalse(chosen.flattenCheck)
        // a decided page and an undone one are different previews
        val key = ScanPreviewKey("/a", ScanPage.WHOLE.toList(), ScanFilter.CleanScan, false, flattenOn)
        assertNotEquals(key, key.copy(autoFlatten = true))
    }

    @Test
    fun aReceiptBecomesGrayscaleOnceAndUndoKeepsItBack() {
        val clean = ScanPageLook()
        assertTrue(scanAutoGrey(ScanLab(), true, clean, ScanAutoMarks()))
        assertFalse(scanAutoGrey(ScanLab(), false, clean, ScanAutoMarks()))
        assertFalse(scanAutoGrey(ScanLab(autoLook = false), true, clean, ScanAutoMarks()))
        assertFalse(scanAutoGrey(ScanLab(presets = false), true, clean, ScanAutoMarks()))
        // a look chosen by the person is not replaced
        assertFalse(scanAutoGrey(ScanLab(), true, ScanPageLook(ScanFilter.Whiteboard), ScanAutoMarks()))
        // taken back: not done again
        assertFalse(scanAutoGrey(ScanLab(), true, clean, ScanAutoMarks(undone = setOf(ScanAutoMarks.GREYSCALE))))
        assertFalse(scanAutoGrey(ScanLab(), true, clean, ScanAutoMarks(greyApplied = true)))
    }

    @Test
    fun eachAutomaticChangeHasAChip() {
        val grey = ScanAutoMarks(greyApplied = true)
        assertEquals(listOf(ScanAutoChip.Grayscale), scanAutoChips(ScanLab(flattenAuto = false), grey, ScanPageLook(ScanFilter.Grayscale), null, autoFlattened = true))
        assertEquals(listOf(ScanAutoChip.Flattened, ScanAutoChip.Grayscale), scanAutoChips(flattenOn, grey, ScanPageLook(ScanFilter.Grayscale), null, autoFlattened = true))
        // the person's own choice has no chip
        assertEquals(emptyList<ScanAutoChip>(), scanAutoChips(flattenOn, ScanAutoMarks(), ScanPageLook(), true, autoFlattened = true))
        assertEquals(emptyList<ScanAutoChip>(), scanAutoChips(flattenOn, ScanAutoMarks(), ScanPageLook(), null, autoFlattened = false))
    }

    @Test
    fun aGreyLookIsNotPassedToTheNextPage() {
        assertEquals(ScanPageLook(), scanLookToPassOn(ScanPageLook(ScanFilter.Grayscale), ScanAutoMarks(greyApplied = true)))
        assertEquals(ScanPageLook(ScanFilter.Grayscale), scanLookToPassOn(ScanPageLook(ScanFilter.Grayscale), ScanAutoMarks()))
    }

    @Test
    fun theAutomaticLookComesFirstInTheStrip() {
        assertEquals(ScanFilter.CleanScan, scanStripOrder(true).first())
        assertEquals(ScanFilter.entries.toSet(), scanStripOrder(true).toSet())
        assertEquals(ScanFilter.entries, scanStripOrder(false))
    }

    @Test
    fun theDecisionsAreLoggedAndKept() {
        val d = scanAutoDecisions("receipt", null, 1, flattened = false, checkWhy = null, choice = null, auto = false, marks = ScanAutoMarks(greyApplied = true))
        assertEquals(ScanAutoDecisions("receipt", true, false, "off", "greyscale", "none"), d)
        val f = scanAutoDecisions("book_page", null, 0, flattened = true, checkWhy = "straighter", choice = null, auto = true, marks = ScanAutoMarks())
        assertEquals("flatten", f.suggestionApplied)
        assertEquals("straighter", f.flattenWhy)
        assertFalse(f.turned)
        val undone = scanAutoDecisions(null, 2, 2, flattened = false, checkWhy = null, choice = false, auto = false, marks = ScanAutoMarks(undone = setOf("flatten", "greyscale")))
        assertEquals("undone", undone.flattenWhy)
        assertEquals("flatten+greyscale", undone.undone)
        assertEquals("none", undone.kind)
        // the person's turn is not the automatic look's
        assertFalse(undone.turned)
        val fields = d.diag(0, ScanLab()).toMap()
        assertEquals(1, fields["index"])
        assertEquals("greyscale", fields["suggestion_applied"])
        val json = JSONObject(scanTestPageJson("1.0 (1)", true, ScanPage.WHOLE, null, null, "auto", 60, false, ScanLab(), null, null, autoDecisions = d.json()))
        assertEquals("greyscale", json.getJSONObject("auto_decisions").getString("suggestion_applied"))
        assertTrue(JSONObject(scanTestPageJson("1.0 (1)", true, ScanPage.WHOLE, null, null, "auto", 60, false, ScanLab(), null, null)).isNull("auto_decisions"))
    }

    @Test
    fun theMarksSurviveTheSession() {
        val marks = ScanAutoMarks(true, setOf("flatten"))
        assertEquals(marks, ScanAutoMarks.decode(marks.encode()))
        assertEquals(ScanAutoMarks(), ScanAutoMarks.decode("nonsense"))
        val crop = ScanPage.WHOLE
        val s = ScanSession(null, null, false, "Scan", listOf(ScanSession.Page("/c/a.jpg", crop, null, look = ScanPageLook(ScanFilter.Grayscale), auto = marks), ScanSession.Page("/c/b.jpg", crop, null)))
        val back = ScanSession.decode(s.encode())!!
        assertEquals(marks, back.pages[0].auto)
        assertEquals(ScanFilter.Grayscale, back.pages[0].look?.filter)
        assertEquals(ScanAutoMarks(), back.pages[1].auto)
    }

    @Test
    fun fingersAreRemovedByDefaultAndUndoKeepsThemOnThatPage() {
        assertTrue(ScanLab().removeFingers)
        assertFalse(ScanLab(removeFingers = false).asDesigned)
        assertEquals(ScanLab(removeFingers = false), ScanLab.decode(ScanLab(removeFingers = false).encode()))
        assertTrue(ScanLab.decode("keep=1;auto_look=1").removeFingers)
        assertEquals(false, ScanLab(removeFingers = false).diag().toMap()["remove_fingers"])
        val undone = ScanAutoMarks(undone = setOf(ScanAutoMarks.FINGERS))
        assertTrue(scanRemovesFingers(ScanLab(), ScanAutoMarks()))
        assertFalse(scanRemovesFingers(ScanLab(), undone))
        assertFalse(scanRemovesFingers(ScanLab(removeFingers = false), ScanAutoMarks()))
        assertEquals(undone, ScanAutoMarks.decode(undone.encode()))
        // the native request asks for it unless the page's Undo was taken
        assertTrue(scanPageRequest("/a", ScanPage.WHOLE, ScanFilter.CleanScan, false, ScanLab(), "").removeFingers)
        assertFalse(scanPageRequest("/a", ScanPage.WHOLE, ScanFilter.CleanScan, false, ScanLab(), "", removeFingers = false).removeFingers)
    }

    @Test
    fun aRemovedFingerHasAChipThatSaysWhenPrintWasLost() {
        val look = ScanPageLook()
        assertEquals(listOf(ScanAutoChip.Fingers), scanAutoChips(ScanLab(), ScanAutoMarks(), look, null, false, fingersFilled = 1))
        assertEquals(listOf(ScanAutoChip.FingersOverPrint), scanAutoChips(ScanLab(), ScanAutoMarks(), look, null, false, fingersFilled = 2, fingersOverPrint = 1))
        assertEquals(emptyList<ScanAutoChip>(), scanAutoChips(ScanLab(), ScanAutoMarks(), look, null, false))
        assertEquals(emptyList<ScanAutoChip>(), scanAutoChips(ScanLab(), ScanAutoMarks(undone = setOf(ScanAutoMarks.FINGERS)), look, null, false, fingersFilled = 1))
        assertEquals(ScanAutoMarks.FINGERS, scanAutoChipWhat(ScanAutoChip.FingersOverPrint))
    }

    @Test
    fun theFingersAreLoggedAndKept() {
        val counts = ScanFingerCounts(found = 2, overPrint = 1, filled = 2, ms = 14)
        val diag = scanFingersDiag(true, counts).toMap()
        assertEquals(2, diag["fingers"]); assertEquals(1, diag["fingers_over_print"]); assertEquals(2, diag["fingers_filled"]); assertEquals(14, diag["fingers_ms"])
        assertEquals(-1, scanFingersDiag(true, null).toMap()["fingers"])
        val d = scanAutoDecisions("document", null, 0, false, null, null, false, ScanAutoMarks(), counts)
        assertEquals("fingers", d.suggestionApplied)
        val json = d.json()
        assertEquals(1, json.getInt("fingers_over_print"))
        assertEquals(14, json.getInt("fingers_ms"))
        assertEquals("none", scanAutoDecisions("document", null, 0, false, null, null, false, ScanAutoMarks(), null).suggestionApplied)
    }
}
