package io.github.artcraftandroid.pdfcraft.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** #634: what the busy dialog says for each step the engine reports while Compress runs. */
class CompressStepTest {
    @Test
    fun pagesAndImagesAreCountedFromOne() {
        // the engine says how many are done: the one in hand is the next
        assertEquals(CompressStep.Reading(1, 869), compressStep("reading", 0, 869, 1))
        assertEquals(CompressStep.Reading(120, 869), compressStep("reading", 119, 869, 1))
        assertEquals(CompressStep.Images(13, 240), compressStep("images", 12, 240, 1))
    }

    @Test
    fun theCountNeverPassesItsTotal() {
        assertEquals(CompressStep.Reading(869, 869), compressStep("reading", 869, 869, 1))
        assertEquals(CompressStep.Images(240, 240), compressStep("images", 240, 240, 1))
    }

    @Test
    fun aStepWithoutACountShowsNone() {
        for (stage in listOf("discarding", "fragments", "cleaning_up", "merging", "writing", "")) {
            assertEquals(stage, CompressStep.Working, compressStep(stage, 0, 0, 1))
        }
        // a document with no images, and a stage this app does not know yet
        assertEquals(CompressStep.Working, compressStep("images", 0, 0, 1))
        assertEquals(CompressStep.Working, compressStep("something_new", 3, 10, 1))
    }

    @Test
    fun aSecondRunForATargetSizeSaysSo() {
        assertEquals(CompressStep.Again(2), compressStep("images", 3, 10, 2))
        assertEquals(CompressStep.Again(4), compressStep("writing", 0, 0, 4))
    }
}
