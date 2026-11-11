package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.github.artcraftandroid.pdfcraft.ui.viewer.ZoomBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomBoxTest {
    private val pad = Size(1000f, 300f)
    private val box = ZoomBox.start(page = 0, pageWidth = 600f, pageHeight = 800f, atY = 100f, pad = pad)

    @Test
    fun padPointsMapToPageCoordinatesAtAnyMagnification() {
        assertEquals(200f, box.width, 0.01f)
        assertEquals(60f, box.height, 0.01f)
        val m = box.magnification(pad)
        assertEquals(5f, m, 0.001f)
        val p = box.toPage(Offset(500f, 150f), pad)
        assertEquals(box.x + 100f, p.x, 0.01f)
        assertEquals(box.y + 30f, p.y, 0.01f)
        // The page origin drawn in the pad maps back to page (0, 0).
        val o = box.pageOriginInPad(pad)
        assertEquals(0f, box.toPage(o, pad).x, 0.01f)
        assertEquals(0f, box.toPage(o, pad).y, 0.01f)
    }

    @Test
    fun writingAcrossALineAdvancesThenWrapsThenTurnsThePage() {
        var b = box
        assertTrue(b.inAdvanceZone(b.x + b.width * 0.9f))
        assertFalse(b.inAdvanceZone(b.x + b.width * 0.5f))
        b = b.advanced(600f, 800f, 2)
        assertEquals(box.x + 120f, b.x, 0.01f)
        b = b.advanced(600f, 800f, 2)
        b = b.advanced(600f, 800f, 2)
        // Past the right margin: back to the line start, one line down.
        assertEquals(box.lineStart, b.x, 0.01f)
        assertEquals(box.y + box.height * 0.9f, b.y, 0.01f)
        while (b.page == 0) b = b.nextLine(600f, 800f, 2)
        assertEquals(1, b.page)
        assertEquals(box.lineStart, b.x, 0.01f)
        // The last page's last line stays put.
        var last = b
        repeat(40) { last = last.nextLine(600f, 800f, 2) }
        assertEquals(1, last.page)
        assertTrue(last.y + last.height <= 800f)
    }

    @Test
    fun draggingKeepsTheBoxOnItsPage() {
        val moved = box.movedBy(-1000f, 5000f, 600f, 800f)
        assertEquals(0f, moved.x, 0f)
        assertEquals(800f - box.height, moved.y, 0.01f)
    }
}
