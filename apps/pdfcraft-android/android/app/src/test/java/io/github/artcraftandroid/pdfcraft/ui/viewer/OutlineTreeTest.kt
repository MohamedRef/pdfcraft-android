package io.github.artcraftandroid.pdfcraft.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #647: the contents as a tree that opens in place. */
class OutlineTreeTest {
    // 0 Cover · 1 Part I › 2 Chapter 1 › 3 Section 1.1, 4 Section 1.2 · 5 Chapter 2 · 6 Part II › 7 Chapter 3 · 8 Index
    private val levels = listOf(0, 0, 1, 2, 2, 1, 0, 1, 0)
    private val pages = listOf<Int?>(0, 4, 4, 5, 9, 20, 40, 40, 90)

    @Test
    fun foldedShowsTheTopLevelOnly() {
        assertEquals(listOf(0, 1, 6, 8), outlineVisible(levels, emptySet()))
    }

    @Test
    fun anOpenEntryShowsItsChildrenAndNotTheirs() {
        assertEquals(listOf(0, 1, 2, 5, 6, 8), outlineVisible(levels, setOf(1)))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 8), outlineVisible(levels, setOf(1, 2)))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8), outlineVisible(levels, setOf(1, 2, 6)))
    }

    @Test
    fun anOpenEntryUnderAFoldedOneStaysHidden() {
        // Chapter 1 is open but Part I is not: nothing under Part I shows
        assertEquals(listOf(0, 1, 6, 8), outlineVisible(levels, setOf(2)))
    }

    @Test
    fun aLevelThatSkipsIsShownUnderTheEntryBeforeIt() {
        // a damaged outline: 0, then straight to level 3, then back to 1
        val odd = listOf(0, 3, 1, 0)
        assertEquals(listOf(0, 3), outlineVisible(odd, emptySet()))
        assertEquals(listOf(0, 1, 2, 3), outlineVisible(odd, setOf(0)))
        assertEquals(listOf(0, 1, 2), outlineVisible(listOf(-4, 0, 0), emptySet()))
    }

    @Test
    fun childrenAndAncestors() {
        assertTrue(outlineHasChildren(levels, 1))
        assertTrue(outlineHasChildren(levels, 2))
        assertFalse(outlineHasChildren(levels, 4))
        assertFalse(outlineHasChildren(levels, 8))
        assertFalse(outlineHasChildren(levels, 99))
        assertEquals(listOf(1, 2), outlineAncestors(levels, 4))
        assertEquals(listOf(1), outlineAncestors(levels, 5))
        assertEquals(emptyList<Int>(), outlineAncestors(levels, 6))
        assertEquals(listOf(6), outlineAncestors(levels, 7))
        assertEquals(emptyList<Int>(), outlineAncestors(levels, -1))
        assertEquals(setOf(1, 2, 6), outlineAllParents(levels))
    }

    @Test
    fun theCurrentEntryIsTheLastOneAtOrBeforeThePage() {
        assertEquals(0, outlineCurrent(pages, 0))
        assertEquals(0, outlineCurrent(pages, 3))
        // Part I and Chapter 1 start on the same page: the deeper one, later in reading order
        assertEquals(2, outlineCurrent(pages, 4))
        assertEquals(3, outlineCurrent(pages, 8))
        assertEquals(4, outlineCurrent(pages, 19))
        assertEquals(7, outlineCurrent(pages, 60))
        assertEquals(8, outlineCurrent(pages, 500))
    }

    @Test
    fun noCurrentEntryBeforeTheFirstOrWithoutPages() {
        assertEquals(-1, outlineCurrent(listOf(3, 9), 2))
        assertEquals(-1, outlineCurrent(listOf(null, null), 5))
        assertEquals(-1, outlineCurrent(emptyList(), 5))
        // an entry that goes nowhere is passed over
        assertEquals(2, outlineCurrent(listOf(1, null, 4, 30), 10))
        // pages out of order: the nearest page at or before wins, not the last entry
        assertEquals(1, outlineCurrent(listOf(1, 12, 3, 30), 15))
    }

    @Test
    fun openingTheWayToTheCurrentEntryShowsIt() {
        val current = outlineCurrent(pages, 9)
        assertEquals(4, current)
        val open = outlineAncestors(levels, current).toSet()
        assertTrue(current in outlineVisible(levels, open))
    }

    @Test
    fun foldingAnEntryFoldsWhatIsOpenUnderIt() {
        val open = outlineToggled(levels, emptySet(), 1)
        assertEquals(setOf(1), open)
        val deeper = outlineToggled(levels, open, 2)
        assertEquals(setOf(1, 2), deeper)
        // fold Part I: Chapter 1 goes with it, Part II stays
        assertEquals(setOf(6), outlineToggled(levels, deeper + 6, 1))
        assertEquals(setOf(1, 2), outlineToggled(levels, deeper, 99))
    }

    @Test
    fun deepLevelsKeepTheirWidth() {
        assertEquals(0, outlineIndentDp(0))
        assertEquals(32, outlineIndentDp(2))
        assertEquals(80, outlineIndentDp(5))
        assertEquals(80, outlineIndentDp(12))
        assertEquals(0, outlineIndentDp(-3))
    }

    @Test
    fun shareStaysOnTheBarOnlyWhereThereIsRoom() {
        assertFalse(topBarHoldsShare(320))
        assertFalse(topBarHoldsShare(384))
        assertTrue(topBarHoldsShare(412))
        assertTrue(topBarHoldsShare(800))
    }
}
