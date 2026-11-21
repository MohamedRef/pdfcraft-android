package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.scanTestPageJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The document screen's continuous pages (#786): the testing option, the two views, the page they come back to, a page's state, the log and the page file. */
class ScanContinuousTest {
    private val corners = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        val lab = ScanLab()
        assertTrue(lab.continuousPages)
        assertFalse(lab.copy(continuousPages = false).asDesigned)
        assertEquals(ScanLab(continuousPages = false), ScanLab.decode(ScanLab(continuousPages = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto").continuousPages)
        assertEquals(false, ScanLab(continuousPages = false).diag().toMap()["continuous_pages"])
        assertEquals(false, ScanLab(continuousPages = false).json().getBoolean("continuous_pages"))
    }

    @Test
    fun theScreenOpensOnThePagesAndTheGridIsOneSwitchAway() {
        assertEquals(ScanDocView.Pages, scanDocViewShown(null, continuous = true))
        assertEquals(ScanDocView.Grid, scanDocViewShown(ScanDocView.Grid, continuous = true))
        assertEquals(ScanDocView.Pages, scanDocViewShown(ScanDocView.Pages, continuous = true))
        // the option off: the grid alone, whatever was chosen before
        assertEquals(ScanDocView.Grid, scanDocViewShown(null, continuous = false))
        assertEquals(ScanDocView.Grid, scanDocViewShown(ScanDocView.Pages, continuous = false))
    }

    @Test
    fun theViewIsKeptBetweenVisitsAndGoesWithTheScan() {
        val state = ScanDocViewState()
        assertNull(state.chosen)
        state.show(ScanDocView.Grid)
        state.show(ScanDocView.Grid)
        assertEquals(1, state.toggles)
        state.show(ScanDocView.Pages)
        assertEquals(ScanDocView.Pages, state.chosen)
        assertEquals(2, state.toggles)
        state.anchor = ScanDocAnchor("c", 2)
        state.route = "camera"
        state.pageShown("c", ScanDocPageState.Waiting, 100)
        state.reset()
        assertNull(state.chosen)
        assertEquals(ScanDocAnchor(null, 0), state.anchor)
        assertEquals("none", state.route)
        assertEquals(0, state.toggles)
        assertNull(state.record("c"))
    }

    @Test
    fun theAnchorFollowsItsPageWhereverItMoves() {
        val anchor = ScanDocAnchor("c", 2)
        assertEquals(2, scanAnchorIndex(anchor, listOf("a", "b", "c", "d")))
        // moved to the front, or pushed back by a page added before it
        assertEquals(0, scanAnchorIndex(anchor, listOf("c", "a", "b", "d")))
        assertEquals(3, scanAnchorIndex(anchor, listOf("a", "new", "b", "c", "d")))
        // a new scan opens on page 1
        assertEquals(0, scanAnchorIndex(ScanDocAnchor(null, 0), listOf("a", "b", "c", "d")))
    }

    @Test
    fun aRetakenOrDeletedAnchorFallsBackToItsPlace() {
        val anchor = ScanDocAnchor("c", 2)
        // retaken: a new key in the same place
        assertEquals(2, scanAnchorIndex(anchor, listOf("a", "b", "c2", "d")))
        // deleted: the page that took its place
        assertEquals(2, scanAnchorIndex(anchor, listOf("a", "b", "d")))
        // the last page deleted: the new last page
        assertEquals(1, scanAnchorIndex(ScanDocAnchor("d", 3), listOf("a", "b")))
        assertEquals(0, scanAnchorIndex(ScanDocAnchor("d", -4), listOf("a", "b")))
        assertEquals(0, scanAnchorIndex(anchor, emptyList()))
    }

    @Test
    fun leavingTheGridKeepsTheAnchorWhileItIsInSight() {
        assertEquals(2, scanAnchorFromGrid(2, listOf(0, 1, 2, 3)))
        // scrolled away from it: the first page in sight
        assertEquals(8, scanAnchorFromGrid(2, listOf(9, 8, 10, 11)))
        // not laid out yet
        assertEquals(2, scanAnchorFromGrid(2, emptyList()))
    }

    @Test
    fun thePageNamedIsTheOneMostInSight() {
        // a window of 1000: page 1 fills most of it, the top of page 2 shows below
        val top = listOf(ScanDocSpan(0, 0, 800), ScanDocSpan(1, 820, 800))
        assertEquals(0, scanCurrentPage(top, 0, 1000, atStart = true, atEnd = false))
        // scrolled so page 2 has more of the window
        val scrolled = listOf(ScanDocSpan(0, -600, 800), ScanDocSpan(1, 220, 800))
        assertEquals(1, scanCurrentPage(scrolled, 0, 1000, atStart = false, atEnd = false))
        // the same share of the window: the earlier page
        val even = listOf(ScanDocSpan(1, 500, 800), ScanDocSpan(0, -300, 800))
        assertEquals(0, scanCurrentPage(even, 0, 1000, atStart = false, atEnd = false))
        assertNull(scanCurrentPage(emptyList(), 0, 1000, atStart = true, atEnd = true))
    }

    @Test
    fun aShortLastPageIsNamedAtTheEndOfTheList() {
        // page 3 still has more of the window than the short page 4, but the list can go no further
        val end = listOf(ScanDocSpan(2, -300, 900), ScanDocSpan(3, 620, 300))
        assertEquals(2, scanCurrentPage(end, 0, 1000, atStart = false, atEnd = false))
        assertEquals(3, scanCurrentPage(end, 0, 1000, atStart = false, atEnd = true))
        // a scan that fits the window whole is on its first page
        val whole = listOf(ScanDocSpan(0, 0, 400), ScanDocSpan(1, 420, 300))
        assertEquals(0, scanCurrentPage(whole, 0, 1000, atStart = true, atEnd = true))
    }

    @Test
    fun aPageIsDrawnInItsOwnProportions() {
        assertEquals(0.75f, scanDocPageAspect(600, 800), 0.001f)
        // the photo's part turned a quarter: its sides change places
        assertEquals(800f / 600f, scanDocPageAspect(600, 800, 1), 0.001f)
        assertEquals(800f / 600f, scanDocPageAspect(600, 800, -1), 0.001f)
        assertEquals(0.75f, scanDocPageAspect(600, 800, 2), 0.001f)
        // a long receipt and a wide strip stay within reach
        assertEquals(0.25f, scanDocPageAspect(100, 2000), 0.001f)
        assertEquals(4f, scanDocPageAspect(2000, 100), 0.001f)
        assertEquals(SCAN_DOC_PAGE_ASPECT, scanDocPageAspect(0, 800), 0.001f)
    }

    @Test
    fun onlyAPageMadeAsItIsNowIsReady() {
        assertEquals(ScanDocPageState.Ready, scanDocPageState(background = true, ready = true, working = false, failed = false))
        // being made again after a change (its last result is not the page as it is now): not ready
        assertEquals(ScanDocPageState.Working, scanDocPageState(background = true, ready = false, working = true, failed = false))
        assertEquals(ScanDocPageState.Waiting, scanDocPageState(background = true, ready = false, working = false, failed = false))
        assertEquals(ScanDocPageState.Failed, scanDocPageState(background = true, ready = false, working = false, failed = true))
        // tried again: being made wins over the old failure only once the failure is cleared
        assertEquals(ScanDocPageState.Working, scanDocPageState(background = true, ready = false, working = true, failed = false))
        // pages are not made in the background: the photo, never taken for the page
        assertEquals(ScanDocPageState.Photo, scanDocPageState(background = false, ready = false, working = false, failed = false))
        assertEquals(ScanDocPageState.Photo, scanDocPageState(background = false, ready = true, working = false, failed = false))
    }

    @Test
    fun aPageOffersCheckOnlyWhenTheUnsureCheckCanRun() {
        assertTrue(scanDocPageChecks(unsure = true, checkUnsure = true, batchBorders = true))
        assertFalse(scanDocPageChecks(unsure = false, checkUnsure = true, batchBorders = true))
        assertFalse(scanDocPageChecks(unsure = true, checkUnsure = false, batchBorders = true))
        assertFalse(scanDocPageChecks(unsure = true, checkUnsure = true, batchBorders = false))
    }

    @Test
    fun theLogSaysTheViewTheRouteAndThePage() {
        val shown = scanDocShownDiag(pages = 4, made = 1, background = true, view = "pages", route = "camera", anchor = 0).toMap()
        assertEquals("pages", shown["view"])
        assertEquals("camera", shown["route"])
        assertEquals(1, shown["anchor"])
        assertEquals(3, shown["waiting"])
        val switched = scanDocViewDiag(ScanDocView.Grid, ScanDocView.Pages, anchor = 2, pages = 4, by = "button").toMap()
        assertEquals("grid", switched["to"])
        assertEquals("pages", switched["from"])
        assertEquals(3, switched["page"])
        assertEquals(4, switched["pages"])
        assertEquals("button", switched["by"])
        val ready = scanDocPageReadyDiag(page = 0, pages = 4, ms = 2400, view = ScanDocView.Pages, openedAs = ScanDocPageState.Working).toMap()
        assertEquals(1, ready["page"])
        assertEquals(2400L, ready["ms"])
        assertEquals("working", ready["opened_as"])
        val all = scanDocAllReadyDiag(pages = 4, failed = 1, ms = -5, view = ScanDocView.Grid).toMap()
        assertEquals(1, all["failed"])
        assertEquals(0L, all["ms"])
        assertEquals("grid", all["view"])
        // short lowercase words, numbers and booleans only
        for (value in shown.values + switched.values + ready.values + all.values) {
            assertTrue(value is Boolean || value is Int || value is Long || (value is String && value.all { it in 'a'..'z' }))
        }
    }

    @Test
    fun aPagesFirstDrawingAndItsMakingAreRecordedOnce() {
        val state = ScanDocViewState()
        state.pageShown("a", ScanDocPageState.Waiting, 1_000)
        // drawn again after scrolling back: the first time stands
        state.pageShown("a", ScanDocPageState.Ready, 5_000)
        assertEquals(-1L, state.record("a")?.readyMs)
        state.pageReady("a", 3_400)
        state.pageReady("a", 9_000)
        assertEquals(ScanDocPageRecord("waiting", 1_000, 3_400), state.record("a"))
        assertEquals(2_400L, state.record("a")?.readyMs)
        // made already when it was first drawn
        state.pageShown("b", ScanDocPageState.Ready, 2_000)
        assertEquals(0L, state.record("b")?.readyMs)
        // never drawn: nothing to say
        state.pageReady("c", 2_000)
        assertNull(state.record("c"))
    }

    @Test
    fun aKeptPageSaysTheViewAndWhatItShowed() {
        val state = ScanDocViewState()
        state.route = "camera"
        state.show(ScanDocView.Grid)
        state.pageShown("a", ScanDocPageState.Working, 1_000)
        state.pageReady("a", 1_900)
        val json = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 60, true, ScanLab(), "page-01.out.jpg", null, null, documentView = state.json("a", continuous = true)))
        val view = json.getJSONObject("document_view")
        assertEquals("grid", view.getString("view"))
        assertEquals("camera", view.getString("route"))
        assertEquals(1, view.getInt("toggles"))
        assertTrue(view.getBoolean("shown"))
        assertEquals("working", view.getString("shown_as"))
        assertEquals(900L, view.getLong("ready_ms"))
        assertTrue(json.getJSONObject("options").getBoolean("continuous_pages"))
        // a page the continuous view never drew
        val other = state.json("z", continuous = true)!!
        assertFalse(other.getBoolean("shown"))
        assertTrue(other.isNull("shown_as"))
        assertEquals(-1L, other.getLong("ready_ms"))
        // the option off: nothing about the view
        assertNull(state.json("a", continuous = false))
        val none = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 60, true, ScanLab(), "page-01.out.jpg", null, null))
        assertTrue(none.isNull("document_view"))
    }
}
