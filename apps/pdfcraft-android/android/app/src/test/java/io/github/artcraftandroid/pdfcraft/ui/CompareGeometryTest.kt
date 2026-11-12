package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.unionTopLeft
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Compare (#171): doc_compare's rectangles (origin bottom-left) shown on the newer page (origin top-left). */
class CompareGeometryTest {
    @Test
    fun rectanglesUniteAndFlipToTopLeft() {
        // the two lines doc_compare reported for a replaced change on a 792 pt page
        val box = unionTopLeft(listOf(floatArrayOf(246.73f, 717.6f, 388.97f, 729.6f), floatArrayOf(72f, 697.6f, 259.42f, 709.6f)), 792f)!!
        assertArrayEquals(floatArrayOf(72f, 62.4f, 388.97f, 94.4f), box, 0.01f)
    }

    @Test
    fun withoutAHeightTheBoxIsKeptAsIs() {
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), unionTopLeft(listOf(floatArrayOf(3f, 4f, 1f, 2f)), null)!!, 0f)
        assertNull(unionTopLeft(emptyList(), 100f))
    }
}
