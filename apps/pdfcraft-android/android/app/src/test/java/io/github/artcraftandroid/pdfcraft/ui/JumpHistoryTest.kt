package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.JumpHistory
import io.github.artcraftandroid.pdfcraft.ui.viewer.JumpSpot
import io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode
import io.github.artcraftandroid.pdfcraft.ui.viewer.PageSize
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerState
import io.github.artcraftandroid.pdfcraft.ui.viewer.parsePageNumber
import io.github.artcraftandroid.pdfcraft.ui.viewer.searchTextSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpHistoryTest {
    @Test
    fun backPopsSeveralLevelsAndForwardReturns() {
        val h = JumpHistory()
        h.push(JumpSpot(0, 0f)) // contents: 1 -> 10
        h.push(JumpSpot(9, 50f)) // link: 10 -> 40
        h.push(JumpSpot(39, 0f)) // bookmark: 40 -> 80
        assertEquals(JumpSpot(39, 0f), h.goBack(JumpSpot(79, 0f)))
        assertEquals(JumpSpot(9, 50f), h.goBack(JumpSpot(39, 0f)))
        assertEquals(JumpSpot(0, 0f), h.goBack(JumpSpot(9, 50f)))
        assertFalse(h.canGoBack)
        assertNull(h.goBack(JumpSpot(0, 0f)))
        // and forward again, the way it came
        assertEquals(JumpSpot(9, 50f), h.goForward(JumpSpot(0, 0f)))
        assertEquals(JumpSpot(39, 0f), h.goForward(JumpSpot(9, 50f)))
        assertEquals(JumpSpot(9, 50f), h.backTarget)
    }

    @Test
    fun aNewJumpClearsForward() {
        val h = JumpHistory()
        h.push(JumpSpot(1, 0f))
        h.goBack(JumpSpot(5, 0f))
        assertTrue(h.canGoForward)
        h.push(JumpSpot(1, 0f))
        assertFalse(h.canGoForward)
    }

    @Test
    fun keepsAtMostTheLimit() {
        val h = JumpHistory(limit = 3)
        for (p in 0 until 6) h.push(JumpSpot(p, 0f))
        assertEquals(JumpSpot(5, 0f), h.goBack(JumpSpot(9, 0f)))
        assertEquals(JumpSpot(4, 0f), h.goBack(JumpSpot(5, 0f)))
        assertEquals(JumpSpot(3, 0f), h.goBack(JumpSpot(4, 0f)))
        assertNull(h.goBack(JumpSpot(3, 0f)))
    }

    @Test
    fun theSamePlaceTwiceIsKeptOnce() {
        val h = JumpHistory()
        h.push(JumpSpot(2, 10f))
        h.push(JumpSpot(2, 10f))
        h.goBack(JumpSpot(7, 0f))
        assertFalse(h.canGoBack)
    }

    @Test
    fun pageNumbersOutOfRangeAreRejected() {
        assertEquals(0, parsePageNumber("1", 240))
        assertEquals(239, parsePageNumber(" 240 ", 240))
        assertNull(parsePageNumber("0", 240))
        assertNull(parsePageNumber("241", 240))
        assertNull(parsePageNumber("", 240))
        assertNull(parsePageNumber("abc", 240))
    }

    @Test
    fun aSpotRoundTripsThroughTheViewer() {
        val v = ViewerState(List(20) { PageSize(612f, 792f) }).apply {
            gap = 20f
            setViewport(1080f, 2000f)
            setPadding(120f, 80f)
        }
        assertEquals(PageMode.Continuous, v.mode)
        v.goToPage(7, 300f)
        val spot = v.spot()
        assertEquals(7, spot.page)
        assertEquals(300f, spot.yPoints, 0.5f)
        val scroll = v.scrollY
        v.goToPage(0)
        v.goTo(spot)
        assertEquals(scroll, v.scrollY, 0.5f)
    }

    @Test
    fun searchSampleCoversTheWholeDocument() {
        assertEquals(listOf(0, 1, 2), searchTextSample(3))
        val s = searchTextSample(1000)
        assertEquals(40, s.size)
        assertEquals(0, s.first())
        assertEquals(999, s.last())
        assertTrue(searchTextSample(0).isEmpty())
    }
}
