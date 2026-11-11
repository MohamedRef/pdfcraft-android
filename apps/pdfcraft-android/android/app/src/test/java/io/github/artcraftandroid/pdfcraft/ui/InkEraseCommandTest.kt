package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.MAX_ERASER_PATH_POINTS
import io.github.artcraftandroid.pdfcraft.ui.viewer.appendEraserPoint
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkEraseCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InkEraseCommandTest {
    @Test fun eraserIncludesFreehandMarkerButNotOtherAnnotationKinds() {
        val command = inkEraseCommand(0, listOf(Offset(12f, 30f), Offset(18f, 42f)))
        assertEquals(1, command["page"])
        assertEquals(true, command["erase_highlights"])
        assertEquals(8.0, command["radius"])
        assertEquals(listOf(listOf(12.0, 30.0), listOf(18.0, 42.0)), command["path"])
        assertEquals(setOf("page", "path", "radius", "erase_highlights"), command.keys)
    }

    @Test fun eraserPreservesPageNumberAndRadius() {
        val command = inkEraseCommand(6, listOf(Offset(1f, 2f)), 11.5)
        assertEquals(7, command["page"])
        assertEquals(11.5, command["radius"])
    }

    @Test fun rejectsInvalidGestureBeforeSendingToNative() {
        val valid = Offset(1f, 2f)
        assertThrows(IllegalArgumentException::class.java) { inkEraseCommand(-1, listOf(valid)) }
        assertThrows(IllegalArgumentException::class.java) { inkEraseCommand(0, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { inkEraseCommand(0, List(4097) { valid }) }
        assertThrows(IllegalArgumentException::class.java) { inkEraseCommand(0, listOf(Offset(Float.NaN, 3f))) }
        assertThrows(IllegalArgumentException::class.java) { inkEraseCommand(0, listOf(valid), Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { inkEraseCommand(0, listOf(valid), 100.1) }
    }

    /** A long scrub: zig-zags 40 pt wide, 1.5 pt apart vertically, like rubbing out one line. */
    private fun scrub(n: Int) = List(n) { i -> Offset(if (i % 2 == 0) 100f else 140f, 300f + (i % 7) * 1.5f) }

    @Test fun longScrubIsNeverThinnedIntoChords() {
        val input = scrub(2000)
        val path = mutableListOf<Offset>()
        val flushed = mutableListOf<List<Offset>>()
        for (p in input) appendEraserPoint(path, p, 4f)?.let(flushed::add)
        assertTrue("2000 points fit one path", flushed.isEmpty())
        // Every committed segment is one the pen actually drew: none is longer than the input's.
        val longest = input.zipWithNext { a, b -> (b - a).getDistance() }.max()
        assertTrue(path.zipWithNext { a, b -> (b - a).getDistance() }.all { it <= longest + 1e-3f })
        assertEquals(input, path)
    }

    @Test fun overlongGestureIsSplitIntoConnectedChunks() {
        val input = scrub(10_000)
        val path = mutableListOf<Offset>()
        val flushed = mutableListOf<List<Offset>>()
        for (p in input) appendEraserPoint(path, p, 4f)?.let(flushed::add)
        assertEquals(2, flushed.size)
        assertTrue(flushed.all { it.size == MAX_ERASER_PATH_POINTS })
        // Each chunk starts where the previous one ended, so no part of the gesture is skipped.
        assertEquals(flushed[0].last(), flushed[1].first())
        assertEquals(flushed[1].last(), path.first())
        assertEquals(input, flushed[0] + flushed[1].drop(1) + path.drop(1))
        inkEraseCommand(0, flushed[0])
    }

    @Test fun pointsCloserThanAnEighthOfTheRadiusAreSkipped() {
        val path = mutableListOf(Offset(10f, 10f))
        assertEquals(null, appendEraserPoint(path, Offset(10.4f, 10f), 4f))
        assertEquals(1, path.size)
        appendEraserPoint(path, Offset(10.6f, 10f), 4f)
        assertEquals(2, path.size)
    }
}
