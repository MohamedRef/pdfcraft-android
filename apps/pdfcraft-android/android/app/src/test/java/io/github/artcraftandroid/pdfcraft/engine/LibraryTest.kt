package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    private fun <T> withDir(f: (File) -> T): T {
        val dir = Files.createTempDirectory("lib").toFile()
        try { return f(dir) } finally { dir.deleteRecursively() }
    }

    @Test
    fun nestedCollectionsHoldFilesWithoutMovingThem() = withDir { dir ->
        val lib = Library(File(dir, "library.json"))
        lib.addCollection("Semester 1", null, id = "s1")
        lib.addCollection("Biology", "s1", id = "bio")
        lib.addCollection("Exam", null, id = "exam")
        lib.addItem("content://a", "Krebs.pdf", 1, collection = "bio")
        lib.addItem("content://a", "Krebs.pdf", 2, collection = "exam")
        lib.addItem("content://b", "Loose.pdf", 3)
        val d = lib.load()
        assertEquals(setOf("bio", "exam"), d.item("content://a")!!.collections)
        // a file in a sub-collection is in its parent too
        assertEquals(listOf("content://a"), d.itemsIn("s1").map { it.uri })
        assertEquals(listOf("Semester 1", "Biology"), d.path("bio").map { it.name })
        assertEquals(2, d.items.size)
        // deleting a collection deletes those under it, never the files
        val after = lib.deleteCollection("s1")
        assertEquals(listOf("exam"), after.collections.map { it.id })
        assertEquals(setOf("exam"), after.item("content://a")!!.collections)
        assertEquals(2, after.items.size)
    }

    @Test
    fun tagsColoursAndDanglingParentsSurviveTheFile() = withDir { dir ->
        val lib = Library(File(dir, "library.json"))
        lib.addItem("content://a", "A.pdf", 1)
        lib.setItem("content://a") { it.copy(tags = setOf(Library.cleanTag("#exam"), Library.cleanTag("  week   3 ")), color = 0xE53935) }
        val d = Library(File(dir, "library.json")).load()
        assertEquals(setOf("exam", "week 3"), d.item("content://a")!!.tags)
        assertEquals(0xE53935, d.item("content://a")!!.color)
        assertEquals(listOf("exam", "week 3"), d.allTags())
        val broken = LibraryData.fromJson(LibraryData(listOf(LibraryCollection("x", "X", parent = "gone"))).toJson())
        assertNull(broken.collection("x")!!.parent)
    }

    @Test
    fun findsAWordOnPage37Of50DocumentsQuickly() = withDir { dir ->
        val index = LibraryIndex(File(dir, "index"))
        val filler = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(40)
        for (d in 0 until 50) {
            val pages = (0 until 60).map { p -> if (d == 23 && p == 36) "$filler The Krebs cycle makes ATP. $filler" else filler }
            index.put(IndexedDoc("content://doc$d", "Doc $d.pdf", "1:1", pages, comments = listOf(Triple(2, "check the Krebs table", d == 7))))
        }
        val t0 = System.nanoTime()
        val hits = index.search("krebs CYCLE", null)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("search took $ms ms", ms < 1000)
        val text = hits.single { it.kind == HitKind.Text }
        assertEquals("content://doc23", text.uri)
        assertEquals(36, text.page)
        assertEquals("krebs cycle", LibraryIndex.fold(text.snippet.substring(text.start, text.start + text.length)))
        // comments are found and named as comments
        val comments = index.search("krebs table", null)
        assertEquals(50, comments.size)
        assertTrue(comments.all { it.kind == HitKind.Comment && it.page == 2 })
        assertEquals(1, index.get("content://doc7")!!.unresolvedComments)
    }

    @Test
    fun foldingIgnoresCaseAndAccentsAndKeepsPositions() {
        assertEquals("cafe creme", LibraryIndex.fold("Café Crème"))
        assertEquals("Café Crème".length, LibraryIndex.fold("Café Crème").length)
    }

    @Test
    fun indexIsBoundedAndCanBeWiped() = withDir { dir ->
        val index = LibraryIndex(File(dir, "index"), maxChars = 100)
        index.put(IndexedDoc("content://a", "A", "s", listOf("x".repeat(80), "y".repeat(80))))
        assertEquals(100, index.get("content://a")!!.pages.sumOf { it.length })
        assertTrue(index.has("content://a", "s"))
        index.retain(emptySet())
        assertNull(index.get("content://a"))
        index.put(IndexedDoc("content://b", "B", "s", listOf("z")))
        index.wipe()
        assertEquals(0, index.sizeBytes())
    }
}
