package io.github.artcraftandroid.pdfcraft.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which way up a scanned page is saved (#339): the classifier's turn, Rotate, Undo and the multi-page rule. */
class ScanTurnsTest {
    @Test
    fun aTurnIsAlwaysZeroToThree() {
        assertEquals(listOf(0, 1, 2, 3, 0, 3, 2), listOf(0, 1, 2, 3, 4, -1, 10).map(::scanQuarter))
    }

    @Test
    fun thePageShownIsThePersonsTurnWhenThereIsOne() {
        assertEquals(3, scanTurnShown(null, 3))
        assertEquals(1, scanTurnShown(1, 3))
        assertEquals(0, scanTurnShown(0, 2))
    }

    @Test
    fun rotateTurnsWhatIsShownOneQuarterClockwise() {
        // the classifier turned the page three times: one press makes four, which is none
        assertEquals(0, scanTurnAfterRotate(null, 3))
        assertEquals(1, scanTurnAfterRotate(null, 0))
        // four presses come back to where they started
        var turns: Int? = null
        repeat(4) { turns = scanTurnAfterRotate(turns, 2) }
        assertEquals(2, turns)
    }

    @Test
    fun theChipShowsOnlyOnAPageTheClassifierTurnedAndThePersonDidNot() {
        assertTrue(scanShowsTurnedChip(null, 1))
        assertFalse(scanShowsTurnedChip(null, 0))
        assertFalse(scanShowsTurnedChip(null, 4))
        // after Undo or Rotate the turn is the person's
        assertFalse(scanShowsTurnedChip(SCAN_TURN_UNDONE, 1))
        assertFalse(scanShowsTurnedChip(2, 1))
    }

    @Test
    fun theClassifierIsAskedOnlyWhenNothingStopsItAndThePersonHasNotTurnedThePage() {
        assertEquals(ScanTurnAsk(upright = true, turns = 0), scanTurnAsk(null, setting = true, testing = true))
        assertEquals(ScanTurnAsk(upright = false, turns = 0), scanTurnAsk(null, setting = false, testing = true))
        assertEquals(ScanTurnAsk(upright = false, turns = 0), scanTurnAsk(null, setting = true, testing = false))
        // a turn by hand switches the classifier off for that page, whatever the settings
        assertEquals(ScanTurnAsk(upright = false, turns = 3), scanTurnAsk(3, setting = true, testing = true))
        assertEquals(ScanTurnAsk(upright = false, turns = 0), scanTurnAsk(SCAN_TURN_UNDONE, setting = true, testing = true))
        assertEquals(ScanTurnAsk(upright = false, turns = 1), scanTurnAsk(5, setting = false, testing = false))
    }

    @Test
    fun onlyAPageThatFollowedItsNeighboursIntoAnotherTurnIsMadeAgain() {
        val settled = listOf(ScanSettled(1, false), ScanSettled(1, true), ScanSettled(1, false), ScanSettled(2, true), ScanSettled(0, false))
        // page 4 followed into the turn it was already saved with; page 5 failed
        val saved = listOf(1, 0, 1, 2, null)
        assertEquals(mapOf(1 to 1), scanTurnsToRedo(settled, saved, rule = true))
        assertEquals(emptyMap<Int, Int>(), scanTurnsToRedo(settled, saved, rule = false))
        assertEquals(emptyMap<Int, Int>(), scanTurnsToRedo(settled, listOf(null, null, null, null, null), rule = true))
        assertEquals(emptyMap<Int, Int>(), scanTurnsToRedo(emptyList(), emptyList(), rule = true))
    }

    @Test
    fun whereATurnCameFromIsOneShortWord() {
        assertEquals("manual", scanTurnSource(0, followed = false, upright = false))
        assertEquals("manual", scanTurnSource(2, followed = true, upright = true))
        assertEquals("neighbours", scanTurnSource(null, followed = true, upright = true))
        assertEquals("classifier", scanTurnSource(null, followed = false, upright = true))
        assertEquals("off", scanTurnSource(null, followed = false, upright = false))
    }
}
