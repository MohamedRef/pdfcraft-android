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
}
