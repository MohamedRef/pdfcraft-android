package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.core.ScanFindOptions
import io.github.artcraftandroid.pdfcraft.core.ScanFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanCropDetectTest {
    private val auto = ScanFindOptions(ScanFinder.AUTO, true)
    private val lines = ScanFindOptions(ScanFinder.LINES, true)

    @Test
    fun aPhotoImportedWithNothingFoundIsNotSearchedTheSameWayAgain() {
        // the owner's log: the import found nothing, then seven presses made the same search (#443)
        val empty = scanCropDetectEmptyAtOpen(null, auto)
        assertEquals(setOf(auto), empty)
        assertEquals(ScanCropDetectPlan.Search(lines, other = true), scanCropDetectPlan(auto, empty, feedback = true))
        // once the line detector has found nothing too, a press does not search at all
        assertEquals(ScanCropDetectPlan.AlreadySearched, scanCropDetectPlan(auto, empty + lines, feedback = true))
    }

    @Test
    fun aPageTheImportFoundIsSearchedAsAsked() {
        assertTrue(scanCropDetectEmptyAtOpen(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), auto).isEmpty())
        // a photo kept from before #443 has no recorded search: the first press searches as asked
        assertTrue(scanCropDetectEmptyAtOpen(null, null).isEmpty())
        assertEquals(ScanCropDetectPlan.Search(auto, other = false), scanCropDetectPlan(auto, emptySet(), feedback = true))
    }

    @Test
    fun theImportsSearchWithOtherOptionsDoesNotCount() {
        // the testing options were changed after the import: the closer look is a different search
        val noRefine = ScanFindOptions(ScanFinder.AUTO, false)
        assertEquals(ScanCropDetectPlan.Search(auto, other = false), scanCropDetectPlan(auto, setOf(noRefine), feedback = true))
    }

    @Test
    fun onlyAutoHasAnotherDetectorToTry() {
        for (finder in listOf(ScanFinder.LINES, ScanFinder.NETWORK, ScanFinder.OFF)) {
            val asked = ScanFindOptions(finder, true)
            assertEquals(ScanCropDetectPlan.Search(asked, other = false), scanCropDetectPlan(asked, emptySet(), feedback = true))
            assertEquals(ScanCropDetectPlan.AlreadySearched, scanCropDetectPlan(asked, setOf(asked), feedback = true))
        }
    }

    @Test
    fun withTheTestingOptionOffEveryPressSearchesAsBefore() {
        assertEquals(ScanCropDetectPlan.Search(auto, other = false), scanCropDetectPlan(auto, setOf(auto, lines), feedback = false))
    }

    @Test
    fun eachOutcomeHasItsLine() {
        assertNull(scanCropDetectNote(found = true, failed = false, other = false))
        assertEquals(ScanCropDetectNote.FoundOther, scanCropDetectNote(found = true, failed = false, other = true))
        assertEquals(ScanCropDetectNote.NoneFound, scanCropDetectNote(found = false, failed = false, other = false))
        assertEquals(ScanCropDetectNote.NoneFoundOther, scanCropDetectNote(found = false, failed = false, other = true))
        assertEquals(ScanCropDetectNote.Failed, scanCropDetectNote(found = false, failed = true, other = false))
        // the log's names are short lowercase words, each its own
        val ids = ScanCropDetectNote.entries.map { it.id }
        assertTrue(ids.all { it.matches(Regex("[a-z_]+")) })
        assertEquals(ids.size, ids.toSet().size)
    }
}
