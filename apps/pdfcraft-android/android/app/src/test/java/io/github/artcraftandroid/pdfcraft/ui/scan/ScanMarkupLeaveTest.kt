package io.github.artcraftandroid.pdfcraft.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/** Leaving Markup (#531): the arrow and the Back gesture keep the marks, as Done does. */
class ScanMarkupLeaveTest {
    private val stroke = ScanStrokeMark(ScanMarkTool.Pen, 0, 0.01f, floatArrayOf(0.1f, 0.1f, 1f, 0.4f, 0.1f, 1f))

    @Test
    fun aStrokeDrawnIsKeptWhicheverWayMarkupIsLeft() {
        val now = ScanMarks(1, listOf(stroke))
        for (way in ScanMarkupLeftBy.entries) {
            val left = scanMarkupLeave(now)
            assertEquals(way.id, now, left.kept)
            assertEquals(way.id, 0, left.discarded)
        }
    }

    @Test
    fun theLogSaysHowAndWhatWasDropped() {
        assertEquals(listOf("done", "back", "gesture"), ScanMarkupLeftBy.entries.map { it.id })
        val m = scanMarkupLeaveDiag(ScanMarkupLeftBy.Gesture, 0).toMap()
        assertEquals("gesture", m["left_by"])
        assertEquals(0, m["discarded"])
    }
}
