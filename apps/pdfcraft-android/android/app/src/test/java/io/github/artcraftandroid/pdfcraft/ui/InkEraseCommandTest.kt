package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
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
        assertThrows(IllegalArgumentException::class.java) { inkEraseCommand(0, List(513) { valid }) }
        assertThrows(IllegalArgumentException::class.java) { inkEraseCommand(0, listOf(Offset(Float.NaN, 3f))) }
        assertThrows(IllegalArgumentException::class.java) { inkEraseCommand(0, listOf(valid), Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { inkEraseCommand(0, listOf(valid), 100.1) }
    }
}
