package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkAppearance
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkLassoBounds
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStroke
import io.github.artcraftandroid.pdfcraft.ui.viewer.LASSO_MAX_SCALE
import io.github.artcraftandroid.pdfcraft.ui.viewer.LASSO_MIN_SCALE
import io.github.artcraftandroid.pdfcraft.ui.viewer.LASSO_TURNABLE_SUBTYPES
import io.github.artcraftandroid.pdfcraft.ui.viewer.LassoHandle
import io.github.artcraftandroid.pdfcraft.ui.viewer.lassoHandleAt
import io.github.artcraftandroid.pdfcraft.ui.viewer.lassoHandlePosition
import io.github.artcraftandroid.pdfcraft.ui.viewer.lassoPivot
import io.github.artcraftandroid.pdfcraft.ui.viewer.lassoResizeScale
import io.github.artcraftandroid.pdfcraft.ui.viewer.lassoTransformPoint
import io.github.artcraftandroid.pdfcraft.ui.viewer.lassoTransformedDraft
import io.github.artcraftandroid.pdfcraft.ui.viewer.lassoTurnAngle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #689: the lasso selection's resize and turn handles. */
class InkLassoTransformTest {
    private val box = InkLassoBounds(100f, 200f, 300f, 300f)

    private fun assertNear(expected: Offset, actual: Offset, tolerance: Float = 1e-3f) {
        assertEquals("x of $actual", expected.x, actual.x, tolerance)
        assertEquals("y of $actual", expected.y, actual.y, tolerance)
    }

    @Test fun theHandlesSitAtTheCornerAndAboveTheMiddle() {
        assertNear(Offset(300f, 300f), lassoHandlePosition(box, LassoHandle.Resize, 20f))
        assertNear(Offset(200f, 180f), lassoHandlePosition(box, LassoHandle.Turn, 20f))
        assertEquals(LassoHandle.Resize, lassoHandleAt(box, Offset(305f, 296f), 10f, 20f))
        assertEquals(LassoHandle.Turn, lassoHandleAt(box, Offset(203f, 183f), 10f, 20f))
        // inside the box, away from both: a move, not a handle
        assertNull(lassoHandleAt(box, Offset(200f, 250f), 10f, 20f))
        assertNull(lassoHandleAt(box, Offset(Float.NaN, 0f), 10f, 20f))
        // a resize keeps the opposite corner where it is; a turn goes about the middle
        assertNear(Offset(100f, 200f), lassoPivot(box, LassoHandle.Resize))
        assertNear(Offset(200f, 250f), lassoPivot(box, LassoHandle.Turn))
    }

    @Test fun aResizeKeepsTheProportions() {
        val pivot = Offset(100f, 200f)
        val corner = Offset(300f, 300f)
        assertEquals(2f, lassoResizeScale(pivot, corner, Offset(500f, 400f)), 1e-5f)
        assertEquals(0.5f, lassoResizeScale(pivot, corner, Offset(200f, 250f)), 1e-5f)
        // off the diagonal: how far along it the handle went (1.5 along it, then across it)
        assertEquals(1.5f, lassoResizeScale(pivot, corner, Offset(400f, 350f) + Offset(-20f, 40f)), 1e-5f)
        assertEquals(LASSO_MIN_SCALE, lassoResizeScale(pivot, corner, Offset(0f, 100f)), 0f)
        assertEquals(LASSO_MAX_SCALE, lassoResizeScale(pivot, corner, Offset(1e6f, 1e6f)), 0f)
        assertEquals(1f, lassoResizeScale(pivot, pivot, Offset(5f, 5f)), 0f)
    }

    @Test fun aTurnIsMeasuredAboutTheMiddleAndSnapsToQuarterTurns() {
        val pivot = Offset(200f, 250f)
        val top = Offset(200f, 180f)
        // from straight above to straight right: a quarter turn clockwise as seen (page y down)
        assertEquals(90f, lassoTurnAngle(pivot, top, Offset(270f, 250f)), 1e-3f)
        assertEquals(-90f, lassoTurnAngle(pivot, top, Offset(130f, 250f)), 1e-3f)
        assertEquals(45f, lassoTurnAngle(pivot, top, Offset(250f, 200f)), 1e-3f)
        // within 4 degrees of a quarter turn it is the quarter turn
        assertEquals(90f, lassoTurnAngle(pivot, top, Offset(270f, 254f)), 0f)
        assertEquals(180f, lassoTurnAngle(pivot, top, Offset(202f, 320f)), 0f)
        assertEquals(0f, lassoTurnAngle(pivot, pivot, Offset(1f, 1f)), 0f)
    }

    /** The same turn the engine's `transform` makes: positive is clockwise as seen. */
    @Test fun aPointTurnsClockwiseAsSeen() {
        val pivot = Offset(100f, 100f)
        assertNear(Offset(100f, 300f), lassoTransformPoint(Offset(200f, 100f), pivot, 2f, 90f))
        assertNear(Offset(0f, 300f), lassoTransformPoint(Offset(200f, 150f), pivot, 2f, 90f))
        assertNear(Offset(150f, 100f), lassoTransformPoint(Offset(200f, 100f), pivot, 0.5f, 0f))
        assertNear(Offset(200f, 100f), lassoTransformPoint(lassoTransformPoint(Offset(200f, 100f), pivot, 2f, 30f), pivot, 0.5f, -30f))
    }

    @Test fun aDraftIsTurnedAndScaledWithItsWidth() {
        val s = InkStroke(2, InkAppearance(0xFF112233.toInt(), 0.8f, 3f, "Ada"), varies = true).also {
            it.points.addAll(listOf(Offset(100f, 100f), Offset(200f, 100f)))
            it.scales.addAll(listOf(0.8f, 1.2f))
        }
        val t = lassoTransformedDraft(s, Offset(100f, 100f), 0.5f, 90f)
        assertEquals(2, t.page)
        assertEquals(1.5f, t.appearance.width, 1e-5f)
        assertEquals(s.appearance.copy(width = 1.5f), t.appearance)
        assertTrue(t.pressureSensitive)
        assertEquals(listOf(0.8f, 1.2f), t.scales.toList())
        assertNear(Offset(100f, 150f), t.points[1])
        // the original is left as it was, for Undo
        assertNear(Offset(200f, 100f), s.points[1])
        assertEquals(3f, s.appearance.width, 0f)
        // the width stays within the pens' bounds
        assertEquals(30f, lassoTransformedDraft(s, Offset.Zero, 20f, 0f).appearance.width, 0f)
    }

    @Test fun onlyDrawingsLinesAndPolygonsTurn() {
        assertTrue("Ink" in LASSO_TURNABLE_SUBTYPES && "Polygon" in LASSO_TURNABLE_SUBTYPES)
        assertFalse("Square" in LASSO_TURNABLE_SUBTYPES || "FreeText" in LASSO_TURNABLE_SUBTYPES || "Stamp" in LASSO_TURNABLE_SUBTYPES)
    }
}
