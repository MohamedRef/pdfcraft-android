package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class InkLassoTest {
    private val outline = listOf(
        Offset(0f, 0f), Offset(100f, 0f), Offset(100f, 100f), Offset(0f, 100f),
    )

    @Test fun fullyEnclosedStrokeCanBeSelectedInPageCoordinates() {
        assertTrue(lassoEnclosesStroke(outline, listOf(Offset(5f, 5f), Offset(40f, 60f))))
        assertTrue(lassoEnclosesStroke(outline, listOf(Offset(0f, 25f), Offset(50f, 40f))))
        assertFalse(lassoEnclosesStroke(outline, listOf(Offset(5f, 5f), Offset(125f, 50f))))
    }

    @Test fun invalidOrDegenerateLassosCannotSelect() {
        val ink = listOf(Offset(2f, 2f), Offset(3f, 3f))
        assertFalse(lassoEnclosesStroke(emptyList(), ink))
        assertFalse(lassoEnclosesStroke(outline.take(2), ink))
        assertFalse(lassoEnclosesStroke(listOf(Offset.Zero, Offset(1f, 0f), Offset(2f, 0f)), ink))
        assertFalse(lassoEnclosesStroke(outline + Offset(Float.NaN, 2f), ink))
        assertFalse(lassoEnclosesStroke(outline, ink + Offset(Float.POSITIVE_INFINITY, 5f)))
    }

    @Test fun translationPreservesShapeAndRejectsUnsafeCoordinates() {
        val points = listOf(Offset(2f, 3f), Offset(5f, 7f))
        assertEquals(listOf(Offset(4f, 6f), Offset(7f, 10f)), translateInkPoints(points, Offset(2f, 3f)))
        assertEquals(points, translateInkPoints(points, Offset.Zero))
        assertNull(translateInkPoints(points, Offset(Float.NaN, 1f)))
        assertNull(translateInkPoints(points, Offset(10_001f, 0f)))
        assertNull(translateInkPoints(listOf(Offset(Float.MAX_VALUE, 2f)), Offset(Float.MAX_VALUE, 0f)))
    }

    @Test fun selectionBoundsAreFiniteAndIndependentOfZoom() {
        assertEquals(InkBounds(1f, 2f, 8f, 9f), inkSelectionBounds(listOf(listOf(Offset(8f, 2f), Offset(1f, 9f)))))
        assertNull(inkSelectionBounds(emptyList()))
        assertNull(inkSelectionBounds(listOf(listOf(Offset(Float.NEGATIVE_INFINITY, 0f)))))
    }
}
