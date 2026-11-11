package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.LASER_FADE_MS
import io.github.artcraftandroid.pdfcraft.ui.viewer.laserAlpha
import io.github.artcraftandroid.pdfcraft.ui.viewer.presentationTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationTest {
    @Test
    fun theLaserFadesWithinASecond() {
        assertEquals(1f, laserAlpha(0), 0f)
        assertEquals(0.5f, laserAlpha(LASER_FADE_MS / 2), 0.01f)
        assertEquals(0f, laserAlpha(LASER_FADE_MS), 0f)
        assertTrue(LASER_FADE_MS <= 1000L)
    }

    @Test
    fun outerThirdsTurnPages() {
        assertEquals(-1, presentationTap(10f, 900f))
        assertEquals(0, presentationTap(450f, 900f))
        assertEquals(1, presentationTap(890f, 900f))
        assertEquals(0, presentationTap(10f, 0f))
    }
}
