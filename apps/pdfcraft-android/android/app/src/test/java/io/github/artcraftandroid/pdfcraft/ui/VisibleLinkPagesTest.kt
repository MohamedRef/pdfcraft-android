package io.github.artcraftandroid.pdfcraft.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VisibleLinkPagesTest {
    @Test fun onlyDisplayedPagesAreLoaded() {
        assertEquals(listOf(10, 12), missingVisibleLinkPages(10..12, 10000, setOf(11)))
        assertEquals(listOf(10, 11), missingVisibleLinkPages(10..50, 10000, emptySet(), maxBatch = 2))
    }

    @Test fun nothingOutOfRangeOrAlreadyLoadedIsRequested() {
        assertEquals(listOf(0, 1), missingVisibleLinkPages(-10..1, 2, emptySet()))
        assertEquals(emptyList<Int>(), missingVisibleLinkPages(999..1005, 200, emptySet()))
        assertEquals(emptyList<Int>(), missingVisibleLinkPages(0..3, 4, setOf(0, 1, 2, 3)))
        assertEquals(emptyList<Int>(), missingVisibleLinkPages(0..3, 4, emptySet(), maxBatch = 0))
    }
    @Test fun everyVisiblePageCanBeHydratedAcrossBatches() {
        val loaded = HashSet<Int>()
        repeat(3) { loaded.addAll(missingVisibleLinkPages(0..29, 10000, loaded)) }
        assertEquals((0..29).toSet(), loaded)
        assertEquals(emptyList<Int>(), missingVisibleLinkPages(0..29, 10000, loaded))
        loaded.clear() // an edit invalidates the previous generation
        assertEquals((0..11).toList(), missingVisibleLinkPages(0..29, 10000, loaded))
    }

    @Test fun hostileViewportBoundsAreClampedBeforeIteration() {
        assertEquals(listOf(0, 1), missingVisibleLinkPages(Int.MIN_VALUE..Int.MAX_VALUE, 2, emptySet()))
        assertEquals(emptyList<Int>(), missingVisibleLinkPages(5..1, 10, emptySet()))
    }
}
