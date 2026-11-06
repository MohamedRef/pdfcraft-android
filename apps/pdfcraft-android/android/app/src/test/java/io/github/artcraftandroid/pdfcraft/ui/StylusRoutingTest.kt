package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.StylusRouting
import org.junit.Assert.*
import org.junit.Test

class StylusRoutingTest {
    @Test fun fingersDrawUntilPenIsUsedThenNavigate() {
        val session = StylusRouting()
        assertFalse(session.fingersNavigate(false))
        session.penStarted()
        assertTrue(session.fingersNavigate(false))
        assertFalse(StylusRouting().fingersNavigate(false)) // closing/reopening tool resets auto mode
    }
    @Test fun explicitPenOnlyWorksBeforePenDetection() {
        assertTrue(StylusRouting().fingersNavigate(true))
    }
    @Test fun palmDoesNotCancelPenButTwoFingersCancelFingerInk() {
        val session = StylusRouting()
        assertFalse(session.cancelForExtraTouch(true, 3))
        assertTrue(session.cancelForExtraTouch(false, 2))
        assertFalse(session.cancelForExtraTouch(false, 1))
    }
}
