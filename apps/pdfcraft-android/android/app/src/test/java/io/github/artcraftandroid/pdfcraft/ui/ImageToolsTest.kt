package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.Arrange
import io.github.artcraftandroid.pdfcraft.ui.viewer.dragCrop
import io.github.artcraftandroid.pdfcraft.ui.viewer.fitRatio
import io.github.artcraftandroid.pdfcraft.ui.viewer.numberAfterArrange
import io.github.artcraftandroid.pdfcraft.ui.viewer.snapDegrees
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Edit PDF's picture tools (#161): the geometry and numbering the engine calls are built from. */
class ImageToolsTest {
    @Test
    fun arrangeRenumbersInDrawingOrder() {
        assertEquals(3, numberAfterArrange(Arrange.Front, 1, 3))
        assertEquals(1, numberAfterArrange(Arrange.Back, 3, 3))
        assertEquals(3, numberAfterArrange(Arrange.Forward, 2, 3))
        assertEquals(3, numberAfterArrange(Arrange.Forward, 3, 3))
        assertEquals(1, numberAfterArrange(Arrange.Backward, 2, 3))
        assertEquals(1, numberAfterArrange(Arrange.Backward, 1, 3))
    }

    @Test
    fun arrangeUsesTheEngineActionNames() {
        assertEquals(listOf("bring_to_front", "bring_forward", "send_backward", "send_to_back"), Arrange.entries.map { it.action })
    }

    @Test
    fun rotationSnapsToFifteenDegrees() {
        assertEquals(0, snapDegrees(7f))
        assertEquals(15, snapDegrees(8f))
        assertEquals(-45, snapDegrees(-44f))
        assertEquals(180, snapDegrees(400f))
        assertEquals(-180, snapDegrees(-181f))
    }

    @Test
    fun aspectPresetFitsCentredInsideTheFrame() {
        val square = fitRatio(floatArrayOf(0f, 0f, 200f, 100f), 1f)
        assertArrayEquals(floatArrayOf(50f, 0f, 150f, 100f), square, 0.001f)
        val wide = fitRatio(floatArrayOf(0f, 0f, 160f, 160f), 16f / 9f)
        assertEquals(160f, wide[2] - wide[0], 0.001f)
        assertEquals(90f, wide[3] - wide[1], 0.001f)
        assertEquals(80f, (wide[1] + wide[3]) / 2f, 0.001f)
    }

    @Test
    fun freeCropMovesOnlyTheDraggedEdgesAndStaysOnThePicture() {
        val limit = floatArrayOf(0f, 0f, 400f, 300f)
        val crop = floatArrayOf(50f, 50f, 250f, 200f)
        val left = dragCrop(crop, booleanArrayOf(true, false, false, false), -100f, 30f, limit, null)
        assertArrayEquals(floatArrayOf(0f, 50f, 250f, 200f), left, 0.001f)
        // an edge can't pass the opposite one: at least 8 points stay
        val squeezed = dragCrop(crop, booleanArrayOf(false, false, true, false), -500f, 0f, limit, null)
        assertEquals(58f, squeezed[2], 0.001f)
    }

    @Test
    fun lockedRatioCornerKeepsTheShape() {
        val limit = floatArrayOf(0f, 0f, 400f, 300f)
        val crop = floatArrayOf(0f, 0f, 100f, 100f)
        val r = dragCrop(crop, booleanArrayOf(false, false, true, true), 50f, 0f, limit, 1f)
        assertEquals(r[2] - r[0], r[3] - r[1], 0.001f)
        assertEquals(150f, r[2], 0.001f)
        // growing past the picture's bottom takes the width back with it
        val tall = dragCrop(floatArrayOf(0f, 0f, 200f, 200f), booleanArrayOf(false, false, true, true), 300f, 0f, limit, 1f)
        assertEquals(300f, tall[3], 0.001f)
        assertEquals(300f, tall[2] - tall[0], 0.001f)
    }
}
