package io.github.artcraftandroid.pdfcraft.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SafBoundedCopyTest {
    @Test fun copiesReadOnlySourceWithinLimit() {
        val input = ByteArray(250_001) { (it % 253).toByte() }
        val output = ByteArrayOutputStream()
        val count = SafBoundedCopy.copy(ByteArrayInputStream(input), output, 1_000_000L, "PDF")
        assertEquals(input.size.toLong(), count)
        assertArrayEquals(input, output.toByteArray())
    }

    @Test fun rejectsExcessWithoutWritingPastLimit() {
        val output = ByteArrayOutputStream()
        val exception = assertThrows(IOException::class.java) {
            SafBoundedCopy.copy(ByteArrayInputStream(ByteArray(65_537) { 7 }), output, 65_536L, "PDF")
        }
        assertEquals(true, exception.message?.contains("size limit"))
        assertEquals(65_536L, output.size().toLong())
    }

    @Test fun rejectsEmptyAndStalledProviders() {
        assertThrows(IOException::class.java) {
            SafBoundedCopy.copy(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), 8L, "PDF")
        }
        val stalled = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        assertThrows(IOException::class.java) {
            SafBoundedCopy.copy(stalled, ByteArrayOutputStream(), 8L, "PDF")
        }
    }

    @Test fun rejectsInvalidLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            SafBoundedCopy.copy(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), 0L, "PDF")
        }
    }
}
