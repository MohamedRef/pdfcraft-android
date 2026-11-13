package io.github.artcraftandroid.pdfcraft.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a document type suggests, and when the chip shows (#299). */
class ScanSuggestionsTest {
    @Test
    fun aBookPageSuggestsFlatteningOnlyUntilThePersonChooses() {
        assertEquals(listOf(ScanSuggestion.Flatten), scanSuggestions(true, true, false, canFlatten = true, flattenChoice = null, cleanScan = true))
        // chosen either way, or no model to flatten with: no chip
        assertTrue(scanSuggestions(true, true, false, canFlatten = true, flattenChoice = false, cleanScan = true).isEmpty())
        assertTrue(scanSuggestions(true, true, false, canFlatten = true, flattenChoice = true, cleanScan = true).isEmpty())
        assertTrue(scanSuggestions(true, true, false, canFlatten = false, flattenChoice = null, cleanScan = true).isEmpty())
    }

    @Test
    fun aReceiptSuggestsGrayscaleWhileTheLookIsCleanScan() {
        assertEquals(listOf(ScanSuggestion.Greyscale), scanSuggestions(true, false, true, canFlatten = true, flattenChoice = null, cleanScan = true))
        assertTrue(scanSuggestions(true, false, true, canFlatten = true, flattenChoice = null, cleanScan = false).isEmpty())
        assertEquals(
            listOf(ScanSuggestion.Flatten, ScanSuggestion.Greyscale),
            scanSuggestions(true, true, true, canFlatten = true, flattenChoice = null, cleanScan = true),
        )
    }

    @Test
    fun theTestingOptionHidesThem() {
        assertTrue(scanSuggestions(false, true, true, canFlatten = true, flattenChoice = null, cleanScan = true).isEmpty())
        assertEquals(listOf("flatten", "greyscale"), ScanSuggestion.entries.map { it.id })
    }

    @Test
    fun nothingIsAskedThatTheAutomaticLookDecides() {
        // #493: automatic flattening on (the default) decided already; no "Flatten this page?"
        val lab = io.github.artcraftandroid.pdfcraft.ui.ScanLab()
        assertTrue(lab.flattenAuto && lab.autoLook)
        assertTrue(scanSuggestionsShown(lab, true, true, canFlatten = true, flattenChoice = null, cleanScan = true).isEmpty())
        // with each automatic decision off, its suggestion comes back
        assertEquals(listOf(ScanSuggestion.Flatten), scanSuggestionsShown(lab.copy(flattenAuto = false), true, false, canFlatten = true, flattenChoice = null, cleanScan = true))
        assertEquals(listOf(ScanSuggestion.Greyscale), scanSuggestionsShown(lab.copy(autoLook = false), false, true, canFlatten = true, flattenChoice = null, cleanScan = true))
    }
}
