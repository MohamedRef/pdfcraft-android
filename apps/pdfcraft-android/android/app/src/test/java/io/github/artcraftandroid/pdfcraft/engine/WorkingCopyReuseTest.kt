package io.github.artcraftandroid.pdfcraft.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkingCopyReuseTest {
    private fun pdf(dir: File, body: Int = 4000): File {
        val f = File(dir, "Doc.pdf")
        f.writeBytes("%PDF-1.7\n".toByteArray() + ByteArray(body) { 'x'.code.toByte() } + "\n%%EOF\n".toByteArray())
        f.setLastModified(1_700_000_000_000L)
        return f
    }

    private fun stampFor(f: File, source: SourceStamp = SourceStamp(f.length(), 1_699_000_000_000L)) =
        CopyStamp(source, f.length(), f.lastModified())

    @Test
    fun stampRoundTripsAndRejectsJunk() {
        val s = CopyStamp(SourceStamp(314_572_800, 1_699_000_000_123), 314_572_800, 1_700_000_000_000)
        assertEquals(s, CopyStamp.decode(s.encode()))
        assertNull(CopyStamp.decode("v2 1 2 3 4"))
        assertNull(CopyStamp.decode("v1 1 2 x 4"))
        assertNull(CopyStamp.decode(""))
    }

    @Test
    fun reusesOnlyAnUnchangedIntactCopyOfAnUnchangedSource() {
        val dir = Files.createTempDirectory("wc").toFile()
        try {
            val f = pdf(dir)
            val stamp = stampFor(f)
            assertTrue(canReuseCopy(stamp, stamp.source, f, pending = false))
            // the source changed: size or date
            assertFalse(canReuseCopy(stamp, stamp.source.copy(size = stamp.source.size + 1), f, false))
            assertFalse(canReuseCopy(stamp, stamp.source.copy(modified = stamp.source.modified + 1000), f, false))
            // unknown source values never reuse
            assertFalse(canReuseCopy(stamp, null, f, false))
            assertFalse(canReuseCopy(stamp, SourceStamp(f.length(), 0), f, false))
            // a pending write-back, or no stamp, never reuses
            assertFalse(canReuseCopy(stamp, stamp.source, f, pending = true))
            assertFalse(canReuseCopy(null, stamp.source, f, false))
            // the copy was edited locally (date changed)
            f.setLastModified(1_700_000_100_000L)
            assertFalse(canReuseCopy(stamp, stamp.source, f, false))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aTruncatedCopyIsNotReused() {
        val dir = Files.createTempDirectory("wc").toFile()
        try {
            val f = pdf(dir)
            val whole = f.readBytes()
            f.writeBytes(whole.copyOf(whole.size - 7) + ByteArray(7))
            f.setLastModified(1_700_000_000_000L)
            val stamp = stampFor(f)
            assertFalse(canReuseCopy(stamp, stamp.source, f, false))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun keepsTheMostRecentCopiesWithinTheBudgetAndAlwaysTheOpenOne() {
        val copies = listOf(
            KeptCopy("old", 300, lastUsed = 1),
            KeptCopy("new", 300, lastUsed = 3),
            KeptCopy("mid", 300, lastUsed = 2),
            KeptCopy("open", 900, lastUsed = 0),
        )
        assertEquals(setOf("new", "mid"), copiesToKeep(copies, budgetBytes = 650))
        assertEquals(setOf("open"), copiesToKeep(copies, budgetBytes = 1000, always = "open"))
        assertEquals(emptySet<String>(), copiesToKeep(copies, budgetBytes = 100))
    }

    @Test
    fun budgetIsAQuarterOfFreeSpaceUpToTheCap() {
        assertEquals(250L, reuseBudget(1000, capBytes = 2000))
        assertEquals(2000L, reuseBudget(100_000, capBytes = 2000))
        assertEquals(0L, reuseBudget(-5))
    }
}
