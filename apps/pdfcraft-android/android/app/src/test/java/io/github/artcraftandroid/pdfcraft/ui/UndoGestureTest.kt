package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.MULTI_FINGER_TAP_MS
import io.github.artcraftandroid.pdfcraft.ui.viewer.UndoTap
import io.github.artcraftandroid.pdfcraft.ui.viewer.multiFingerTapFingers
import io.github.artcraftandroid.pdfcraft.ui.viewer.undoTapAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UndoGestureTest {
    private val slop = 18f

    @Test
    fun quickStillTwoAndThreeFingerTapsAreRecognised() {
        assertEquals(2, multiFingerTapFingers(2, 120, 0f, 0f, slop))
        assertEquals(3, multiFingerTapFingers(3, 200, 5f, 0.01f, slop))
    }

    @Test
    fun oneFingerPinchPanAndLongPressAreNotTaps() {
        assertNull(multiFingerTapFingers(1, 80, 0f, 0f, slop))
        assertNull(multiFingerTapFingers(4, 80, 0f, 0f, slop))
        assertNull(multiFingerTapFingers(2, MULTI_FINGER_TAP_MS + 1, 0f, 0f, slop))
        assertNull(multiFingerTapFingers(2, 100, slop + 1f, 0f, slop))
        assertNull(multiFingerTapFingers(2, 100, 0f, 0.2f, slop))
        assertNull(multiFingerTapFingers(2, 100, Float.MAX_VALUE, 0f, slop))
    }

    @Test
    fun oneTapUndoesAndOnlyRedoOpensTheMenu() {
        assertEquals(UndoTap.Undo, undoTapAction(canUndo = true, canRedo = false))
        assertEquals(UndoTap.Undo, undoTapAction(canUndo = true, canRedo = true))
        assertEquals(UndoTap.OpenMenu, undoTapAction(canUndo = false, canRedo = true))
        assertEquals(UndoTap.Nothing, undoTapAction(canUndo = false, canRedo = false))
    }
}
