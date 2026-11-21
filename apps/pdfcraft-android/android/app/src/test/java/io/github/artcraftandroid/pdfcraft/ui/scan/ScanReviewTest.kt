package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanFilter
import io.github.artcraftandroid.pdfcraft.ui.ScanFinding
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.ScanPage
import io.github.artcraftandroid.pdfcraft.ui.ScanSession
import io.github.artcraftandroid.pdfcraft.ui.scanTestPageJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The review of pages whose borders need a check (#783). Drawn numbers only: no photo is read. */
class ScanReviewTest {
    private val quad = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
    private val moved = floatArrayOf(0.12f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
    private fun found(confidence: Double) = ScanFinding("model", confidence, 0, 1, 4, 0)

    // the sheet check's usual answer on a pile of sheets: nothing found
    private val nothing = ScanFinding("none", 0.39, 0, 7, 0, 0)

    private fun state(corners: FloatArray, found: FloatArray?, uncropped: FloatArray?, finding: ScanFinding?, visits: Int = 0, searched: Boolean = true, kept: ScanReviewState? = null) =
        scanReviewState(corners, found, uncropped, finding, visits, searched, kept)

    @Test
    fun aSearchedPageIsSureUnsureOrNotFoundUntilSomeoneLooks() {
        assertEquals(ScanReviewState.Sure, state(quad, quad, null, found(0.88)))
        assertEquals(ScanReviewState.Sure, state(quad, quad, null, found(0.7)))
        assertEquals(ScanReviewState.Unsure, state(quad, quad, null, found(0.61)))
        assertEquals(ScanReviewState.NotFound, state(ScanPage.WHOLE, null, ScanPage.WHOLE, nothing))
        // a page that kept no start corners counts from the whole photo
        assertEquals(ScanReviewState.NotFound, state(ScanPage.WHOLE, null, null, nothing))
        // corners moved, or Adjust borders left with Done
        assertEquals(ScanReviewState.Checked, state(moved, quad, null, found(0.61)))
        assertEquals(ScanReviewState.Checked, state(quad, quad, null, found(0.61), visits = 1))
        assertEquals(ScanReviewState.Checked, state(moved, null, ScanPage.WHOLE, nothing))
        assertEquals(ScanReviewState.Checked, state(ScanPage.WHOLE, null, ScanPage.WHOLE, nothing, visits = 1))
        // a sure outline moved by hand is checked too, never taken back to sure
        assertEquals(ScanReviewState.Checked, state(moved, quad, null, found(0.88)))
    }

    @Test
    fun theReviewAgreesWithTheUnsureRuleOnEveryPageSearchedInThisRun() {
        val cases = listOf(
            Triple(quad, quad, found(0.56)), Triple(quad, quad, found(0.7)), Triple(quad, quad, found(0.88)), Triple(moved, quad, found(0.61)),
            Triple(ScanPage.WHOLE, null, nothing), Triple(moved, null, nothing),
        )
        for ((corners, outline, finding) in cases) for (visits in 0..1) {
            val uncropped = if (outline == null) ScanPage.WHOLE else null
            assertEquals(
                "$visits ${finding.confidence}", scanPageUnsure(corners, outline, uncropped, finding, visits),
                state(corners, outline, uncropped, finding, visits).needsReview,
            )
        }
    }

    @Test
    fun aPageWaitsWhileItsSearchRunsAndAFailedSearchIsNotTakenForAWholePhotoOnPurpose() {
        // taken a moment ago: the search has not answered
        assertEquals(ScanReviewState.Pending, state(ScanPage.WHOLE, null, null, null, searched = false))
        assertFalse(ScanReviewState.Pending.needsReview)
        // the search failed: the page is the whole photo and nobody chose that
        assertEquals(ScanReviewState.Failed, state(ScanPage.WHOLE, null, ScanPage.WHOLE, null))
        assertTrue(ScanReviewState.Failed.needsReview)
        assertEquals(ScanReviewState.Checked, state(moved, null, ScanPage.WHOLE, null))
        // a low-light merge comes straightened: no search, nothing to check
        assertEquals(ScanReviewState.NoSearch, state(ScanPage.WHOLE, null, null, null))
        assertFalse(ScanReviewState.NoSearch.needsReview)
        // #758's rule does not see a failed search; with the review off it is not offered, as before
        assertFalse(scanPageUnsure(ScanPage.WHOLE, null, ScanPage.WHOLE, null, 0))
    }

    @Test
    fun aReopenedDraftKeepsWhatWasKnownAndADraftFromBeforeKnowsNothing() {
        // reopened: no search report, the draft's word stands
        assertEquals(ScanReviewState.Unsure, state(quad, quad, null, null, searched = false, kept = ScanReviewState.Unsure))
        assertEquals(ScanReviewState.NotFound, state(ScanPage.WHOLE, null, ScanPage.WHOLE, null, searched = false, kept = ScanReviewState.NotFound))
        assertEquals(ScanReviewState.Failed, state(ScanPage.WHOLE, null, ScanPage.WHOLE, null, searched = false, kept = ScanReviewState.Failed))
        assertEquals(ScanReviewState.Sure, state(quad, quad, null, null, searched = false, kept = ScanReviewState.Sure))
        // a crop confirmed with Done and not moved stays checked after the draft is reopened
        assertEquals(ScanReviewState.Checked, state(quad, quad, null, null, searched = false, kept = ScanReviewState.Checked))
        // corners moved after the draft was reopened
        assertEquals(ScanReviewState.Checked, state(moved, quad, null, null, searched = false, kept = ScanReviewState.Unsure))
        assertEquals(ScanReviewState.Checked, state(quad, quad, null, null, visits = 1, searched = false, kept = ScanReviewState.Unsure))
        // a half of a book spread keeps no start corners: its draft's word stands
        assertEquals(ScanReviewState.NotFound, state(moved, null, null, null, searched = false, kept = ScanReviewState.NotFound))
        // a draft from before the review, or one whose search never answered: unknown, not confirmed and not offered
        assertEquals(ScanReviewState.Unknown, state(quad, quad, null, null, searched = false, kept = ScanReviewState.Unknown))
        assertEquals(ScanReviewState.Unknown, state(ScanPage.WHOLE, null, ScanPage.WHOLE, null, searched = false, kept = ScanReviewState.Pending))
        assertFalse(ScanReviewState.Unknown.needsReview)
        assertFalse(ScanReviewState.Checked.needsReview)
        assertTrue(ScanReviewState.Unknown != ScanReviewState.Checked)
    }

    @Test
    fun theStatesHaveTheirWords() {
        assertEquals(
            listOf("pending", "sure", "unsure", "not_found", "failed", "checked", "none", "unknown"),
            ScanReviewState.entries.map { it.id },
        )
        for (s in ScanReviewState.entries) assertEquals(s, ScanReviewState.of(s.id))
        assertNull(ScanReviewState.of(null))
        assertNull(ScanReviewState.of("later_word"))
        assertEquals(listOf(ScanReviewState.Unsure, ScanReviewState.NotFound, ScanReviewState.Failed), ScanReviewState.entries.filter { it.needsReview })
    }

    @Test
    fun aDraftKeepsEachPagesReviewStateAndOldDraftsStillOpen() {
        val look = ScanPageLook(ScanFilter.Grayscale)
        val s = ScanSession(
            null, null, false, "Scan",
            listOf(
                ScanSession.Page("/c/a.jpg", quad, quad, look = look, review = "checked"),
                ScanSession.Page("/c/b.jpg", ScanPage.WHOLE, null, review = "not_found"),
                ScanSession.Page("/c/c.jpg", quad, quad, 1, "receipt", look = look, auto = ScanAutoMarks(greyApplied = true), review = "unsure"),
                ScanSession.Page("/c/d.jpg", quad, quad),
            ),
        )
        val text = s.encode()
        val back = ScanSession.decode(text)!!
        assertEquals(listOf("checked", "not_found", "unsure", null), back.pages.map { it.review })
        // the lines after it still belong to the same page
        assertEquals(look, back.pages[0].look)
        assertEquals(look, back.pages[2].look)
        assertTrue(back.pages[2].auto.greyApplied)
        assertEquals("receipt", back.pages[2].preset)
        // written twice, the same text
        assertEquals(text, back.encode())
        // a draft from before the review has no such line and reads as before
        val old = ScanSession(null, null, false, "Scan", s.pages.map { ScanSession.Page(it.path, it.corners, it.found, it.turns, it.preset, look = it.look) })
        assertFalse(old.encode().contains("review"))
        assertEquals(listOf<String?>(null, null, null, null), ScanSession.decode(old.encode())!!.pages.map { it.review })
        // a build from before the review skips the line: taken out, the rest is the old draft's text
        assertEquals(old.encode(), ScanSession(null, null, false, "Scan", s.pages.map { ScanSession.Page(it.path, it.corners, it.found, it.turns, it.preset, look = it.look, review = it.review) }).encode().lines().filterNot { it.startsWith("review\t") }.joinToString("\n"))
        // a word that could break a line is not written
        assertFalse(ScanSession(null, null, false, "Scan", listOf(ScanSession.Page("/c/a.jpg", quad, quad, review = "un\tsure"))).encode().contains("review"))
        // a review line with no page before it is skipped
        assertTrue(ScanSession.decode("pdfcraft-scan 2\nreview\tunsure\n")!!.pages.isEmpty())
    }

    @Test
    fun pagesTwoAndSevenOfTenAreVisitedInOrderAndTheOthersAreLeftAlone() {
        val keys = List(10) { "p${it + 1}" }
        val needing = setOf("p7", "p2")
        val pass = ScanBatchPass(ScanBatchWhat.Borders, scanBatchKeys(needing, keys), 0, 0L, from = "unsure")
        assertTrue(pass.review)
        assertEquals(listOf("p2", "p7"), pass.keys)
        assertEquals("p2", pass.key)
        val second = scanBatchStep(pass, 1)!!
        assertEquals("p7", second.key)
        // past the last flagged page the review ends
        assertNull(scanBatchStep(second, 1))
        // a page deleted meanwhile is found by its key, never by its place: the pass's page is gone, the others keep theirs
        val after = keys - "p2"
        assertEquals(-1, after.indexOf(pass.key!!))
        assertEquals(5, after.indexOf(second.key!!))
        // moved pages are visited in the scan's new order
        assertEquals(listOf("p7", "p2"), scanBatchKeys(needing, listOf("p7") + (keys - "p7")))
        // a pass over chosen pages is not a review
        assertFalse(ScanBatchPass(ScanBatchWhat.Borders, keys, 0, 0L).review)
        assertFalse(scanBatchIsReview("selection"))
        assertFalse(scanBatchIsReview("offer"))
        assertTrue(scanBatchIsReview("save"))
    }

    @Test
    fun theOfferComesBeforeTheImportsWhileAFlaggedPageIsLeft() {
        val needing = setOf("a", "b")
        assertTrue(scanReviewOfferFirst(true, true, true, true, needing, emptySet()))
        // closed: the import's offer is back; the pages stay flagged
        assertFalse(scanReviewOfferFirst(true, true, true, true, needing, needing))
        assertFalse(scanReviewOfferFirst(true, true, true, true, emptySet(), emptySet()))
        // off, or without what it needs: as before
        assertFalse(scanReviewOfferFirst(false, true, true, true, needing, emptySet()))
        assertFalse(scanReviewOfferFirst(true, false, true, true, needing, emptySet()))
        assertFalse(scanReviewOfferFirst(true, true, false, true, needing, emptySet()))
        assertFalse(scanReviewOfferFirst(true, true, true, false, needing, emptySet()))
    }

    @Test
    fun saveAsksAboutFlaggedPagesOnceAndClosingTheOfferDoesNotConfirmThem() {
        val needing = listOf("p2", "p7")
        assertEquals(needing, scanReviewSaveAsks(true, true, true, true, needing, emptySet()))
        // Save anyway was chosen for page 2: only a page flagged since is asked about
        assertEquals(listOf("p7"), scanReviewSaveAsks(true, true, true, true, needing, setOf("p2")))
        assertEquals(emptyList<String>(), scanReviewSaveAsks(true, true, true, true, needing, needing.toSet()))
        assertEquals(emptyList<String>(), scanReviewSaveAsks(true, true, true, true, emptyList(), emptySet()))
        // the offer's dismissed pages are no argument here: a closed offer leaves Save asking
        // off, or without the pass it would open: nothing is asked, as before
        assertEquals(emptyList<String>(), scanReviewSaveAsks(false, true, true, true, needing, emptySet()))
        assertEquals(emptyList<String>(), scanReviewSaveAsks(true, false, true, true, needing, emptySet()))
        assertEquals(emptyList<String>(), scanReviewSaveAsks(true, true, false, true, needing, emptySet()))
        assertEquals(emptyList<String>(), scanReviewSaveAsks(true, true, true, false, needing, emptySet()))
    }

    @Test
    fun backAsksOnlyAboutCornersMovedOnThePageShown() {
        assertTrue(scanBatchBackAsks(true, quad, moved))
        assertFalse(scanBatchBackAsks(true, quad, quad.copyOf()))
        assertFalse(scanBatchBackAsks(false, quad, moved))
        assertEquals("confirmed", scanReviewOutcome(left = true, changed = false, back = false))
        assertEquals("changed", scanReviewOutcome(left = true, changed = true, back = false))
        assertEquals("back_kept", scanReviewOutcome(left = true, changed = true, back = true))
        assertEquals("back_dropped", scanReviewOutcome(left = false, changed = true, back = true))
        assertEquals("back", scanReviewOutcome(left = false, changed = false, back = true))
    }

    @Test
    fun aSearchThatAnswersLateLeavesCornersThePersonSet() {
        // untouched: the search's answer is taken
        assertTrue(scanSearchMovesCorners(true, ScanPage.WHOLE, ScanPage.WHOLE, 0))
        // moved, or confirmed with Done (Whole photo on purpose): it is not
        assertFalse(scanSearchMovesCorners(true, moved, ScanPage.WHOLE, 0))
        assertFalse(scanSearchMovesCorners(true, ScanPage.WHOLE, ScanPage.WHOLE, 1))
        // off: the answer replaces them, as before
        assertTrue(scanSearchMovesCorners(false, moved, ScanPage.WHOLE, 1))
        // the page then reads as checked, whatever the search found
        assertEquals(ScanReviewState.Checked, state(moved, quad, null, found(0.61)))
        assertEquals(ScanReviewState.Checked, state(ScanPage.WHOLE, quad, null, found(0.9), visits = 1))
    }

    @Test
    fun theLogLinesCarryCountsAndPlacesOnly() {
        val open = scanReviewOpenDiag(pages = 3, notFound = 1, failed = 1, firstPage = 2, scanPages = 10, from = "unsure").toMap()
        assertEquals(3, open["pages"])
        assertEquals(1, open["not_found"])
        assertEquals(1, open["failed"])
        assertEquals(1, open["low_confidence"])
        assertEquals(2, open["first_page"])
        assertEquals(10, open["scan_pages"])
        assertEquals("unsure", open["from"])
        val page = scanReviewPageDiag(at = 2, of = 2, page = 7, scanPages = 10, state = ScanReviewState.NotFound, outcome = "changed", ms = 4_200L).toMap()
        assertEquals(2, page["at"])
        assertEquals(2, page["of"])
        assertEquals(7, page["page"])
        assertEquals("not_found", page["state"])
        assertEquals("changed", page["outcome"])
        assertEquals(4_200L, page["ms"])
        val pass = ScanBatchPass(ScanBatchWhat.Borders, listOf("p2", "p7"), 1, 0L, shown = setOf("p2", "p7"), changed = setOf("p7"), from = "save")
        val done = scanReviewDoneDiag(pass, left = 0, ms = 9_000L, how = "done").toMap()
        assertEquals(2, done["pages"])
        assertEquals(2, done["shown"])
        assertEquals(1, done["changed"])
        assertEquals(0, done["left"])
        assertEquals(9_000L, done["ms"])
        assertEquals("done", done["how"])
        assertEquals("save", done["from"])
        val ask = scanReviewSaveDiag(2, 10, "save").toMap()
        assertEquals(setOf("pages", "scan_pages", "out"), ask.keys)
        val answer = scanReviewSaveDiag(2, 10, "share", "check").toMap()
        assertEquals("check", answer["answer"])
        assertEquals("share", answer["out"])
    }

    @Test
    fun theKeptPageSaysWhatWasKnownOfItsBorders() {
        val flagged = JSONObject(scanTestPageJson("t", true, ScanPage.WHOLE, null, nothing, "auto", 60, false, ScanLab(), null, null, review = ScanReviewState.NotFound.id))
        assertEquals("not_found", flagged.getString("review"))
        assertTrue(flagged.getJSONObject("options").getBoolean("review_borders"))
        assertTrue(JSONObject(scanTestPageJson("t", true, quad, quad, null, "auto", 60, false, ScanLab(), null, null)).isNull("review"))
    }

    @Test
    fun theTestingOptionIsOnAndRemembered() {
        assertTrue(ScanLab().reviewBorders)
        val off = ScanLab(reviewBorders = false)
        assertFalse(ScanLab.decode(off.encode()).reviewBorders)
        assertEquals(off, ScanLab.decode(off.encode()))
        assertFalse(off.asDesigned)
        assertEquals(false, off.diag().toMap()["review_borders"])
        assertEquals(false, off.json().getBoolean("review_borders"))
        // a phone that saved its options before the review has it on
        assertTrue(ScanLab.decode("keep=0;batch=1;check_unsure=1").reviewBorders)
    }
}
