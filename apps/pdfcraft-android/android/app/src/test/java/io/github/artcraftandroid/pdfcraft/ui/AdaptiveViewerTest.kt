package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.PageSize
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerState
import io.github.artcraftandroid.pdfcraft.ui.viewer.showTabletPageRail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveViewerTest {
    @Test fun railRequiresAnExpandedTabletWindow() {
        assertFalse(showTabletPageRail(839, 800, false))
        assertTrue(showTabletPageRail(840, 600, false))
        assertTrue(showTabletPageRail(1024, 800, false))
        assertFalse(showTabletPageRail(900, 393, false)) // a phone rotated sideways
        assertTrue(showTabletPageRail(900, 393, true)) // DeX resizable desktop window
        assertFalse(showTabletPageRail(700, 393, true)) // compact DeX window
    }

    @Test fun jumpingAndResizingKeepPageWithinDocumentBounds() {
        val viewer = ViewerState(List(12) { PageSize(612f, 792f) })
        viewer.setViewport(1600f, 950f)
        viewer.goToPage(7)
        assertEquals(7, viewer.currentPage)
        viewer.setViewport(1100f, 950f) // sidebar opened
        assertEquals(7, viewer.currentPage)
        viewer.setViewport(1600f, 950f) // sidebar collapsed
        assertEquals(7, viewer.currentPage)
        viewer.goToPage(999)
        assertEquals(11, viewer.currentPage)
        viewer.zoomStep(1, Offset(800f, 400f))
        assertTrue(viewer.zoom > 1f)
    }
}
