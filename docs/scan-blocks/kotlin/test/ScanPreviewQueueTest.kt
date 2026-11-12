package io.github.artcraftandroid.pdfcraft.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "latest wins" rule for one preview target (#301, spec S15 section 2). */
class ScanPreviewQueueTest {
    private val corners: List<Float> = listOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    private fun key(photo: String, look: ScanPageLook = ScanPageLook(), maxSide: Int = 1280): ScanPreviewKey =
        ScanPreviewKey(photo, corners, look, maxSide)

    @Test
    fun theFirstAskStartsAndASecondWhileRunningWaits() {
        val queue = ScanPreviewQueue()
        val a = key("a")
        val b = key("b")
        assertEquals(1, queue.ask(a))
        assertTrue(queue.isRunning)
        assertNull(queue.ask(b))
        assertEquals(b, queue.waiting)
        val finished = queue.done(1)
        // a was the render of the key that is no longer wanted: not shown, and b starts now
        assertFalse(finished.show)
        assertEquals(b, finished.next)
        assertEquals(2, finished.nextTicket)
        assertNull(queue.shownKey)
        val last = queue.done(2)
        assertTrue(last.show)
        assertNull(last.next)
        assertNull(last.nextTicket)
        assertEquals(b, queue.shownKey)
        assertFalse(queue.isRunning)
    }

    @Test
    fun aThirdRequestReplacesTheSecondAndTheSecondNeverRuns() {
        val queue = ScanPreviewQueue()
        val a = key("a")
        val b = key("b")
        val c = key("c")
        assertEquals(1, queue.ask(a))
        assertNull(queue.ask(b))
        assertNull(queue.ask(c))
        assertEquals(c, queue.waiting)
        val first = queue.done(1)
        assertFalse(first.show)
        assertEquals(c, first.next)
        assertEquals(2, first.nextTicket)
        val second = queue.done(2)
        assertTrue(second.show)
        assertNull(second.next)
        assertEquals(c, queue.shownKey)
    }

    @Test
    fun asksForWhatIsShownOrRunningDoNothing() {
        val queue = ScanPreviewQueue()
        val a = key("a")
        assertEquals(1, queue.ask(a))
        assertNull(queue.ask(a))
        assertNull(queue.waiting)
        assertTrue(queue.done(1).show)
        assertNull(queue.ask(a))
        assertNull(queue.ask(a))
        assertFalse(queue.isRunning)
        // b starts; asking for a, which is on screen, waits for nothing
        val b = key("b")
        assertEquals(2, queue.ask(b))
        assertNull(queue.ask(a))
        assertNull(queue.waiting)
        val stale = queue.done(2)
        assertFalse(stale.show)
        assertNull(stale.next)
        assertEquals(a, queue.shownKey)
    }

    @Test
    fun askingAgainForTheRunningKeyDropsTheWaitingOne() {
        val queue = ScanPreviewQueue()
        val a = key("a")
        val b = key("b")
        assertEquals(1, queue.ask(a))
        assertNull(queue.ask(b))
        assertNull(queue.ask(a))
        assertNull(queue.waiting)
        val finished = queue.done(1)
        assertTrue(finished.show)
        assertNull(finished.next)
        assertEquals(a, queue.shownKey)
    }

    @Test
    fun aThousandAsksInARowLeaveOneWaitingAndOneRender() {
        val queue = ScanPreviewQueue()
        assertEquals(1, queue.ask(key("first")))
        for (i in 0 until 1000) {
            assertNull(queue.ask(key("p$i")))
        }
        assertEquals(key("p999"), queue.waiting)
        val finished = queue.done(1)
        assertFalse(finished.show)
        assertEquals(key("p999"), finished.next)
        assertEquals(2, finished.nextTicket)
        assertTrue(queue.done(2).show)
        assertFalse(queue.isRunning)
    }

    @Test
    fun aDoneWithAnOldTicketChangesNothing() {
        val queue = ScanPreviewQueue()
        val a = key("a")
        assertEquals(1, queue.ask(a))
        val stray = queue.done(7)
        assertFalse(stray.show)
        assertNull(stray.next)
        assertTrue(queue.isRunning)
        assertTrue(queue.done(1).show)
        // the same ticket again, when nothing runs, is also nothing
        assertFalse(queue.done(1).show)
        assertEquals(a, queue.shownKey)
    }

    @Test
    fun aFailedRenderIsNotShownAndNotRenderedAgain() {
        val queue = ScanPreviewQueue()
        val a = key("a")
        assertEquals(1, queue.ask(a))
        val failed = queue.done(1, ok = false)
        assertFalse(failed.show)
        assertNull(failed.next)
        assertNull(queue.shownKey)
        assertFalse(queue.isRunning)
        // asking again starts it again: the person asked, so it may run
        assertEquals(2, queue.ask(a))
    }

    @Test
    fun aFailedRenderStillLetsAWaitingKeyStart() {
        val queue = ScanPreviewQueue()
        val a = key("a")
        val b = key("b")
        assertEquals(1, queue.ask(a))
        assertNull(queue.ask(b))
        val failed = queue.done(1, ok = false)
        assertFalse(failed.show)
        assertEquals(b, failed.next)
        assertEquals(2, failed.nextTicket)
        assertTrue(queue.done(2).show)
        assertEquals(b, queue.shownKey)
    }

    @Test
    fun aKeyThatDiffersInAnyPartIsAnotherPicture() {
        val queue = ScanPreviewQueue()
        val base = key("a")
        assertEquals(1, queue.ask(base))
        assertTrue(queue.done(1).show)
        // a new look, turn, size or corner is a new render
        assertEquals(2, queue.ask(key("a", ScanPageLook(userTurns = 1))))
        assertTrue(queue.done(2).show)
        assertEquals(3, queue.ask(key("a", maxSide = 200)))
        assertTrue(queue.done(3).show)
        assertFalse(base == key("a", maxSide = 200))
    }
}
