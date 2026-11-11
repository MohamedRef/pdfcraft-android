package io.github.artcraftandroid.pdfcraft.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CacheCleanupTest {
    // well after the real clock, so folders the test creates count as old
    private val now = System.currentTimeMillis() + 2 * CacheCleanup.SHARE_KEEP_MS
    private val old = now - CacheCleanup.SHARE_KEEP_MS - 1
    private val recent = now - 60_000L

    private fun file(parent: File, path: String, modified: Long): File =
        File(parent, path).apply { parentFile!!.mkdirs(); writeText("x"); setLastModified(modified) }

    @Test
    fun staleTemporaryFilesGoAndFreshOnesStay() {
        val cache = Files.createTempDirectory("cache").toFile()
        val oldPrint = file(cache, "print-1.pdf", old)
        val oldOut = file(cache, "out-1.pdf", old)
        val freshOut = file(cache, "out-2.pdf", recent)
        val oldVoice = file(cache, "voice-play-1.wav", old)
        val legacyVoice = file(cache, "voice-play.wav", old)
        val recording = file(cache, "voice-9.wav", old)
        val oldAttachment = file(cache, "attachments/1/secret.pdf", old)
        val freshAttachment = file(cache, "attachments/2/new.pdf", recent)
        val oldPicked = file(cache, "picked/1/in.pdf", old)
        val thumb = file(cache, "thumbs/a.png", old)

        CacheCleanup.sweep(cache, now)

        for (gone in listOf(oldPrint, oldOut, oldVoice, legacyVoice, oldAttachment.parentFile!!, oldPicked.parentFile!!)) {
            assertFalse("$gone should be deleted", gone.exists())
        }
        for (kept in listOf(freshOut, freshAttachment, recording, thumb)) {
            assertTrue("$kept should stay", kept.exists())
        }
    }

    @Test
    fun shareCopiesStayForTheKeepTimeAndLooseFilesStay() {
        val cache = Files.createTempDirectory("cache").toFile()
        val oldShare = file(cache, "share/1/doc.pdf", old)
        // older than the settle time but younger than the keep time: a receiver may still read it
        val readingShare = file(cache, "share/2/doc.pdf", now - CacheCleanup.SETTLE_MS - 1)
        val report = file(cache, "share/pdfcraft-open-timings.txt", old)

        CacheCleanup.sweep(cache, now)

        assertFalse(oldShare.parentFile!!.exists())
        assertTrue(readingShare.exists())
        assertTrue(report.exists())
    }

    @Test
    fun aFolderWithAFreshFileInsideIsKept() {
        val cache = Files.createTempDirectory("cache").toFile()
        val dir = File(cache, "attachments/1")
        file(dir, "a.pdf", old)
        file(dir, "b.pdf", recent)
        dir.setLastModified(old)
        assertEquals(0, CacheCleanup.sweep(cache, now))
        assertTrue(dir.exists())
    }
}
