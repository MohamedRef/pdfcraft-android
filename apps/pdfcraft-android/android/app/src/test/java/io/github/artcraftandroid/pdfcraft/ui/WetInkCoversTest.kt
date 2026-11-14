package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.artcraftandroid.pdfcraft.ui.viewer.WetInkCovers
import org.junit.Assert.assertEquals
import org.junit.Test

class WetInkCoversTest {
    @Test
    fun barsAreKeptInTheSurfacesOwnPixelsAndDropWhenGone() {
        val covers = WetInkCovers()
        val bottomBar = Any()
        val rail = Any()
        covers.origin = Offset(0f, 100f)
        covers.set(bottomBar, Rect(0f, 1700f, 900f, 2000f))
        covers.set(rail, Rect(840f, 600f, 900f, 1200f))
        assertEquals(setOf(Rect(0f, 1600f, 900f, 1900f), Rect(840f, 500f, 900f, 1100f)), covers.inSurface().toSet())
        // a bar that slides away leaves no hole behind
        covers.set(rail, null)
        covers.set(bottomBar, Rect(0f, 2000f, 900f, 2000f))
        assertEquals(emptyList<Rect>(), covers.inSurface())
    }
}
