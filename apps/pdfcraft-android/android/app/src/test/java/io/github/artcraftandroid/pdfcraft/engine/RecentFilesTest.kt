package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's Recent list: newest first, what the reader set is kept, at most [RecentFiles.MAX] (#125). */
class RecentFilesTest {
    private fun recents() = RecentFiles(File(Files.createTempDirectory("recents").toFile(), "recent.json"))
    private fun entry(uri: String, opened: Long = 0) = RecentFile(uri, "$uri.pdf", opened, pages = 3, size = 100)

    @Test fun theLastOpenedComesFirstOnce() {
        val r = recents()
        r.touch(entry("a")); r.touch(entry("b")); r.touch(entry("a", opened = 5))
        assertEquals(listOf("a", "b"), r.list().map { it.uri })
        assertEquals(5L, r.list().first().opened)
    }

    @Test fun openingAgainKeepsTheStarReadingModeAndPlace() {
        val r = recents()
        r.touch(entry("a"))
        r.update("a") { it.copy(starred = true, reading = true, lastX = 10f, lastY = 20f, lastZoom = 2f, layout = "two") }
        r.touch(entry("a", opened = 9))
        val a = r.list().single()
        assertTrue(a.starred && a.reading)
        assertEquals(listOf(10f, 20f, 2f), listOf(a.lastX, a.lastY, a.lastZoom))
        assertEquals("two", a.layout)
    }

    @Test fun theListIsTrimmedToMax() {
        val r = recents()
        repeat(RecentFiles.MAX + 5) { r.touch(entry("u$it", opened = it.toLong())) }
        val list = r.list()
        assertEquals(RecentFiles.MAX, list.size)
        assertEquals("u${RecentFiles.MAX + 4}", list.first().uri)
    }

    @Test fun removeAndClear() {
        val r = recents()
        r.touch(entry("a")); r.touch(entry("b")); r.touch(entry("c"))
        r.remove(setOf("b"))
        assertEquals(listOf("c", "a"), r.list().map { it.uri })
        r.clear()
        assertTrue(r.list().isEmpty())
    }

    @Test fun aBrokenFileReadsAsEmptyAndEntriesWithoutUriAreSkipped() {
        val dir = Files.createTempDirectory("recents").toFile()
        File(dir, "bad.json").writeText("{oops")
        assertTrue(RecentFiles(File(dir, "bad.json")).list().isEmpty())
        File(dir, "mixed.json").writeText("""[{"name":"x"},{"uri":"content://ok","name":"ok.pdf"},7]""")
        assertEquals(listOf("content://ok"), RecentFiles(File(dir, "mixed.json")).list().map { it.uri })
    }
}
