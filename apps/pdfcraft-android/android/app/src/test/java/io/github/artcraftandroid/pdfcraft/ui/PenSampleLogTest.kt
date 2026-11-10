package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenSample
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenSampleLog
import io.github.artcraftandroid.pdfcraft.ui.viewer.StylusHistoryPoint
import io.github.artcraftandroid.pdfcraft.ui.viewer.stylusMotionPoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PenSampleLogTest {
    private fun log(vararg s: PenSample) = PenSampleLog().apply { setDown(true); s.forEach(::add) }

    @Test fun historicalSamplesGetTheirRealPressure() {
        val log = log(PenSample(100, 0f, 0f, 0.2f), PenSample(104, 4f, 0f, 0.9f), PenSample(108, 8f, 0f, 0.4f))
        val points = stylusMotionPoints(
            previousPosition = Offset(0f, 0f), position = Offset(8f, 0f), previousTime = 100, currentTime = 108,
            previousPressure = 0.2f, pressure = 0.4f,
            history = listOf(StylusHistoryPoint(104, Offset(4f, 0f))),
            pressureAt = { log.pressureAt(it) },
        )
        // Interpolation would give 0.3; the pen really pressed 0.9 there.
        assertEquals(0.9f, points.first { it.uptimeMillis == 104L }.pressure, 1e-6f)
    }

    @Test fun withoutALoggedSampleTheEstimateRemains() {
        val points = stylusMotionPoints(Offset(0f, 0f), Offset(8f, 0f), 100, 108, 0.2f, 0.4f, listOf(StylusHistoryPoint(104, Offset(4f, 0f))))
        assertEquals(0.3f, points.first { it.uptimeMillis == 104L }.pressure, 1e-5f)
    }

    @Test fun pressureLookupNeedsAMatchingTime() {
        val log = log(PenSample(100, 0f, 0f, 0.5f))
        assertEquals(0.5f, log.pressureAt(101)!!, 0f)
        assertNull(log.pressureAt(110))
        assertNull(log(PenSample(100, 0f, 0f, 0f)).pressureAt(100))
    }

    @Test fun predictionFollowsThePensSpeed() {
        val log = log(PenSample(100, 0f, 0f, 1f), PenSample(120, 20f, 0f, 1f), PenSample(140, 40f, 10f, 1f))
        val (dx, dy) = log.predictedOffset(now = 145, aheadMs = 10f)!!
        // Over the last 20 ms the pen moved (20, 10) px: 10 ms ahead is half of that.
        assertEquals(10f, dx, 0.01f)
        assertEquals(5f, dy, 0.01f)
    }

    @Test fun noPredictionWhenUpStaleOrTooFast() {
        val samples = arrayOf(PenSample(100, 0f, 0f, 1f), PenSample(120, 20f, 0f, 1f))
        assertNull(PenSampleLog().apply { samples.forEach(::add) }.predictedOffset(125))
        assertNull(log(*samples).predictedOffset(now = 500))
        val fast = log(PenSample(100, 0f, 0f, 1f), PenSample(120, 2000f, 0f, 1f)).predictedOffset(125, maxPx = 48f)
        assertNotNull(fast)
        assertEquals(48f, fast!!.first, 0.01f)
        assertNull(log(PenSample(100, 0f, 0f, 1f)).predictedOffset(101))
    }

    @Test fun theLogIsBounded() {
        val log = PenSampleLog(capacity = 4).apply { setDown(true) }
        for (t in 0L until 10L) log.add(PenSample(t, t.toFloat(), 0f, 0.5f))
        assertNull(log.pressureAt(2))
        assertTrue(log.pressureAt(9) != null)
    }
}
