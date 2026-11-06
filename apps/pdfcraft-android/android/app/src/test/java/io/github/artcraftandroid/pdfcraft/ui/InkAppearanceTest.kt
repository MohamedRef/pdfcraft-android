package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.InkAppearance
import io.github.artcraftandroid.pdfcraft.ui.viewer.consecutiveInkBatches
import org.junit.Assert.*
import org.junit.Test

class InkAppearanceTest {
    private val red = InkAppearance(0xffff0000.toInt(), 1f, 2f, "A")
    private val blue = red.copy(argb = 0xff0000ff.toInt(), width = 6f)

    @Test fun overlappingColorsRetainOriginalPaintOrder() {
        val strokes = listOf(red, red, blue, red)
        val groups = consecutiveInkBatches(strokes) { it }
        assertEquals(listOf(2, 1, 1), groups.map { it.size })
        assertEquals(strokes, groups.flatten())
    }
    @Test fun pageOpacityAndAuthorChangesSplitSavedAnnotations() {
        val keys = listOf(0 to red, 1 to red, 1 to red.copy(opacity = 0.5f), 1 to red.copy(author = "B"))
        assertEquals(4, consecutiveInkBatches(keys) { it }.size)
        assertTrue(consecutiveInkBatches(emptyList<Int>()) { it }.isEmpty())
    }
    @Test fun changingPenForNextStrokeDoesNotRecolorEarlierInk() {
        var selected = red
        val first = selected
        selected = blue
        val second = selected
        assertEquals(red, first)
        assertEquals(2f, first.width)
        assertEquals(blue, second)
    }
}
