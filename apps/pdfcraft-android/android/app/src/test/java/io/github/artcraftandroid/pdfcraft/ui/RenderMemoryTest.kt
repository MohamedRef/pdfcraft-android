package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.engine.paddedRasterRows
import io.github.artcraftandroid.pdfcraft.ui.viewer.ImageKey
import io.github.artcraftandroid.pdfcraft.ui.viewer.ImageRequest
import io.github.artcraftandroid.pdfcraft.ui.viewer.nextRenderRequest
import org.junit.Assert.*
import org.junit.Test

class RenderMemoryTest {
    @Test fun budgetUsesActualHeapRatherThanAdvertisedLargeHeap() {
        assertEquals(32 * 1024 * 1024, runtimeImageBudget(256L * 1024 * 1024))
        assertEquals(64 * 1024 * 1024, runtimeImageBudget(512L * 1024 * 1024))
        assertEquals(192 * 1024 * 1024, runtimeImageBudget(Long.MAX_VALUE))
        assertEquals(0, runtimeImageBudget(0))
    }
    @Test fun oddWidthCompactRowsKeepPixelsAndPadEachAndroidRow() {
        val pixels = byteArrayOf(1,2,3,4,5,6,7,8,9,10,11,12)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,0,0,7,8,9,10,11,12,0,0), paddedRasterRows(pixels,6,8,2))
        assertSame(pixels, paddedRasterRows(pixels,6,6,2))
        assertThrows(IllegalArgumentException::class.java) { paddedRasterRows(pixels,6,4,2) }
    }
    @Test fun visibleWorkWinsAndPrefetchWaitsForFirstCanvas() {
        val speculative = ImageRequest(ImageKey(2,0,0),1f,null,prefetch=true)
        val visible = ImageRequest(ImageKey(0,0,0),1f,null)
        val detail = ImageRequest(ImageKey(0,0,1,0),2f,null)
        val requests = listOf(speculative,visible,detail)
        assertEquals(visible,nextRenderRequest(requests,emptySet(),emptySet(),emptySet(),false))
        assertEquals(detail,nextRenderRequest(requests,setOf(visible.key),emptySet(),emptySet(),true))
        assertNull(nextRenderRequest(requests,setOf(visible.key),setOf(detail.key),emptySet(),false))
        assertEquals(speculative,nextRenderRequest(requests,setOf(visible.key),setOf(detail.key),emptySet(),true))
        assertNull(nextRenderRequest(requests,setOf(visible.key),setOf(detail.key),setOf(speculative.key),true))
    }
}
