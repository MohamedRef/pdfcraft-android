package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.DocLayout
import io.github.artcraftandroid.pdfcraft.ui.viewer.FIT_EACH_AUTO_SPREAD
import io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode
import io.github.artcraftandroid.pdfcraft.ui.viewer.PageSize
import io.github.artcraftandroid.pdfcraft.ui.viewer.Spread
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewLayout
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerState
import io.github.artcraftandroid.pdfcraft.ui.viewer.fitEachShown
import io.github.artcraftandroid.pdfcraft.ui.viewer.sameScale
import io.github.artcraftandroid.pdfcraft.ui.viewer.widthSpread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fit every page to the width in the continuous view (#524): pure geometry, on the desktop. */
class FitEachPageTest {
    private val letter = PageSize(612f, 792f)

    /** The issue's document: pages 400, 600 and 300 points wide. */
    private val mixed = listOf(PageSize(400f, 600f), PageSize(600f, 800f), PageSize(300f, 500f))

    private fun layout(pages: List<PageSize>, fitEach: Boolean, spread: Spread = Spread.Single, w: Float = 1080f, margin: Float = 8f) =
        DocLayout(pages, PageMode.Continuous, w, 2000f, gap = 20f, margin = margin, padTop = 100f, padBottom = 50f, spread = spread, fitEach = fitEach)

    @Test
    fun everyPageFillsTheWidthAndKeepsItsShape() {
        val one = layout(mixed, fitEach = false)
        // before: one scale, the widest page fills the width and the others are narrower
        assertEquals(1064f, one.rects[1].width, 0.01f)
        assertEquals(1064f * 400f / 600f, one.rects[0].width, 0.01f)
        assertEquals(1064f * 300f / 600f, one.rects[2].width, 0.01f)

        val each = layout(mixed, fitEach = true)
        for ((i, p) in mixed.withIndex()) {
            val r = each.rects[i]
            assertEquals(8f, r.left, 0.01f)
            assertEquals(1072f, r.right, 0.01f)
            // its own scale, the same across and down
            assertEquals(1064f / p.width, each.scaleOf(i), 0.0001f)
            assertEquals(p.height / p.width, r.height / r.width, 0.0001f)
        }
        // stacked with the gap between them, from the top padding
        assertEquals(100f, each.rects[0].top, 0.01f)
        assertEquals(each.rects[0].bottom + 20f, each.rects[1].top, 0.01f)
        assertEquals(each.rects[1].bottom + 20f, each.rects[2].top, 0.01f)
        assertEquals(each.rects[2].bottom + 50f, each.height, 0.01f)
    }

    @Test
    fun hitTestingFindsPagesOfMixedScales() {
        val l = layout(mixed, fitEach = true)
        for (i in mixed.indices) {
            val r = l.rects[i]
            assertEquals(i, l.pageAt(r.left + 5f, r.top + 5f))
            assertEquals(i, l.pageAt(r.right - 5f, r.bottom - 5f))
            assertEquals(i, l.nearestPage(540f, (r.top + r.bottom) / 2f))
            assertEquals(i..i, l.visible(0f, r.top + 1f, 1080f, r.top + 2f))
        }
        // in the gap, the nearer page
        assertEquals(0, l.nearestPage(540f, l.rects[0].bottom + 2f))
        assertEquals(1, l.nearestPage(540f, l.rects[1].top - 2f))
        assertEquals(0..2, l.visible(0f, 0f, 1080f, l.height))
    }

    @Test
    fun goToPageThreePutsItsTopAtTheTop() {
        val v = ViewerState(mixed).apply { gap = 20f; margin = 0f; setViewport(1080f, 800f) }
        v.chooseFitEach(true)
        v.goToPage(2)
        assertEquals(2, v.currentPage)
        assertEquals(0f, v.pageRectOnScreen(2).top, 0.01f)
        assertEquals(1080f, v.pageRectOnScreen(2).width, 0.01f)
        // a spot down the page (a link, a bookmark, Find) lands at the screen's top, in its own points
        v.goToPage(1, yPoints = 300f)
        val at = v.screenToPage(Offset(500f, 0f))!!
        assertEquals(1, at.page)
        assertEquals(300f, at.y, 0.05f)
        assertEquals(500f / v.pixelsPerPoint(1), at.x, 0.05f)
        // and back to the screen
        val back = v.pageToScreen(1, at.x, at.y)
        assertEquals(500f, back.x, 0.05f)
        assertEquals(0f, back.y, 0.05f)
    }

    @Test
    fun zoomingStillZoomsThePagesTogether() {
        val v = ViewerState(mixed).apply { gap = 20f; margin = 0f; setViewport(1080f, 2000f) }
        v.chooseFitEach(true)
        v.zoomTo(2f, Offset(0f, 0f))
        for (i in mixed.indices) assertEquals(2160f, v.pageRectOnScreen(i).width, 0.01f)
    }

    @Test
    fun aBookWithEqualPagesLooksExactlyAsItDoesNow() {
        val book = List(40) { letter }
        assertEquals(1f, widthSpread(book), 0f)
        assertFalse(fitEachShown(null, madeScan = false, spread = widthSpread(book)))
        for (spread in Spread.entries) {
            val before = layout(book, fitEach = false, spread = spread, w = 2000f)
            val after = layout(book, fitEach = true, spread = spread, w = 2000f)
            assertEquals(before.rects, after.rects)
            assertEquals(before.height, after.height, 0f)
        }
        // and the viewer, left to decide, keeps one scale
        val v = ViewerState(book).apply { gap = 20f; setViewport(1080f, 2000f) }
        assertFalse(v.fitEach)
        assertFalse(v.layout.fitEach)
    }

    @Test
    fun turnsOnByItselfForMixedWidthsAndScans() {
        assertEquals(0.5f, widthSpread(mixed), 0.0001f)
        assertEquals(1f, widthSpread(emptyList()), 0f)
        assertTrue(fitEachShown(null, madeScan = false, spread = 0.5f))
        assertFalse(fitEachShown(null, madeScan = false, spread = FIT_EACH_AUTO_SPREAD))
        assertTrue(fitEachShown(null, madeScan = true, spread = 1f))
        // the reader's choice wins either way
        assertFalse(fitEachShown(false, madeScan = true, spread = 0.5f))
        assertTrue(fitEachShown(true, madeScan = false, spread = 1f))

        val v = ViewerState(mixed).apply { gap = 20f; setViewport(1080f, 2000f) }
        assertTrue(v.fitEach)
        v.chooseFitEach(false)
        assertFalse(v.layout.fitEach)
        v.chooseFitEach(null)
        assertTrue(v.layout.fitEach)
        // other page modes are unchanged: single page already fits each page
        v.switchMode(PageMode.SinglePage)
        assertFalse(v.layout.fitEach)
    }

    @Test
    fun aScanTheAppMadeFitsEachPage() {
        val nearlyEqual = listOf(PageSize(600f, 800f), PageSize(560f, 800f))
        val v = ViewerState(nearlyEqual).apply { gap = 20f; margin = 0f; setViewport(1080f, 2000f) }
        assertFalse(v.fitEach)
        v.markMadeScan(true)
        assertTrue(v.fitEach)
        assertEquals(1080f, v.layout.rects[1].width, 0.01f)
    }

    @Test
    fun switchingKeepsTheReaderOnTheSameSpot() {
        val pages = List(10) { if (it % 2 == 0) PageSize(300f, 500f) else PageSize(600f, 800f) }
        val v = ViewerState(pages).apply { gap = 20f; margin = 0f; setViewport(1080f, 2000f); setPadding(120f, 0f) }
        v.chooseFitEach(false)
        v.goToPage(6, yPoints = 200f)
        val top = Offset(540f, 120f)
        val before = v.screenToPage(top)!!
        assertEquals(6, before.page)
        assertEquals(200f, before.y, 0.05f)
        v.chooseFitEach(true)
        val after = v.screenToPage(top)!!
        assertEquals(6, after.page)
        assertEquals(before.y, after.y, 0.05f)
        assertEquals(before.x, after.x, 0.05f)
        v.chooseFitEach(false)
        val again = v.screenToPage(top)!!
        assertEquals(6, again.page)
        assertEquals(before.y, again.y, 0.05f)
    }

    @Test
    fun twoPageSpreadsFitTheWidthAsOneRow() {
        val pages = listOf(PageSize(300f, 400f), PageSize(600f, 400f), PageSize(400f, 600f), PageSize(400f, 600f), PageSize(500f, 700f))
        val l = layout(pages, fitEach = true, spread = Spread.TwoUp, w = 2000f, margin = 0f)
        // each pair spans the width, one scale within the row
        for (row in listOf(0 to 1, 2 to 3)) {
            val (a, b) = l.rects[row.first] to l.rects[row.second]
            assertEquals(0f, a.left, 0.01f)
            assertEquals(2000f, b.right, 0.01f)
            assertEquals(a.right + 20f, b.left, 0.01f)
            assertEquals(l.scaleOf(row.first), l.scaleOf(row.second), 0.0001f)
        }
        // the odd last page takes one page's slot, centred, not the whole width
        val last = l.rects[4]
        assertEquals((2000f - 20f) / 2f, last.width, 0.01f)
        assertEquals(1000f, (last.left + last.right) / 2f, 0.01f)
        // the cover alone likewise
        val cover = layout(pages, fitEach = true, spread = Spread.TwoUpCover, w = 2000f, margin = 0f)
        assertEquals((2000f - 20f) / 2f, cover.rects[0].width, 0.01f)
        assertEquals(2000f, cover.rects[2].right - cover.rects[1].left, 0.01f)
    }

    @Test
    fun theChoiceIsRememberedWithTheLayout() {
        for (l in listOf(
            ViewLayout(PageMode.Continuous, Spread.Single, fitEach = true),
            ViewLayout(PageMode.Continuous, Spread.TwoUp, fitEach = false),
            ViewLayout(PageMode.SinglePage, Spread.Single, autoSpread = true, fitEach = true),
            ViewLayout(PageMode.Continuous, Spread.Single),
        )) assertEquals(l, ViewLayout.decode(l.encode()))
        // what was remembered before #524 still reads, left to decide from the pages
        assertEquals(ViewLayout(PageMode.Continuous, Spread.TwoUp), ViewLayout.decode("Continuous:TwoUp"))
        assertEquals("Continuous:TwoUp", ViewLayout(PageMode.Continuous, Spread.TwoUp).encode())
        assertNull(ViewLayout.decode("Continuous:Single:Sideways"))
        assertNull(ViewLayout.decode("Continuous:Single:FitEach:x"))
        // the file's initial view is the same with or without it
        assertEquals(
            ViewLayout(PageMode.Continuous, Spread.TwoUp).openLayout(),
            ViewLayout(PageMode.Continuous, Spread.TwoUp, fitEach = true).openLayout(),
        )
    }

    @Test
    fun aCachedImageAtAnotherScaleIsRenderedAgain() {
        assertTrue(sameScale(2f, 2f))
        assertTrue(sameScale(2f, 2.009f))
        assertFalse(sameScale(2f, 3f))
        assertFalse(sameScale(1.5f, 1.4f))
    }
}
