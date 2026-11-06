package io.github.artcraftandroid.pdfcraft.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EmbeddedBookmarkJsonTest {
    @Test fun preservesUtf8AndExactBytes() {
        val source = """{"bookmarks":[{"title":"عنوان 📚","page":1}]}""".toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()
        assertEquals(source.size.toLong(), EmbeddedBookmarkJson.copyBounded(ByteArrayInputStream(source), output))
        assertArrayEquals(source, output.toByteArray())
    }

    @Test fun acceptsInputExactlyAtLimit() {
        val source = ByteArray(EmbeddedBookmarkJson.MAX_BYTES.toInt()) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        assertEquals(EmbeddedBookmarkJson.MAX_BYTES, EmbeddedBookmarkJson.copyBounded(ByteArrayInputStream(source), output))
        assertArrayEquals(source, output.toByteArray())
    }

    @Test fun rejectsOneByteBeyondLimitWithoutWritingOverflow() {
        val source = ByteArray(EmbeddedBookmarkJson.MAX_BYTES.toInt() + 1) { 42 }
        val output = ByteArrayOutputStream()
        val error = assertThrows(IOException::class.java) {
            EmbeddedBookmarkJson.copyBounded(ByteArrayInputStream(source), output)
        }
        assertEquals(true, error.message?.contains("16 MiB"))
        assertEquals(EmbeddedBookmarkJson.MAX_BYTES, output.size().toLong())
    }

    @Test fun refusesEmptyProviderContent() {
        assertThrows(IOException::class.java) {
            EmbeddedBookmarkJson.copyBounded(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream())
        }
    }

    @Test fun refusesProviderThatNeverMakesProgress() {
        val stalled = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        assertThrows(IOException::class.java) {
            EmbeddedBookmarkJson.copyBounded(stalled, ByteArrayOutputStream())
        }
    }

    @Test fun propagatesMidstreamProviderFailure() {
        val broken = object : InputStream() {
            var first = true
            override fun read(): Int = throw IOException("provider failure")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!first) throw IOException("provider failure")
                first = false
                b[off] = 123
                return 1
            }
        }
        assertThrows(IOException::class.java) {
            EmbeddedBookmarkJson.copyBounded(broken, ByteArrayOutputStream())
        }
    }
}
