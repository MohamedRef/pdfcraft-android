package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.GestureRole
import io.github.artcraftandroid.pdfcraft.ui.viewer.GestureRoute
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenButtonHold
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenDownMode
import io.github.artcraftandroid.pdfcraft.ui.viewer.StylusRouting
import io.github.artcraftandroid.pdfcraft.ui.viewer.ToolPointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The routing of tool gestures (#81), without a device. */
class GestureRouteTest {
    private val slop = 8f
    private val at = Offset(100f, 100f)

    @Test fun penTapWithSubPixelJitterIsATap() {
        val r = GestureRoute(slop)
        assertEquals(GestureRole.Draw, r.start(ToolPointer.Stylus, at))
        r.motion(Offset(100.4f, 99.7f))
        r.motion(Offset(100.6f, 100.2f))
        assertFalse("pen jitter is not a move (#74)", r.moved)
        r.motion(Offset(110f, 100f))
        assertTrue(r.moved)
    }

    @Test fun sideButtonAtContactDecidesTheModeForTheWholeStroke() {
        val r = GestureRoute(slop)
        assertEquals(GestureRole.Erase, r.start(ToolPointer.Stylus, at, buttonHeld = true, hold = PenButtonHold.Eraser))
        assertEquals(GestureRole.Draw, GestureRoute(slop).start(ToolPointer.Stylus, at, buttonHeld = true, hold = PenButtonHold.Highlighter))
        assertEquals(PenDownMode.Highlight, GestureRoute(slop).apply { start(ToolPointer.Stylus, at, buttonHeld = true, hold = PenButtonHold.Highlighter) }.mode)
        // A button pressed mid-stroke is not a new contact: an active pen ignores a second "arrival".
        val ink = GestureRoute(slop)
        ink.start(ToolPointer.Stylus, at)
        assertNull(ink.penArrives(ToolPointer.Stylus, at, buttonHeld = true))
        assertEquals(GestureRole.Draw, ink.role)
        assertEquals(GestureRole.Erase, GestureRoute(slop).start(ToolPointer.Eraser, at))
    }

    @Test fun palmThenPenLetsThePenWriteFromWhereItTouched() {
        val r = GestureRoute(slop)
        assertEquals(GestureRole.Palm, r.start(ToolPointer.Touch, at, palm = true))
        r.motion(Offset(300f, 300f))
        val pen = Offset(40f, 50f)
        assertEquals(GestureRole.Draw, r.penArrives(ToolPointer.Stylus, pen, buttonHeld = false))
        assertTrue(r.activePen)
        assertEquals(pen, r.origin)
        assertFalse("the palm's travel does not count against the pen's tap", r.moved)
        assertFalse("touches never interrupt the pen", r.touches(3))
    }

    @Test fun secondFingerTurnsAFingerStrokeIntoNavigation() {
        val r = GestureRoute(slop)
        assertEquals(GestureRole.Draw, r.start(ToolPointer.Touch, at))
        assertFalse(r.touches(1))
        assertTrue(r.touches(2))
        assertEquals(GestureRole.Navigate, r.role)
    }

    @Test fun fingersNavigateOnceAPenWasUsedOrInPenOnlyMode() {
        val routing = StylusRouting()
        assertEquals(GestureRole.Navigate, GestureRoute(slop, routing).start(ToolPointer.Touch, at, penOnly = true))
        assertEquals(GestureRole.Draw, GestureRoute(slop, routing).start(ToolPointer.Touch, at))
        GestureRoute(slop, routing).start(ToolPointer.Stylus, at)
        assertEquals(GestureRole.Navigate, GestureRoute(slop, routing).start(ToolPointer.Touch, at))
    }

    @Test fun overlayAndPalmTouchesNeitherDrawNorScroll() {
        assertEquals(GestureRole.Overlay, GestureRoute(slop).start(ToolPointer.Touch, at, overlay = true, palm = true))
        assertEquals(GestureRole.Palm, GestureRoute(slop).start(ToolPointer.Touch, at, palm = true))
    }
}
