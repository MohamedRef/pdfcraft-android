package io.github.artcraftandroid.pdfcraft.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class Utf8TextRangeTest {
    @Test fun unicodeAndReverseSelectionsUseExactUtf8Boundaries() {
        val text = "ع😀2²"
        assertEquals(listOf(6, 7), nativeUtf8Range(text, 3, 4))
        assertEquals(listOf(2, 6), nativeUtf8Range(text, 3, 1))
        assertEquals(listOf(7, 9), nativeUtf8Range(text, 4, 5))
        assertEquals(listOf(0, 9), nativeUtf8Range(text, text.length, 0))
    }
    @Test fun cursorDoesNotAccidentallySendAnEmptyNativeRange() {
        assertNull(nativeUtf8Range("H2O", 1, 1))
        assertNull(nativeUtf8Range("", 0, 0))
    }
    @Test fun invalidBoundsAndSurrogateSplitsAreRejected() {
        for ((a, b) in listOf(-1 to 0, 0 to 5, 1 to 2, 2 to 3)) {
            assertThrows(IllegalArgumentException::class.java) { nativeUtf8Range("A😀B", a, b) }
        }
    }
}
