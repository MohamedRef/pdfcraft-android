package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.SCAN_PRESETS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The document-type chip under the page preview (#299). */
class ScanPresetChipTest {
    @Test
    fun everyKindOfferedHasANameAndNoneTwice() {
        for (preset in SCAN_PRESETS) assertNotNull(preset, scanPresetLabel(preset))
        assertEquals(SCAN_PRESETS.size, SCAN_PRESETS.toSet().size)
        assertEquals(SCAN_PRESETS.size, SCAN_PRESETS.mapNotNull(::scanPresetLabel).toSet().size)
        assertNull(scanPresetLabel("magic"))
    }

    @Test
    fun theChipSaysAutoForAWorkedOutKindAndJustTheNameForAChosenOne() {
        assertEquals(ScanPresetChipText("receipt", auto = true), scanPresetChipText(null, "receipt"))
        assertEquals(ScanPresetChipText("whiteboard", auto = false), scanPresetChipText("whiteboard", "receipt"))
        // nothing to say before the first preview, or about a word that is no kind
        assertNull(scanPresetChipText(null, null))
        assertNull(scanPresetChipText(null, "magic"))
        assertEquals(ScanPresetChipText("document", auto = true), scanPresetChipText("magic", "document"))
    }
}
