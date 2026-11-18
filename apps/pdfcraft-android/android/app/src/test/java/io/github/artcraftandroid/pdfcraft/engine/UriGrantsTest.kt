package io.github.artcraftandroid.pdfcraft.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class UriGrantsTest {
    private fun doc(n: Int, at: Long = n.toLong()) = UriGrant("content://p/document/$n", tree = false, persistedAt = at)
    private fun tree(n: Int) = UriGrant("content://p/tree/$n", tree = true, persistedAt = 0)

    @Test
    fun documentsThatLeftRecentAreReleasedAndFoldersNever() {
        val grants = listOf(doc(1), doc(2), doc(3), tree(1))
        val release = documentGrantsToRelease(grants, recent = setOf(doc(1).uri), open = doc(3).uri, cap = 96)
        assertEquals(setOf(doc(2).uri), release)
    }

    @Test
    fun theOldestRecentGrantsBeyondTheCapAreReleased() {
        val grants = (1..10).map { doc(it) } + tree(1) + tree(2)
        val recent = grants.filter { !it.tree }.map { it.uri }.toSet()
        // the open document takes one of the 5 places, the 4 newest others keep theirs
        val release = documentGrantsToRelease(grants, recent, open = doc(1).uri, cap = 5)
        assertEquals((2..6).map { doc(it).uri }.toSet(), release)
    }

    @Test
    fun nothingIsReleasedWhenAllDocumentsAreInRecentAndUnderTheCap() {
        val grants = listOf(doc(1), doc(2), tree(1))
        assertEquals(emptySet<String>(), documentGrantsToRelease(grants, setOf(doc(1).uri, doc(2).uri), open = null, cap = 96))
    }

    @Test
    fun aGrantTakenMomentsAgoStaysThoughRecentDoesNotListItYet() {
        // Open file took access a second ago and Home's clean-up ran as the picker closed: the file
        // is still opening, so Recent does not list it yet (#705)
        val opening = doc(2, at = System.currentTimeMillis() - 1_000)
        val release = documentGrantsToRelease(listOf(doc(1), opening, tree(1)), recent = emptySet(), open = null, cap = 96)
        assertEquals(setOf(doc(1).uri), release)
    }

    @Test
    fun aGrantNotInRecentGoesOnceItHasSettled() {
        val now = 1_000_000_000L
        val grants = listOf(doc(1, at = now - GRANT_SETTLING_MS - 1), doc(2, at = now - GRANT_SETTLING_MS + 1_000))
        assertEquals(setOf(doc(1).uri), documentGrantsToRelease(grants, recent = emptySet(), open = null, cap = 96, now = now))
    }

    @Test
    fun grantsTakenMomentsAgoCountTowardTheCap() {
        val now = 1_000_000_000L
        // three settled ones in Recent and two still opening: with room for four, the oldest goes
        val grants = (1..3).map { doc(it) } + (4..5).map { doc(it, at = now - 1_000 + it) }
        val recent = (1..3).map { doc(it).uri }.toSet()
        assertEquals(setOf(doc(1).uri), documentGrantsToRelease(grants, recent, open = null, cap = 4, now = now))
    }
}
