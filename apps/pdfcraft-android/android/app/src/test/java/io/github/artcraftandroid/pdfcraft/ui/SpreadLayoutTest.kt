package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.DocLayout
import io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode
import io.github.artcraftandroid.pdfcraft.ui.viewer.PageSize
import io.github.artcraftandroid.pdfcraft.ui.viewer.Spread
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerState
import io.github.artcraftandroid.pdfcraft.ui.viewer.spreadFits
import io.github.artcraftandroid.pdfcraft.ui.viewer.spreadGroups
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewLayout
import io.github.artcraftandroid.pdfcraft.ui.viewer.shownSpread
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpreadLayoutTest {
    private val letter = PageSize(612f, 792f)
    private val landscape = PageSize(792f, 612f)

    @Test
    fun groupsPairPagesWithAndWithoutACover() {
        assertEquals(listOf(0..0, 1..1, 2..2), spreadGroups(3, Spread.Single))
        assertEquals(listOf(0..1, 2..3, 4..4), spreadGroups(5, Spread.TwoUp))
        assertEquals(listOf(0..0, 1..2, 3..4), spreadGroups(5, Spread.TwoUpCover))
        assertEquals(listOf(0..0, 1..2, 3..3), spreadGroups(4, Spread.TwoUpCover))
        assertTrue(spreadGroups(0, Spread.TwoUp).isEmpty())
    }

    @Test
    fun singleSpreadLaysOutExactlyAsBefore() {
        // the old one-column layout, re-derived: the widest page fills the width, pages stack
        val pages = listOf(letter, landscape, letter)
        for (mode in PageMode.entries) {
            val a = DocLayout(pages, mode, 1080f, 2000f, gap = 20f, margin = 8f, padTop = 100f, padBottom = 50f)
            val b = DocLayout(pages, mode, 1080f, 2000f, gap = 20f, margin = 8f, padTop = 100f, padBottom = 50f, spread = Spread.Single)
            assertEquals(a.rects, b.rects)
            assertEquals(a.height, b.height, 0f)
        }
        val l = DocLayout(pages, PageMode.Continuous, 1080f, 2000f, gap = 20f, margin = 0f, padTop = 100f, padBottom = 50f)
        assertEquals(1080f, l.rects[1].width, 0.01f)
        assertEquals(l.rects[0].bottom + 20f, l.rects[1].top, 0.01f)
    }

    @Test
    fun continuousTwoUpPutsPagesSideBySide() {
        val l = DocLayout(List(5) { letter }, PageMode.Continuous, 2000f, 1200f, gap = 20f, margin = 0f, padTop = 0f, padBottom = 0f, spread = Spread.TwoUp)
        val (a, b) = l.rects[0] to l.rects[1]
        assertEquals(a.top, b.top, 0f)
        assertEquals(a.right + 20f, b.left, 0.01f)
        assertEquals(2000f, b.right - a.left, 0.5f)
        // page 3 starts the next row; page 5 sits alone, centred
        assertEquals(a.bottom + 20f, l.rects[2].top, 0.01f)
        assertEquals(1000f, (l.rects[4].left + l.rects[4].right) / 2f, 0.5f)
        // hit testing finds the right page of a row
        assertEquals(1, l.pageAt(b.left + 5f, b.top + 5f))
        assertEquals(0, l.pageAt(a.left + 5f, a.top + 5f))
        assertEquals(2..3, l.visible(0f, l.rects[2].top + 1f, 2000f, l.rects[2].top + 2f))
    }

    @Test
    fun coverShowsThePageAloneThenPairs() {
        val l = DocLayout(List(5) { letter }, PageMode.SinglePage, 2000f, 1200f, gap = 20f, margin = 0f, padTop = 0f, padBottom = 0f, spread = Spread.TwoUpCover)
        assertEquals(3, l.groups.size)
        assertEquals(3 * 2000f, l.width, 0f)
        // the cover in the first slot, pages 2 and 3 side by side in the second
        assertTrue(l.rects[0].right <= 2000f)
        assertTrue(l.rects[1].left >= 2000f && l.rects[2].right <= 4000f)
        assertEquals(l.rects[1].right + 20f, l.rects[2].left, 0.01f)
        assertEquals(2, l.nearestPage(l.rects[2].left + 1f, 10f))
        assertEquals(1, l.groupOf(2))
    }

    @Test
    fun turningPagesGoesSpreadBySpreadAndKeepsThePageAcrossSwitches() {
        val v = ViewerState(List(9) { letter }).apply { gap = 20f; setViewport(2000f, 1200f) }
        v.switchMode(PageMode.SinglePage)
        v.showSpread(Spread.TwoUpCover)
        assertEquals(0, v.currentPage)
        v.turnPage(1)
        assertEquals(1, v.currentPage)
        v.turnPage(1)
        assertEquals(3, v.currentPage)
        v.turnPage(-1)
        assertEquals(1, v.currentPage)
        v.goToPage(6)
        assertEquals(5, v.currentPage) // 6 is the right-hand page of 5-6
        assertEquals(2000f * 4, v.snapTarget(-10_000f), 0f) // 5-6 is the fourth slot; a swipe goes to 7-8
        // back to one page: the reader stays at the spread's page
        v.showSpread(Spread.Single)
        assertEquals(5, v.currentPage)
        v.turnPage(1)
        assertEquals(6, v.currentPage)
    }

    @Test
    fun onlyWideWindowsShowTwoPages() {
        assertFalse(spreadFits(411))
        assertTrue(spreadFits(600))
        assertTrue(spreadFits(1280))
    }

    @Test
    fun horizontalPutsPagesInARowAsTallAsTheScreen() {
        val l = DocLayout(listOf(letter, landscape, letter), PageMode.Horizontal, 1000f, 800f, gap = 10f, margin = 5f, padTop = 50f, padBottom = 50f)
        // the tallest page fills the height less the paddings and margins
        val s = (800f - 100f - 10f) / 792f
        assertEquals(690f, l.rects[0].height, 0.01f)
        assertEquals(5f, l.rects[0].left, 0.01f)
        assertEquals(l.rects[0].right + 10f, l.rects[1].left, 0.01f)
        assertEquals(792f * s, l.rects[1].width, 0.01f)
        // a shorter page is centred in the height
        assertEquals(55f + (690f - 612f * s) / 2f, l.rects[1].top, 0.01f)
        assertEquals(l.rects[2].right + 5f, l.width, 0.01f)
        assertEquals(1, l.pageAt(l.rects[1].left + 1f, 400f))
        assertEquals(1..2, l.visible(l.rects[1].left + 1f, 0f, l.rects[2].left + 1f, 800f))
        assertEquals(2, l.nearestPage(l.rects[2].left - 2f, 0f))
    }

    @Test
    fun horizontalScrollsSidewaysAndKeepsThePage() {
        val v = ViewerState(List(10) { letter }).apply { gap = 20f; setViewport(1000f, 800f) }
        v.goToPage(4)
        v.switchMode(PageMode.Horizontal)
        assertEquals(4, v.currentPage)
        assertEquals(0f, v.scrollY, 0.01f)
        val x = v.scrollX
        v.scrollBy(-v.layout.rects[0].width - 20f, 0f)
        assertEquals(5, v.currentPage)
        assertTrue(v.scrollX > x)
        v.setViewport(1200f, 700f)
        assertEquals(5, v.currentPage)
        v.switchMode(PageMode.Continuous)
        assertEquals(5, v.currentPage)
    }

    @Test
    fun automaticTwoPagesOnlyOnWideLandscapeWindows() {
        assertEquals(Spread.TwoUp, shownSpread(Spread.Single, auto = true, widthDp = 1280, heightDp = 800))
        assertEquals(Spread.Single, shownSpread(Spread.TwoUp, auto = true, widthDp = 800, heightDp = 1280))
        assertEquals(Spread.Single, shownSpread(Spread.TwoUp, auto = true, widthDp = 700, heightDp = 400))
        // without automatic, the choice holds wherever two pages fit
        assertEquals(Spread.TwoUpCover, shownSpread(Spread.TwoUpCover, auto = false, widthDp = 700, heightDp = 1000))
        assertEquals(Spread.Single, shownSpread(Spread.TwoUp, auto = false, widthDp = 400, heightDp = 800))
    }

    @Test
    fun aDocumentsLayoutIsRememberedAndWrittenAsItsInitialView() {
        for (l in listOf(
            ViewLayout(PageMode.Horizontal, Spread.TwoUp),
            ViewLayout(PageMode.SinglePage, Spread.TwoUpCover),
            ViewLayout(PageMode.Continuous, Spread.Single, autoSpread = true),
        )) assertEquals(l, ViewLayout.decode(l.encode()))
        assertNull(ViewLayout.decode(null))
        assertNull(ViewLayout.decode("Sideways:TwoUp"))
        assertEquals(OpenLayout.TwoUpContinuousCover, ViewLayout(PageMode.Continuous, Spread.TwoUpCover).openLayout())
        assertEquals(OpenLayout.TwoUp, ViewLayout(PageMode.SinglePage, Spread.TwoUp).openLayout())
        assertNull(ViewLayout(PageMode.Horizontal, Spread.Single).openLayout())
        assertNull(ViewLayout(PageMode.Continuous, Spread.Single, autoSpread = true).openLayout())
        // what the file asks for maps back to the same layout
        for (open in OpenLayout.entries) ViewLayout.of(open)?.let { assertEquals(open, it.openLayout()) }
        assertNull(ViewLayout.of(OpenLayout.Default))
    }
}
