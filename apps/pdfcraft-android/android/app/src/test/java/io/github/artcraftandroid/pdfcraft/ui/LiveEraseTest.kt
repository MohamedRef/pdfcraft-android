package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Rect
import io.github.artcraftandroid.pdfcraft.ui.viewer.ERASE_REGION_MAX_PIXELS
import io.github.artcraftandroid.pdfcraft.ui.viewer.EraseRegion
import io.github.artcraftandroid.pdfcraft.ui.viewer.eraseRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveEraseTest {
    private val view = Rect(0f, 0f, 1000f, 1000f)

    @Test
    fun aSmallVisiblePartRendersAtScreenScale() {
        // a page 600×800 px at 2 px/pt, scrolled so its top 200 px are above the view
        val r = eraseRegion(3, Rect(100f, -200f, 700f, 600f), view, 2f)!!
        assertEquals(EraseRegion(3, 2f, 0, 200, 600, 600), r)
    }

    @Test
    fun aLargeViewIsRenderedSmallerWithinTheEnginesTileLimit() {
        val big = Rect(0f, 0f, 3000f, 4000f)
        val r = eraseRegion(0, Rect(-500f, -500f, 2500f, 3500f), big, 4f)!!
        assertTrue(r.w.toLong() * r.h <= ERASE_REGION_MAX_PIXELS)
        assertTrue(r.scale < 4f)
        // the same part of the page: its size in points does not change with the scale
        assertEquals(2500f / 4f, r.w / r.scale, 2f)
        assertEquals(3500f / 4f, r.h / r.scale, 2f)
        assertEquals(500f / 4f, r.x / r.scale, 1f)
    }

    @Test
    fun aPageOutOfViewHasNoRegion() {
        assertNull(eraseRegion(0, Rect(0f, 1200f, 600f, 2000f), view, 2f))
        assertNull(eraseRegion(0, Rect(0f, 0f, 600f, 800f), view, 0f))
    }
}
