package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.engine.expectedBitmapByteCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnginePixelsTest {
    @Test
    fun nativePixelsMustFitAndroidHeapBudget() {
        assertEquals(612 * 792 * 4, expectedBitmapByteCount(612, 792))
        assertEquals(512 * 512 * 4, expectedBitmapByteCount(512, 512))
        assertNull(expectedBitmapByteCount(0, 10))
        assertNull(expectedBitmapByteCount(-1, 20))
        assertNull(expectedBitmapByteCount(100_000, 100_000))
        assertNull(expectedBitmapByteCount(8192, 8192))
        assertNull(expectedBitmapByteCount(Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
