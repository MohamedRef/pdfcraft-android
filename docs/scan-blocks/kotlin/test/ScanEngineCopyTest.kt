package io.github.artcraftandroid.pdfcraft.ui.scan

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Copying a page from Google's scanner into the app (#307, spec S21 section 3). */
class ScanEngineCopyTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** A stream that gives [good] bytes and then fails, as a dropped content provider would. */
    private class FailingStream(private val good: Int) : InputStream() {
        private var given = 0

        override fun read(): Int {
            if (given >= good) throw IOException("the provider went away")
            given += 1
            return 7
        }
    }

    @Test
    fun aCompleteStreamIsCopiedWholeAndNoPartFileRemains() {
        val target = File(folder.root, "scan-1.jpg")
        val bytes = ByteArray(10_000) { (it % 251).toByte() }
        assertTrue(copyPageStream(ByteArrayInputStream(bytes), target))
        assertArrayEquals(bytes, target.readBytes())
        assertFalse(File(folder.root, "scan-1.jpg" + PAGE_PART_SUFFIX).exists())
        println("copied ${target.length()} bytes")
    }

    @Test
    fun aStreamThatFailsPartWayLeavesNoFileAtAll() {
        val target = File(folder.root, "scan-2.jpg")
        assertFalse(copyPageStream(FailingStream(good = 1_000), target))
        assertFalse(target.exists())
        assertEquals(0, folder.root.listFiles()?.size ?: -1)
    }

    @Test
    fun aMissingStreamLeavesNoFileAtAll() {
        val target = File(folder.root, "scan-3.jpg")
        assertFalse(copyPageStream(null, target))
        assertEquals(0, folder.root.listFiles()?.size ?: -1)
    }

    @Test
    fun aPageNameComesFromItsNanoTime() {
        assertEquals("scan-123456789.jpg", googlePageFileName(123_456_789L))
        assertEquals(".part", PAGE_PART_SUFFIX)
    }
}
