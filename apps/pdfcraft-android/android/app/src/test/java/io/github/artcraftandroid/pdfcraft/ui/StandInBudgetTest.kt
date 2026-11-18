package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.StandIn
import io.github.artcraftandroid.pdfcraft.ui.viewer.standInsToDrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #685: stand-in images no longer in the cache count in the budget, and go first when it is over. */
class StandInBudgetTest {
    private val mb = 1024L * 1024L
    private val standIns = listOf(
        StandIn("page 1", 6 * mb, tile = false, inView = false),
        StandIn("tile 1/3/4", 1 * mb, tile = true, inView = false),
        StandIn("page 2", 6 * mb, tile = false, inView = true),
        StandIn("tile 2/3/0", 1 * mb, tile = true, inView = true),
        StandIn("tile 1/3/5", 1 * mb, tile = true, inView = false),
    )

    @Test fun withinTheBudgetNothingGoes() {
        assertTrue(standInsToDrop(standIns, 0L).isEmpty())
        assertTrue(standInsToDrop(standIns, -5 * mb).isEmpty())
    }

    @Test fun tilesOutOfViewGoFirstThenPages() {
        assertEquals(listOf("tile 1/3/4"), standInsToDrop(standIns, 1 * mb).map { it.slot })
        assertEquals(listOf("tile 1/3/4", "tile 1/3/5"), standInsToDrop(standIns, 2 * mb).map { it.slot })
        assertEquals(listOf("tile 1/3/4", "tile 1/3/5", "page 1"), standInsToDrop(standIns, 3 * mb).map { it.slot })
    }

    @Test fun whatIsInViewStaysHoweverFarOver() {
        val dropped = standInsToDrop(standIns, 100 * mb)
        assertEquals(setOf("page 1", "tile 1/3/4", "tile 1/3/5"), dropped.map { it.slot }.toSet())
        assertTrue(dropped.none { it.inView })
    }
}
