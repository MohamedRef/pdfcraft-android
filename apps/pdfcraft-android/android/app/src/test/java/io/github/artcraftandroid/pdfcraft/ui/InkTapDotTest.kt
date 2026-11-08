package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.persistedInkTapPoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkTapDotTest {
    @Test fun tapBecomesNativeTwoPointRoundCappedStroke() {
        val point = Offset(21f, 35f)
        val saved = persistedInkTapPoints(listOf(point))
        assertEquals(2, saved.size)
        assertEquals(point, saved.first())
        assertTrue((saved.last() - saved.first()).getDistance() < 0.1f)
    }

    @Test fun movingStrokesAndEmptyGesturesAreNotChanged() {
        val points = listOf(Offset(1f, 2f), Offset(50f, 60f))
        assertEquals(points, persistedInkTapPoints(points))
        assertTrue(persistedInkTapPoints(emptyList()).isEmpty())
    }

    @Test fun nonFiniteSinglePointCannotWriteMalformedPdfInk() {
        assertTrue(persistedInkTapPoints(listOf(Offset(Float.NaN, 2f))).isEmpty())
        assertTrue(persistedInkTapPoints(listOf(Offset(1f, Float.POSITIVE_INFINITY))).isEmpty())
    }
}
