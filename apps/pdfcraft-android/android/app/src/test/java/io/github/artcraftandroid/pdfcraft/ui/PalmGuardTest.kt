package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.PALM_GUARD_WINDOW_MS
import io.github.artcraftandroid.pdfcraft.ui.viewer.palmGuardRejects
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmGuardTest {
    private val pen = Offset(500f, 500f)
    private val size = 300f

    @Test
    fun rightHandedPalmBelowRightIsRejectedButLeftSideIsNot() {
        assertTrue(palmGuardRejects(pen, 1000, Offset(700f, 750f), 1100, leftHanded = false, size = size))
        assertFalse(palmGuardRejects(pen, 1000, Offset(200f, 750f), 1100, leftHanded = false, size = size))
    }

    @Test
    fun leftHandedMirrorsTheRegion() {
        assertTrue(palmGuardRejects(pen, 1000, Offset(300f, 750f), 1100, leftHanded = true, size = size))
        assertFalse(palmGuardRejects(pen, 1000, Offset(800f, 750f), 1100, leftHanded = true, size = size))
    }

    @Test
    fun touchesAboveThePenOrLongAfterItLeftAreAllowed() {
        assertFalse(palmGuardRejects(pen, 1000, Offset(600f, 100f), 1100, leftHanded = false, size = size))
        assertFalse(palmGuardRejects(pen, 1000, Offset(700f, 750f), 1000 + PALM_GUARD_WINDOW_MS + 1, leftHanded = false, size = size))
        assertFalse(palmGuardRejects(null, 0, Offset(700f, 750f), 10, leftHanded = false, size = size))
    }

    @Test
    fun handednessDoesNotDependOnLayoutDirection() {
        // Pure screen geometry: the same physical side for an Arabic (RTL) or English UI.
        val a = palmGuardRejects(pen, 0, Offset(650f, 600f), 10, leftHanded = false, size = size)
        val b = palmGuardRejects(pen, 0, Offset(350f, 600f), 10, leftHanded = true, size = size)
        assertTrue(a && b)
    }
}
