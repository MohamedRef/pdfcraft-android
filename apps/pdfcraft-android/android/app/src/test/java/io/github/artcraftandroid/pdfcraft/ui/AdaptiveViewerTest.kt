package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.ContentsPlace
import io.github.artcraftandroid.pdfcraft.ui.viewer.PageSize
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerState
import io.github.artcraftandroid.pdfcraft.ui.viewer.contentsPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveViewerTest {
    /** #659: the device decides, a tablet never gets the sheet, a phone always does. */
    @Test fun contentsGoToTheSideOnEveryTabletAndToTheSheetOnAPhone() {
        assertEquals(ContentsPlace.Docked, contentsPlace(800, 800, false)) // tablet upright: the page keeps 500 dp
        assertEquals(ContentsPlace.Docked, contentsPlace(1280, 800, false)) // tablet sideways, as in 0.0.41
        assertEquals(ContentsPlace.Docked, contentsPlace(780, 780, false)) // the line: 480 dp beside 300 dp
        assertEquals(ContentsPlace.Overlay, contentsPlace(779, 779, false))
        assertEquals(ContentsPlace.Overlay, contentsPlace(600, 600, false)) // a small tablet upright
        assertEquals(ContentsPlace.Sheet, contentsPlace(384, 384, false)) // a phone
        assertEquals(ContentsPlace.Sheet, contentsPlace(900, 393, false)) // a phone turned sideways
        assertEquals(ContentsPlace.Docked, contentsPlace(900, 393, true)) // a DeX desktop window
        assertEquals(ContentsPlace.Overlay, contentsPlace(700, 393, true)) // a narrow DeX window
        assertEquals(ContentsPlace.Sheet, contentsPlace(500, 393, true)) // a DeX window phone-wide
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
