package io.github.artcraftandroid.pdfcraft.engine

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

/** A file saved to Downloads is there, or the caller is told it is not (#787). */
class DownloadPublishTest {
    private val events = mutableListOf<String>()

    private fun publish(
        reserve: () -> String = { events += "reserve"; "row" },
        write: (String) -> Unit = { events += "write:$it" },
        publish: (String) -> Int = { events += "publish:$it"; 1 },
        remove: (String) -> Unit = { events += "remove:$it" },
    ): String = publishDownload(reserve, write, publish, remove) { IOException("not published") }

    @Test
    fun aPublishedFileIsReturnedAndNeverRemoved() {
        assertEquals("row", publish(remove = { fail("a published file must stay") }))
        assertEquals(listOf("reserve", "write:row", "publish:row"), events)
    }

    @Test
    fun anEntryThatCannotBeReservedLeavesNothingToRemove() {
        val refused = IOException("no entry")
        assertSame(refused, assertThrows(IOException::class.java) { publish(reserve = { throw refused }) })
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun aFailedWriteRemovesThePendingEntryAndIsThrown() {
        val full = IOException("no space")
        assertSame(full, assertThrows(IOException::class.java) { publish(write = { throw full }) })
        // never published, and the half-written entry is gone
        assertEquals(listOf("reserve", "remove:row"), events)
    }

    @Test
    fun anUpdateThatChangesNoRowIsAFailure() {
        // on main before #787 this returned the address as if the file were saved
        val e = assertThrows(IOException::class.java) { publish(publish = { events += "publish:$it"; 0 }) }
        assertEquals("not published", e.message)
        assertEquals(listOf("reserve", "write:row", "publish:row", "remove:row"), events)
    }

    @Test
    fun aFailedPublishRemovesThePendingEntryAndIsThrown() {
        val gone = IllegalStateException("provider gone")
        assertSame(gone, assertThrows(IllegalStateException::class.java) { publish(publish = { throw gone }) })
        assertEquals(listOf("reserve", "write:row", "remove:row"), events)
    }

    @Test
    fun aRemoveThatFailsDoesNotHideTheFailure() {
        val full = IOException("no space")
        val thrown = assertThrows(IOException::class.java) { publish(write = { throw full }, remove = { throw IllegalArgumentException("can't remove") }) }
        assertSame(full, thrown)
        assertEquals("can't remove", thrown.suppressed.single().message)
    }
}
