package io.github.artcraftandroid.pdfcraft.ui.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.scanTestPageJson
import org.json.JSONObject

/** The document screen's rules (#460). */
class ScanDocumentTest {
    @Test
    fun aPageMovesToAnyPlaceAndTheOthersCloseUp() {
        val pages = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), scanMoved(pages, 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), scanMoved(pages, 3, 0))
        assertEquals(pages, scanMoved(pages, 1, 1))
        assertEquals(pages, scanMoved(pages, 5, 0))
        assertEquals(pages, scanMoved(pages, 0, -1))
    }

    @Test
    fun talkBackMovesOnePlaceAndStopsAtTheEnds() {
        assertEquals(0, scanMoveTarget(1, -1, 3))
        assertEquals(2, scanMoveTarget(1, 1, 3))
        assertNull(scanMoveTarget(0, -1, 3))
        assertNull(scanMoveTarget(2, 1, 3))
        assertNull(scanMoveTarget(0, 1, 1))
    }

    @Test
    fun aSelectionForgetsPagesThatWent() {
        val keys = listOf("a", "b#left", "b#right", "c")
        assertEquals(setOf("b#left", "c"), scanSelectionKept(setOf("b#left", "c", "gone"), keys))
        val same = setOf("a")
        assertTrue(same === scanSelectionKept(same, keys))
        // in page order, whatever order they were chosen in
        assertEquals(listOf(1, 3), scanSelectedIndexes(linkedSetOf("c", "b#left"), keys))
    }

    @Test
    fun theDocumentFollowsTheCameraUnlessBordersOrARetakeDo() {
        assertTrue(scanDocumentAfterCamera(steps = true, retaking = false, opener = null))
        assertFalse(scanDocumentAfterCamera(steps = true, retaking = false, opener = ScanBordersOpener.Capture))
        assertFalse(scanDocumentAfterCamera(steps = true, retaking = true, opener = null))
        assertFalse(scanDocumentAfterCamera(steps = false, retaking = false, opener = null))
    }

    @Test
    fun aTileShowsThePartInsideTheCorners() {
        val whole = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        assertArrayEquals(intArrayOf(0, 0, 300, 400), scanThumbRegion(whole, 300, 400))
        val inner = floatArrayOf(0.1f, 0.2f, 0.9f, 0.25f, 0.8f, 0.9f, 0.2f, 0.8f)
        assertArrayEquals(intArrayOf(30, 80, 240, 280), scanThumbRegion(inner, 300, 400))
        // a line of corners still shows a pixel, and nonsense shows the whole photo
        val flat = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
        assertArrayEquals(intArrayOf(150, 200, 1, 1), scanThumbRegion(flat, 300, 400))
        assertArrayEquals(intArrayOf(0, 0, 300, 400), scanThumbRegion(floatArrayOf(Float.NaN, 0f, 1f, 0f, 1f, 1f, 0f, 1f), 300, 400))
        assertArrayEquals(intArrayOf(0, 0, 1, 1), scanThumbRegion(whole, 0, 0))
    }

    @Test
    fun aDropLandsOnTheCellUnderTheFinger() {
        val cells = listOf(0 to intArrayOf(0, 0, 100, 140), 1 to intArrayOf(116, 0, 100, 140), 2 to intArrayOf(0, 156, 100, 140))
        assertEquals(1, scanDropIndex(cells, 150f, 70f))
        assertEquals(2, scanDropIndex(cells, 10f, 200f))
        assertNull(scanDropIndex(cells, 108f, 70f))
    }

    @Test
    fun theLogLinesCountFromOne() {
        val reorder = scanReorderDiag(0, 2, 4, "drag").toMap()
        assertEquals(1, reorder["from"])
        assertEquals(3, reorder["to"])
        assertEquals("drag", reorder["by"])
        assertEquals(mapOf("count" to 2, "pages" to 5, "confirmed" to true), scanPagesDeleteDiag(2, 5, true).toMap())
        val share = scanShareDiag(3, 1234L, 50L).toMap()
        assertEquals(3, share["pages"])
        assertEquals(1234L, share["bytes"])
        assertEquals(50L, share["ms"])
    }

    @Test
    fun aKeptPageFileHasItsPlaceInTheOrder() {
        val corners = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val json = JSONObject(scanTestPageJson("1.0 (1)", true, corners, null, null, "auto", 60, false, ScanLab(), null, null, position = 3))
        assertEquals(3, json.getInt("position"))
        val unknown = JSONObject(scanTestPageJson("1.0 (1)", true, corners, null, null, "auto", 60, false, ScanLab(), null, null))
        assertTrue(unknown.isNull("position"))
    }
}
