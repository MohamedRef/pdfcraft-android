package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingWorkingCopyTest {
    private fun temp(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("pdfcraft-save-recovery-").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun markerPersistsUntilProviderWriteSucceeds() = temp { dir ->
        assertFalse(PendingWorkingCopy.isPending(dir))
        File(dir, "working.pdf").writeText("latest changes")
        PendingWorkingCopy.mark(dir)
        assertTrue(PendingWorkingCopy.isPending(dir))
        assertTrue(File(dir, "working.pdf").readText() == "latest changes")
        PendingWorkingCopy.clear(dir)
        assertFalse(PendingWorkingCopy.isPending(dir))
    }

    @Test fun explicitDiscardDeletesUnsyncedRecoveryAndMarker() = temp { dir ->
        val working = File(dir, "working.pdf")
        working.writeText("unsynced PDF edits")
        PendingWorkingCopy.mark(dir)
        PendingWorkingCopy.discard(dir)
        assertFalse(dir.exists())
        assertFalse(working.exists())
        assertFalse(PendingWorkingCopy.isPending(dir))
    }

    @Test fun invalidMarkerDirectoryCannotPretendWriteBackIsSafe() = temp { dir ->
        val notDirectory = File(dir, "blocked")
        notDirectory.writeText("not a folder")
        try {
            PendingWorkingCopy.mark(notDirectory)
            org.junit.Assert.fail("Marking a non-directory unexpectedly succeeded")
        } catch (expected: java.io.IOException) {
            assertFalse(PendingWorkingCopy.isPending(notDirectory))
        }
    }

    @Test fun markingAndClearingAreIdempotent() = temp { dir ->
        PendingWorkingCopy.mark(dir)
        PendingWorkingCopy.mark(dir)
        assertTrue(PendingWorkingCopy.isPending(dir))
        PendingWorkingCopy.clear(dir)
        PendingWorkingCopy.clear(dir)
        assertFalse(PendingWorkingCopy.isPending(dir))
    }

    @Test fun recoveryOpensTheCopyTheMarkerNamesNotAnArbitrarySibling() = temp { dir ->
        // #101: an older copy under the source's previous name is still in the folder
        val old = File(dir, "a.pdf").apply { writeText("old") }
        val edited = File(dir, "b.pdf").apply { writeText("unsaved edits") }
        old.setLastModified(System.currentTimeMillis())
        edited.setLastModified(1_000_000L) // the copy keeps its source's (older) date
        PendingWorkingCopy.mark(dir, "b.pdf")
        assertEquals(edited, PendingWorkingCopy.recoveryCopy(dir))
        // a later mark that doesn't know the name keeps the recorded one
        PendingWorkingCopy.mark(dir)
        assertEquals("b.pdf", PendingWorkingCopy.recordedCopy(dir))
        assertEquals(edited, PendingWorkingCopy.recoveryCopy(dir))
    }

    @Test fun recoveryWithoutARecordedNameIsDeterministic() = temp { dir ->
        val older = File(dir, "z.pdf").apply { writeText("older"); setLastModified(1_000_000L) }
        val newer = File(dir, "a.pdf").apply { writeText("newer"); setLastModified(2_000_000L) }
        File(dir, ".a.pdf.part").writeText("staging")
        PendingWorkingCopy.mark(dir) // a marker written before names were recorded
        assertNull(PendingWorkingCopy.recordedCopy(dir))
        assertEquals(newer, PendingWorkingCopy.recoveryCopy(dir))
        newer.setLastModified(older.lastModified())
        assertEquals(newer, PendingWorkingCopy.recoveryCopy(dir)) // ties: by name
    }

    @Test fun aRecordedNameThatIsGoneFallsBackAndUnsafeNamesAreIgnored() = temp { dir ->
        val only = File(dir, "only.pdf").apply { writeText("x") }
        PendingWorkingCopy.mark(dir, "missing.pdf")
        assertEquals(only, PendingWorkingCopy.recoveryCopy(dir))
        PendingWorkingCopy.clear(dir)
        PendingWorkingCopy.mark(dir, "../escape.pdf")
        assertNull(PendingWorkingCopy.recordedCopy(dir))
        PendingWorkingCopy.mark(dir, ".writeback-pending")
        assertNull(PendingWorkingCopy.recordedCopy(dir))
    }

    @Test fun emptyFolderHasNoRecoveryCopy() = temp { dir ->
        PendingWorkingCopy.mark(dir, "gone.pdf")
        assertNull(PendingWorkingCopy.recoveryCopy(dir))
        assertNull(PendingWorkingCopy.recoveryCopy(File(dir, "no-such-folder")))
    }
}
