package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.SaveState
import io.github.artcraftandroid.pdfcraft.ui.viewer.saveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The top bar says whether changes are in the file (#190). */
class SaveStateTest {
    private fun state(edited: Boolean = true, saving: Boolean = false, dirty: Boolean = false, notWrittenBack: Boolean = false, writable: Boolean = true, hasSource: Boolean = true) =
        saveState(edited, saving, dirty, notWrittenBack, writable, hasSource)

    @Test
    fun nothingIsShownBeforeTheFirstEdit() {
        assertNull(state(edited = false))
    }

    @Test
    fun anEditIsUnsavedUntilASaveFinishes() {
        assertEquals(SaveState.Unsaved, state(dirty = true))
        assertEquals(SaveState.Saving, state(dirty = true, saving = true))
        assertEquals(SaveState.Saved, state())
    }

    @Test
    fun changesHeldBackFromTheOriginalAreNotSaved() {
        // saved into the working copy only, while Save a copy's picker was open
        assertEquals(SaveState.Unsaved, state(notWrittenBack = true))
    }

    @Test
    fun aFileThatCannotBeWrittenKeepsTheChangesInTheApp() {
        assertEquals(SaveState.KeptInApp, state(dirty = true, writable = false))
        assertEquals(SaveState.KeptInApp, state(notWrittenBack = true, writable = false))
    }
}
