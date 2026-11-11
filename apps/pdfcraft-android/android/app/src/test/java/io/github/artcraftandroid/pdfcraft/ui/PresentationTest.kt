package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.LASER_FADE_MS
import io.github.artcraftandroid.pdfcraft.ui.viewer.LaserPoint
import io.github.artcraftandroid.pdfcraft.ui.viewer.LaserTrail
import io.github.artcraftandroid.pdfcraft.ui.viewer.PageSize
import io.github.artcraftandroid.pdfcraft.ui.viewer.fitPage
import org.junit.Assert.assertNull
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

    @Test
    fun aPageFillsTheExternalScreenCentred() {
        // a portrait Letter page on a 1920x1080 screen: as tall as the screen, centred across
        val fit = fitPage(PageSize(612f, 792f), 1920f, 1080f)!!
        assertEquals(1080f / 792f, fit.scale, 0.0001f)
        assertEquals(0f, fit.top, 0.01f)
        assertEquals((1920f - 612f * fit.scale) / 2f, fit.left, 0.01f)
        // a landscape slide on the same screen: as wide as the screen
        val slide = fitPage(PageSize(960f, 540f), 1920f, 1080f)!!
        assertEquals(2f, slide.scale, 0.0001f)
        assertEquals(0f, slide.left, 0.01f)
        assertNull(fitPage(PageSize(0f, 792f), 1920f, 1080f))
    }

    @Test
    fun theTrailForgetsWhatHasFaded() {
        val t = LaserTrail()
        t.add(LaserPoint(0, 10f, 10f, 1000L))
        t.add(LaserPoint(0, 20f, 20f, 1500L))
        t.fade(1000L + LASER_FADE_MS + 1)
        assertEquals(listOf(LaserPoint(0, 20f, 20f, 1500L)), t.points.toList())
        t.fade(1500L + LASER_FADE_MS + 1)
        assertTrue(t.points.isEmpty())
    }
}
