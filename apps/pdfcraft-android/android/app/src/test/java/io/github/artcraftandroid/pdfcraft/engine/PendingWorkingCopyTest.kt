package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
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

    @Test fun markingAndClearingAreIdempotent() = temp { dir ->
        PendingWorkingCopy.mark(dir)
        PendingWorkingCopy.mark(dir)
        assertTrue(PendingWorkingCopy.isPending(dir))
        PendingWorkingCopy.clear(dir)
        PendingWorkingCopy.clear(dir)
        assertFalse(PendingWorkingCopy.isPending(dir))
    }
}
