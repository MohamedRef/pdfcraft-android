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

    @Test fun dragMovesToTheSlotAndLevel() {
        // C to the top
        assertEquals(mapOf("path" to listOf(3), "position" to 1), outlineDropArgs(rows, listOf(3), 0, 0))
        // B dragged right under A2: the last child of A
        assertEquals(mapOf("path" to listOf(2), "parent" to listOf(1), "position" to 3), outlineDropArgs(rows, listOf(2), 3, 1))
        // A1 dragged left out of A, before B
        assertEquals(mapOf("path" to listOf(1, 1), "position" to 2), outlineDropArgs(rows, listOf(1, 1), 2, 0))
        // A with its children to the end, as far right as it goes: under C
        assertEquals(mapOf("path" to listOf(1), "parent" to listOf(3), "position" to 1), outlineDropArgs(rows, listOf(1), 2, 5))
    }

    @Test fun dragLevelIsKeptValid() {
        val rest = outlineRowsWithout(rows, listOf(3))
        assertEquals(listOf("A", "A1", "A2", "B"), rest.map { it.title })
        // between A and A1 only a child of A keeps A1 under A
        assertEquals(1..1, outlineDropDepths(rest, 1))
        assertEquals(mapOf("path" to listOf(3), "parent" to listOf(1), "position" to 1), outlineDropArgs(rows, listOf(3), 1, 0))
        // after A2: a child of A2, a child of A, or the top level
        assertEquals(0..2, outlineDropDepths(rest, 3))
        assertEquals(0..0, outlineDropDepths(rest, 0))
        // the children go along: none of A's are left to drop among
        assertEquals(listOf("B", "C"), outlineRowsWithout(rows, listOf(1)).map { it.title })
    }

    @Test fun dropInPlaceDoesNothing() {
        assertNull(outlineDropArgs(rows, listOf(2), 3, 0))
        assertNull(outlineDropArgs(rows, listOf(1, 2), 2, 1))
        assertNull(outlineDropArgs(rows, listOf(9), 0, 0))
    }
}
