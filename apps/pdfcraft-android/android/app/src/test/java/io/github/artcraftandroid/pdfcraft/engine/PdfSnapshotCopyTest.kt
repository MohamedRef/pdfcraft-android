package io.github.artcraftandroid.pdfcraft.engine

import java.io.EOFException
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PdfSnapshotCopyTest {
    private fun checkRange(fallback: Boolean, short: Boolean = false) {
        val dir = Files.createTempDirectory("pdf-snapshot")
        try {
            val bytes = ByteArray(PdfSnapshotCopy.BUFFER_BYTES * 3 + 39) { (it % 251).toByte() }
            val source = Files.write(dir.resolve("source"), bytes)
            val target = Files.createFile(dir.resolve("target"))
            java.nio.channels.FileChannel.open(source, READ).use { input ->
                java.nio.channels.FileChannel.open(target, WRITE).use { output ->
                    val length = bytes.size - 20L
                    val copied = PdfSnapshotCopy.copyRange(input, output, 7L, length) { pos, count ->
                        if (fallback) 0L else input.transferTo(pos, if (short) minOf(19L, count) else count, output)
                    }
                    assertEquals(length, copied)
                }
            }
            assertArrayEquals(bytes.copyOfRange(7, bytes.size - 13), Files.readAllBytes(target))
            assertArrayEquals(bytes, Files.readAllBytes(source))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun nonSeekableProviderDescriptorRetriesViaStreamingWithoutIgnoringSecurity() {
        org.junit.Assert.assertTrue(PdfSnapshotCopy.retryWithProviderStream(java.io.IOException("Provider stream seek failed")))
        org.junit.Assert.assertTrue(PdfSnapshotCopy.retryWithProviderStream(UnsupportedOperationException("No channel")))
        org.junit.Assert.assertTrue(PdfSnapshotCopy.retryWithProviderStream(IllegalStateException("Non-seekable channel")))
        org.junit.Assert.assertFalse(PdfSnapshotCopy.retryWithProviderStream(SecurityException("Provider grant revoked")))
        org.junit.Assert.assertFalse(PdfSnapshotCopy.retryWithProviderStream(OutOfMemoryError("Low memory")))
        org.junit.Assert.assertFalse(PdfSnapshotCopy.retryWithProviderStream(IllegalArgumentException("Invalid provider request")))
    }

    @Test fun copiesOnlyAssetRange() = checkRange(false)
    @Test fun handlesShortTransfers() = checkRange(false, short = true)
    @Test fun zeroTransferUsesBufferedFallback() = checkRange(true)

    @Test fun truncatedSourceAndOverflowAreErrors() {
        val dir = Files.createTempDirectory("pdf-snapshot")
        try {
            val source = Files.write(dir.resolve("source"), byteArrayOf(1, 2, 3))
            val target = Files.createFile(dir.resolve("target"))
            java.nio.channels.FileChannel.open(source, READ).use { input ->
                java.nio.channels.FileChannel.open(target, WRITE).use { output ->
                    assertThrows(EOFException::class.java) { PdfSnapshotCopy.copyRange(input, output, 0, 10) { _, _ -> 0 } }
                    assertThrows(IllegalArgumentException::class.java) { PdfSnapshotCopy.copyRange(input, output, Long.MAX_VALUE, 2) }
                }
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
