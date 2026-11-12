package io.github.artcraftandroid.pdfcraft.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScanCropStatsTest {
    @Test
    fun twoVisitsAddUp() {
        val a = ScanCropStats(visits = 1, cornerDrags = 2, snapsAsked = 2, snapsTaken = 1, snapMsMax = 90, ms = 4000, movedPct = 2.0, snapOn = true, cornersOff = 1)
        val b = ScanCropStats(visits = 1, sideDrags = 1, snapsAsked = 1, snapsTaken = 1, snapMsMax = 40, ms = 1500, movedPct = 0.5, snapOn = false, cornersOff = 2)
        val sum = a + b
        assertEquals(2, sum.visits)
        assertEquals(2, sum.cornerDrags)
        assertEquals(1, sum.sideDrags)
        assertEquals(3, sum.snapsAsked)
        assertEquals(2, sum.snapsTaken)
        assertEquals(90, sum.snapMsMax)
        assertEquals(5500, sum.ms)
        assertEquals(2.0, sum.movedPct, 0.0)
        // the switch and the corners as they were last left
        assertFalse(sum.snapOn)
        assertEquals(2, sum.cornersOff)
        // a visit left with Back does not change where the corners were kept
        assertEquals(1, (a + ScanCropStats(cornersOff = 0)).cornersOff)
    }

    @Test
    fun cornersOffThePhotoAreCounted() {
        assertEquals(0, cornersOffPhoto(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)))
        assertEquals(2, cornersOffPhoto(floatArrayOf(0.1f, -0.05f, 1.1f, 0f, 0.9f, 0.9f, 0.1f, 0.9f)))
    }

    @Test
    fun theLogGetsNumbersAndBooleansOnly() {
        for ((k, v) in ScanCropStats(movedPct = Double.NaN).diag()) {
            assert(k.matches(Regex("[a-z_]+"))) { k }
            assert(v is Number || v is Boolean) { "$k=$v" }
        }
        assertEquals(0.0, ScanCropStats(movedPct = Double.NaN).json().getDouble("moved_pct"), 0.0)
    }

    @Test
    fun aCornerMoveIsMeasuredAgainstThePhotosDiagonal() {
        val a = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        // the top-left corner moved to the bottom-right: the whole diagonal
        val b = floatArrayOf(1f, 1f, 1f, 0f, 1f, 1f, 0f, 1f)
        assertEquals(100.0, maxCornerMovePct(a, b, 4f / 3f), 1e-4)
        // across a 4 x 3 photo by a quarter of its width: 1 of a diagonal of 5
        val c = floatArrayOf(0.25f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        assertEquals(20.0, maxCornerMovePct(a, c, 4f / 3f), 1e-4)
        assertEquals(0.0, maxCornerMovePct(a, a, 1f), 0.0)
        assertEquals(0.0, maxCornerMovePct(a, floatArrayOf(0f), 1f), 0.0)
    }
}
