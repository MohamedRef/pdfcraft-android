package io.github.artcraftandroid.pdfcraft.ui

import android.view.InputDevice
import android.view.MotionEvent
import io.github.artcraftandroid.pdfcraft.ui.viewer.StylusLog
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A pen move event carries the samples the digitiser made since the last frame. Each must keep
 * its own tilt: before, every sample of a batch was drawn with the newest one's lean.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StylusTiltHistoryTest {
    private fun pen() = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })

    private fun coords(x: Float, tilt: Float) = arrayOf(MotionEvent.PointerCoords().apply {
        this.x = x
        y = 10f
        pressure = 0.5f
        setAxisValue(MotionEvent.AXIS_TILT, tilt)
    })

    private fun event(action: Int, time: Long, x: Float, tilt: Float): MotionEvent =
        MotionEvent.obtain(1_000L, time, action, 1, pen(), coords(x, tilt), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)

    @Test fun everyHistoricalSampleKeepsItsOwnTilt() {
        val down = event(MotionEvent.ACTION_DOWN, 1_000L, 0f, 0.05f)
        StylusLog.record(down)
        down.recycle()
        // one move event: 16 historical samples 2 ms apart, leaning from 0.10 to 0.85 rad, then the newest at 0.90
        val tilts = (0 until 16).map { 0.1f + it * 0.05f }
        val move = event(MotionEvent.ACTION_MOVE, 1_002L, 1f, tilts[0])
        for (i in 1 until tilts.size) move.addBatch(1_002L + i * 2, coords(1f + i, tilts[i]), 0)
        move.addBatch(1_034L, coords(17f, 0.9f), 0)
        assertEquals(16, move.historySize)
        StylusLog.record(move)
        move.recycle()

        tilts.forEachIndexed { i, t -> assertEquals("sample $i", t, StylusLog.pen.tiltAt(1_002L + i * 2)!!, 1e-6f) }
        assertEquals(0.9f, StylusLog.pen.tiltAt(1_034L)!!, 1e-6f)
        // the newest lean is still there for a time the log does not have
        assertEquals(0.9f, StylusLog.tiltRad, 1e-6f)

        val up = event(MotionEvent.ACTION_UP, 1_040L, 17f, 0.9f)
        StylusLog.record(up)
        up.recycle()
    }
}
