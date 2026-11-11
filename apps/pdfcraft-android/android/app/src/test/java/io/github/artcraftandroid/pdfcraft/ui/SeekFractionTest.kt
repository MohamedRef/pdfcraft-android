package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.seekFraction
import org.junit.Assert.assertEquals
import org.junit.Test

class SeekFractionTest {
    @Test
    fun leftToRightUsesThePointerPosition() {
        assertEquals(0.9f, seekFraction(90f, 100, rtl = false), 1e-6f)
        assertEquals(0.1f, seekFraction(10f, 100, rtl = false), 1e-6f)
    }

    @Test
    fun rightToLeftMirrorsThePointerPosition() {
        // In Arabic the bar fills from the right, so a tap near the right edge is near the start.
        assertEquals(0.1f, seekFraction(90f, 100, rtl = true), 1e-6f)
        assertEquals(0.9f, seekFraction(10f, 100, rtl = true), 1e-6f)
    }

    @Test
    fun tapsOutsideTheBarAndEmptyBarsStayInRange() {
        assertEquals(1f, seekFraction(150f, 100, rtl = false), 0f)
        assertEquals(0f, seekFraction(-5f, 100, rtl = false), 0f)
        assertEquals(1f, seekFraction(-5f, 100, rtl = true), 0f)
        assertEquals(0f, seekFraction(20f, 0, rtl = false), 0f)
    }
}
