package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.PenButtonClick
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenButtonClickDetector
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenButtonHold
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenDownMode
import io.github.artcraftandroid.pdfcraft.ui.viewer.penDownMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PenButtonTest {
    @Test
    fun eraserTipAlwaysErasesWhateverTheMapping() {
        PenButtonHold.entries.forEach { hold ->
            assertEquals(PenDownMode.Erase, penDownMode(eraserTip = true, buttonHeld = false, hold = hold))
            assertEquals(PenDownMode.Erase, penDownMode(eraserTip = true, buttonHeld = true, hold = hold))
        }
    }

    @Test
    fun heldButtonFollowsTheMappingAndNoneDisablesIt() {
        assertEquals(PenDownMode.Erase, penDownMode(false, true, PenButtonHold.Eraser))
        assertEquals(PenDownMode.Highlight, penDownMode(false, true, PenButtonHold.Highlighter))
        assertEquals(PenDownMode.Ink, penDownMode(false, true, PenButtonHold.None))
        PenButtonHold.entries.forEach { assertEquals(PenDownMode.Ink, penDownMode(false, false, it)) }
    }

    @Test
    fun hoverClickIsAQuickPressAndReleaseWithoutContact() {
        val d = PenButtonClickDetector(maxMs = 600)
        assertFalse(d.hover(false, 0))
        assertFalse(d.hover(true, 100))
        assertFalse(d.hover(true, 200))
        assertTrue(d.hover(false, 300))
        // A long hold is not a click.
        assertFalse(d.hover(true, 1000))
        assertFalse(d.hover(false, 2000))
    }

    @Test
    fun touchingTheScreenCancelsAPendingClick() {
        val d = PenButtonClickDetector()
        d.hover(true, 0)
        d.reset() // the tip touched: the press belongs to a stroke
        assertFalse(d.hover(false, 100))
    }

    @Test
    fun unknownStoredValuesFallBackToSafeDefaults() {
        assertEquals(PenButtonHold.Eraser, PenButtonHold.fromKey("future"))
        assertEquals(PenButtonClick.None, PenButtonClick.fromKey(null))
        assertEquals(PenButtonHold.None, PenButtonHold.fromKey("none"))
    }
}
