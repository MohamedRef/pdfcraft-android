package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reader's bookmarks survive a restart, and one bad entry doesn't lose the rest (#125). */
class UserBookmarksTest {
    private fun file(json: String? = null): File =
        File(Files.createTempDirectory("bookmarks").toFile(), "bookmarks.json").also { f -> json?.let { f.writeText(it) } }

    @Test fun bookmarksAreReadSortedByPage() {
        val f = file("""{"a":[{"name":"Late","page":9},{"name":"Early","page":2}]}""")
        assertEquals(listOf(UserBookmark("Early", 2), UserBookmark("Late", 9)), UserBookmarks(f).list("a"))
    }

    @Test fun aMalformedDocumentEntryDoesNotHideTheOthers() {
        val f = file("""{"broken":"not a list","good":[{"name":"Intro","page":0}],"also":[{"name":"End","page":4},"junk"]}""")
        val b = UserBookmarks(f)
        assertEquals(listOf(UserBookmark("Intro", 0)), b.list("good"))
        assertEquals(listOf(UserBookmark("End", 4)), b.list("also"))
        assertTrue(b.list("broken").isEmpty())
    }

    @Test fun anUnreadableFileStartsEmpty() {
        assertTrue(UserBookmarks(file("{not json")).list("a").isEmpty())
        assertTrue(UserBookmarks(file()).list("a").isEmpty())
    }

    @Test fun renameMoveAndRemoveAreSavedForTheNextStart() {
        val f = file("""{"a":[{"name":"One","page":1},{"name":"Two","page":2}]}""")
        val b = UserBookmarks(f)
        b.rename("a", UserBookmark("One", 1), "First")
        b.remove("a", UserBookmark("Two", 2))
        b.move("a", "renamed")
        val again = UserBookmarks(f)
        assertTrue(again.list("a").isEmpty())
        assertEquals(listOf(UserBookmark("First", 1)), again.list("renamed"))
        assertTrue(again.has("renamed", 1))
        again.clear("renamed")
        assertTrue(UserBookmarks(f).list("renamed").isEmpty())
    }
}
