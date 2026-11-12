package io.github.artcraftandroid.pdfcraft.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabPolicyTest {
    @Test
    fun closingATabShowsTheNextOneElseThePrevious() {
        assertNull(TabPolicy.nextAfterClose(1, 0))
        assertEquals(1, TabPolicy.nextAfterClose(3, 1))
        assertEquals(0, TabPolicy.nextAfterClose(3, 0))
        assertEquals(1, TabPolicy.nextAfterClose(3, 2))
        assertNull(TabPolicy.nextAfterClose(3, 5))
    }

    @Test
    fun ctrlTabWrapsBothWays() {
        assertNull(TabPolicy.cycle(1, 0, 1))
        assertEquals(1, TabPolicy.cycle(3, 0, 1))
        assertEquals(0, TabPolicy.cycle(3, 2, 1))
        assertEquals(2, TabPolicy.cycle(3, 0, -1))
        // on Home (nothing in front) Ctrl+Tab starts from the first tab
        assertEquals(1, TabPolicy.cycle(3, -1, 1))
    }

    @Test
    fun theLeastRecentlyUsedAreParkedFirstAndKeptOnesNever() {
        data class T(val name: String, val used: Long, val keep: Boolean = false)
        val hot = listOf(T("a", 10), T("b", 5, keep = true), T("c", 1), T("d", 20), T("e", 30, keep = true))
        assertEquals(listOf("c"), TabPolicy.toPark(hot, { it.used }, { it.keep }, maxHot = 4).map { it.name })
        assertEquals(listOf("c", "a"), TabPolicy.toPark(hot, { it.used }, { it.keep }, maxHot = 3).map { it.name })
        assertEquals(emptyList<T>(), TabPolicy.toPark(hot, { it.used }, { it.keep }, maxHot = 5))
        // never more than can be parked, even when the kept ones alone are over the limit
        assertEquals(listOf("c", "a", "d"), TabPolicy.toPark(hot, { it.used }, { it.keep }, maxHot = 1).map { it.name })
    }

    @Test
    fun aNewTabGoesRightAfterTheOneInFront() {
        assertEquals(0, TabPolicy.insertAt(0, null))
        assertEquals(2, TabPolicy.insertAt(3, 1))
        assertEquals(3, TabPolicy.insertAt(3, null))
        assertEquals(3, TabPolicy.insertAt(3, 7))
    }

    @Test
    fun splitStaysUsableAndFollowsTheWindowShape() {
        assertEquals(0.25f, TabPolicy.clampSplit(0f))
        assertEquals(0.75f, TabPolicy.clampSplit(2f))
        assertEquals(0.5f, TabPolicy.clampSplit(0.5f))
        assertTrue(TabPolicy.sideBySide(1280, 800))
        assertFalse(TabPolicy.sideBySide(412, 915))
    }

    @Test
    fun savedTabsRoundTripAndDropWhatCannotReopen() {
        val tabs = listOf("content://a/1" to "Lecture 1", "file:///sdcard/b.pdf" to "Book\twith tab")
        val decoded = TabPolicy.decode(TabPolicy.encode(tabs))
        assertEquals(listOf("content://a/1" to "Lecture 1", "file:///sdcard/b.pdf" to "Book with tab"), decoded)
        assertEquals(emptyList<Pair<String, String>>(), TabPolicy.decode(null))
        // a tab without a source the app can open again, a duplicate, and junk are dropped
        assertEquals(
            listOf("content://a/1" to "x"),
            TabPolicy.decode("doc:5\tNo source\ncontent://a/1\tx\ncontent://a/1\ty\nnot a line"),
        )
    }
}
