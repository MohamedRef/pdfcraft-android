package io.github.artcraftandroid.pdfcraft.ui

import android.view.KeyEvent
import io.github.artcraftandroid.pdfcraft.ui.viewer.SHORTCUTS
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut
import io.github.artcraftandroid.pdfcraft.ui.viewer.presetForKey
import io.github.artcraftandroid.pdfcraft.ui.viewer.steppedInkWidth
import io.github.artcraftandroid.pdfcraft.ui.viewer.viewerShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardShortcutsTest {
    @Test
    fun lettersPickToolsAndCtrlKeysAct() {
        assertEquals(ViewerShortcut.Pen, viewerShortcut(KeyEvent.KEYCODE_P, ctrl = false, shift = false))
        assertEquals(ViewerShortcut.Print, viewerShortcut(KeyEvent.KEYCODE_P, ctrl = true, shift = false))
        assertEquals(ViewerShortcut.Undo, viewerShortcut(KeyEvent.KEYCODE_Z, ctrl = true, shift = false))
        assertEquals(ViewerShortcut.Redo, viewerShortcut(KeyEvent.KEYCODE_Z, ctrl = true, shift = true))
        assertEquals(ViewerShortcut.Redo, viewerShortcut(KeyEvent.KEYCODE_Y, ctrl = true, shift = false))
        assertEquals(ViewerShortcut.Leave, viewerShortcut(KeyEvent.KEYCODE_ESCAPE, ctrl = false, shift = false))
        assertEquals(ViewerShortcut.PreviousPage, viewerShortcut(KeyEvent.KEYCODE_SPACE, ctrl = false, shift = true))
        // unknown keys, and Ctrl with a tool letter, are left to Android
        assertNull(viewerShortcut(KeyEvent.KEYCODE_Q, ctrl = false, shift = false))
        assertNull(viewerShortcut(KeyEvent.KEYCODE_E, ctrl = true, shift = false))
    }

    @Test
    fun everyListedShortcutDoesWhatItSays() {
        // the list (Ctrl+/ and Meta+/) and the key handling must agree
        val expected = mapOf(
            KeyEvent.KEYCODE_H to ViewerShortcut.Highlighter, KeyEvent.KEYCODE_E to ViewerShortcut.Eraser,
            KeyEvent.KEYCODE_L to ViewerShortcut.Lasso, KeyEvent.KEYCODE_T to ViewerShortcut.Text,
            KeyEvent.KEYCODE_N to ViewerShortcut.Note, KeyEvent.KEYCODE_S to ViewerShortcut.Shapes,
        )
        for ((key, shortcut) in expected) assertEquals(shortcut, viewerShortcut(key, ctrl = false, shift = false))
        for (e in SHORTCUTS) {
            if (e.keyCode == KeyEvent.KEYCODE_1) continue
            assert(viewerShortcut(e.keyCode, e.ctrl, e.shift) != null) { "listed but not handled: ${e.keyCode}" }
        }
    }

    @Test
    fun presetsAndWidths() {
        assertEquals(0, presetForKey(KeyEvent.KEYCODE_1))
        assertEquals(8, presetForKey(KeyEvent.KEYCODE_9))
        assertNull(presetForKey(KeyEvent.KEYCODE_0))
        assertEquals(1.5f, steppedInkWidth(1f, 1), 0f)
        assertEquals(1f, steppedInkWidth(1f, -1), 0f)
        assertEquals(10f, steppedInkWidth(10f, 1), 0f)
    }
}
