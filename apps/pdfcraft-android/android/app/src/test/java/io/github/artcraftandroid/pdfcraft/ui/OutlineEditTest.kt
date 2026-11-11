package io.github.artcraftandroid.pdfcraft.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutlineEditTest {
    // 1 A
    //   1.1 A1
    //   1.2 A2
    // 2 B
    // 3 C
    private val rows = listOf(
        OutlineRow(listOf(1), "A", 0),
        OutlineRow(listOf(1, 1), "A1", 1),
        OutlineRow(listOf(1, 2), "A2", 2),
        OutlineRow(listOf(2), "B", 3),
        OutlineRow(listOf(3), "C", 4),
    )

    @Test fun countsSiblings() {
        assertEquals(3, siblingCount(rows, emptyList()))
        assertEquals(2, siblingCount(rows, listOf(1)))
        assertEquals(0, siblingCount(rows, listOf(2)))
    }

    @Test fun movesUpAndDownWithinTheLevel() {
        assertEquals(mapOf("path" to listOf(2), "position" to 1), outlineMoveArgs(rows, listOf(2), OutlineMove.Up))
        assertNull(outlineMoveArgs(rows, listOf(1), OutlineMove.Up))
        assertEquals(mapOf("path" to listOf(1, 1), "parent" to listOf(1), "position" to 2), outlineMoveArgs(rows, listOf(1, 1), OutlineMove.Down))
        assertNull(outlineMoveArgs(rows, listOf(3), OutlineMove.Down))
        assertNull(outlineMoveArgs(rows, listOf(1, 2), OutlineMove.Down))
    }

    @Test fun nestsUnderTheBookmarkAboveAndComesBackOut() {
        // B becomes the last child of A
        assertEquals(mapOf("path" to listOf(2), "parent" to listOf(1)), outlineMoveArgs(rows, listOf(2), OutlineMove.In))
        assertNull(outlineMoveArgs(rows, listOf(1), OutlineMove.In))
        // A2 goes to the top level right after A
        assertEquals(mapOf("path" to listOf(1, 2), "position" to 2), outlineMoveArgs(rows, listOf(1, 2), OutlineMove.Out))
        assertNull(outlineMoveArgs(rows, listOf(3), OutlineMove.Out))
    }
}
