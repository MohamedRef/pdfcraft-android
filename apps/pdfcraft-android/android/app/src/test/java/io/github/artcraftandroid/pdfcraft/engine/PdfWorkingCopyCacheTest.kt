package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfWorkingCopyCacheTest {
    private fun temp(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("pdfcraft-cache-").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun usesOnlyMatchingSourceSizeAndModificationTime() = temp { dir ->
        val copy = File(dir, "large.pdf").apply { writeText("hello world") }
        val current = PdfSourceFingerprint(copy.length(), 10000L)
        assertFalse(PdfWorkingCopyCache.canReuse(dir, copy, current))
        PdfWorkingCopyCache.record(dir, current)
        assertTrue(PdfWorkingCopyCache.canReuse(dir, copy, current))
        assertFalse(PdfWorkingCopyCache.canReuse(dir, copy, current.copy(modified = 20000L)))
        assertFalse(PdfWorkingCopyCache.canReuse(dir, copy, current.copy(size = copy.length() + 1)))
        copy.writeText("truncated")
        assertFalse(PdfWorkingCopyCache.canReuse(dir, copy, current))
    }

    @Test fun unknownProviderMetadataNeverReusesAnOldCopy() = temp { dir ->
        val copy = File(dir, "large.pdf").apply { writeText("content") }
        PdfWorkingCopyCache.record(dir, PdfSourceFingerprint(copy.length(), 100L))
        assertFalse(PdfWorkingCopyCache.canReuse(dir, copy, PdfSourceFingerprint(copy.length(), null)))
        assertFalse(PdfWorkingCopyCache.canReuse(dir, copy, PdfSourceFingerprint(null, 100L)))
        assertFalse(PdfWorkingCopyCache.canReuse(dir, copy, PdfSourceFingerprint(copy.length(), 0L)))
    }

    @Test fun missingOrCorruptStampNeverValidatesTheSource() = temp { dir ->
        val copy = File(dir, "large.pdf").apply { writeText("content") }
        val stamp = PdfSourceFingerprint(copy.length(), 100L)
        PdfWorkingCopyCache.record(dir, stamp)
        assertTrue(PdfWorkingCopyCache.hasStamp(dir))
        File(dir, ".source-fingerprint").writeText("corrupt")
        assertFalse(PdfWorkingCopyCache.canReuse(dir, copy, stamp))
        PdfWorkingCopyCache.clear(dir)
        assertFalse(PdfWorkingCopyCache.hasStamp(dir))
    }
}
