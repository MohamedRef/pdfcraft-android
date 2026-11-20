package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live outline's look per frame tries the still search's turns while it finds nothing (#295). */
class ScanLiveLooksTest {
    @Test
    fun whileNothingIsFoundEachFrameTriesTheNextTurn() {
        val looks = ScanLiveLooks(turned = true)
        val seen = mutableListOf(looks.angle)
        repeat(7) {
            looks.next(found = false)
            seen += looks.angle
        }
        // the still search's order, then round again
        assertEquals(listOf(0, 90, -90, -30, 30, -60, 60, 0), seen)
    }

    @Test
    fun aTurnThatFindsThePageIsKeptAndHeldOnceItIsLost() {
        val looks = ScanLiveLooks(turned = true)
        looks.next(false) // 90
        looks.next(false) // -90
        looks.next(false) // -30
        looks.next(false) // 30: the page lies at a slant
        assertEquals(30, looks.angle)
        looks.next(true)
        assertEquals(30, looks.angle)
        looks.next(true)
        assertEquals(30, looks.angle)
        // lost for a frame or two (a hand moving): still looked for at 30
        looks.next(false)
        assertEquals(30, looks.angle)
        looks.next(false)
        assertEquals(30, looks.angle)
        // then the other turns again, from the next one
        looks.next(false)
        assertEquals(-60, looks.angle)
        // found again where it is now
        looks.next(true)
        looks.next(false)
        assertEquals(-60, looks.angle)
    }

    @Test
    fun offEveryFrameIsLookedAtAsItIs() {
        val looks = ScanLiveLooks(turned = false)
        repeat(5) {
            looks.next(found = false)
            assertEquals(0, looks.angle)
        }
    }

    @Test
    fun theTurnsAreTheStillSearchsOwn() {
        assertEquals(listOf(0, 90, -90, -30, 30, -60, 60), SCAN_LIVE_LOOKS)
        assertEquals(2, SCAN_LIVE_LOOK_HOLD)
    }

    @Test
    fun theTestingOptionIsOnAndRemembered() {
        assertTrue(ScanLab().liveTurned)
        val off = ScanLab(liveTurned = false)
        assertFalse(ScanLab.decode(off.encode()).liveTurned)
        assertFalse(off.asDesigned)
        assertEquals(false, off.diag().toMap()["live_turned"])
        assertEquals(false, off.json().getBoolean("live_turned"))
        assertTrue(ScanLab.decode("keep=0;camera_bar=1").liveTurned)
    }
}
