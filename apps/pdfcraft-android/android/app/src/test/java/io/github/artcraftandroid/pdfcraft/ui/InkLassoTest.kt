package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.MAX_INK_LASSO_POINTS
import io.github.artcraftandroid.pdfcraft.ui.viewer.addInkLassoPoint
import io.github.artcraftandroid.pdfcraft.ui.viewer.boundedInkLassoMove
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkLassoBounds
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkLassoHitsStroke
import org.junit.Assert.*
import org.junit.Test

class InkLassoTest {
    private val loop = listOf(
        Offset(10f, 10f), Offset(90f, 10f), Offset(90f, 90f), Offset(10f, 90f),
    )

    @Test fun selectsIntersectingAndInteriorDraftStrokes() {
        assertTrue(inkLassoHitsStroke(loop, listOf(Offset(20f, 20f), Offset(70f, 70f))))
        assertTrue(inkLassoHitsStroke(loop, listOf(Offset(0f, 50f), Offset(100f, 50f))))
        assertTrue(inkLassoHitsStroke(loop, listOf(Offset(10f, 10f), Offset(10f, 90f))))
        assertFalse(inkLassoHitsStroke(loop, listOf(Offset(120f, 50f), Offset(150f, 50f))))
    }

    @Test fun ignoresIncompleteAndMalformedLassos() {
        assertFalse(inkLassoHitsStroke(loop.take(2), listOf(Offset(20f, 20f), Offset(70f, 70f))))
        assertFalse(inkLassoHitsStroke(loop, listOf(Offset(20f, 20f))))
        assertFalse(inkLassoHitsStroke(loop + Offset(Float.NaN, 10f), listOf(Offset(20f, 20f), Offset(70f, 70f))))
        assertNull(inkLassoBounds(listOf(listOf(Offset(Float.POSITIVE_INFINITY, 1f)))))
    }

    @Test fun boundedDragMovesGroupWithoutClippingOrDistortion() {
        val paths = listOf(
            listOf(Offset(10f, 20f), Offset(30f, 40f)),
            listOf(Offset(60f, 70f), Offset(90f, 95f)),
        )
        assertEquals(Offset(10f, 5f), boundedInkLassoMove(paths, Offset(25f, 30f), 100f, 100f))
        assertEquals(Offset(-10f, -20f), boundedInkLassoMove(paths, Offset(-99f, -99f), 100f, 100f))
        assertEquals(Offset.Zero, boundedInkLassoMove(paths, Offset(Float.NaN, 1f), 100f, 100f))
        assertEquals(Offset.Zero, boundedInkLassoMove(paths, Offset(1f, 1f), 0f, 100f))
    }

    @Test fun inkPathRecordingHasFiniteBoundedGrowth() {
        val points = mutableListOf<Offset>()
        repeat(4000) { addInkLassoPoint(points, Offset(it.toFloat(), (it % 5).toFloat())) }
        assertTrue(points.size in 2..MAX_INK_LASSO_POINTS)
        assertEquals(Offset(0f, 0f), points.first())
        assertEquals(Offset(3999f, 4f), points.last())
        val before = points.size
        addInkLassoPoint(points, Offset(Float.NaN, 0f))
        addInkLassoPoint(points, points.last())
        assertEquals(before, points.size)
    }

    @Test fun noOverlapWhenBoundsDoNotIntersect() {
        assertFalse(inkLassoHitsStroke(loop, listOf(Offset(-100f, -100f), Offset(-50f, -50f))))
        assertNull(inkLassoBounds(emptyList()))
    }
}
