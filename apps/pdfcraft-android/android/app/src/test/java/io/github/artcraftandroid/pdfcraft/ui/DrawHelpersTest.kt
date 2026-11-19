package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkAppearance
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkPattern
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStroke
import io.github.artcraftandroid.pdfcraft.ui.viewer.eraseDrafts
import io.github.artcraftandroid.pdfcraft.ui.viewer.layPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** DrawLayer's draft helpers, now that they take what they work on (#730): the eraser over drafts and a dashed line's pieces. */
class DrawHelpersTest {
    /** A 2 pt line on [page] from (0, y) to (100, y), a point every 10 pt. */
    private fun line(page: Int = 0, y: Float = 0f, pattern: InkPattern = InkPattern.Solid) =
        InkStroke(page, InkAppearance(0xFF000000.toInt(), 1f, 2f, null)).also { s ->
            for (x in 0..100 step 10) {
                s.points.add(Offset(x.toFloat(), y))
                s.scales.add(1f)
            }
            s.pattern = pattern
        }

    @Test
    fun theEraserCutsADraftWhereItPassesAndLeavesTheRest() {
        val cut = line()
        val otherPage = line(page = 1)
        val far = line(y = 200f)
        val strokes = mutableListOf(cut, otherPage, far)
        eraseDrafts(strokes, page = 0, a = Offset(50f, -5f), b = Offset(50f, 5f), radius = 3f, whole = false)
        // the cut line became its two ends, in its place; the others are the same objects
        assertEquals(4, strokes.size)
        assertSame(otherPage, strokes[2])
        assertSame(far, strokes[3])
        val (left, right) = strokes[0] to strokes[1]
        assertEquals(0f, left.points.first().x, 1e-3f)
        assertEquals(100f, right.points.last().x, 1e-3f)
        for (p in left.points + right.points) assertTrue("$p is under the eraser", kotlin.math.abs(p.x - 50f) >= 3f - 1e-3f)
        for (piece in listOf(left, right)) assertEquals(piece.points.size, piece.scales.size)
    }

    @Test
    fun theWholeStrokeEraserTakesAllOfAStrokeItTouches() {
        val touched = line()
        val kept = line(page = 1)
        val strokes = mutableListOf(touched, kept)
        eraseDrafts(strokes, page = 0, a = Offset(50f, 0f), b = Offset(50f, 0f), radius = 3f, whole = true)
        assertEquals(listOf(kept), strokes)
    }

    @Test
    fun aDashedLineIsCutIntoPiecesOfOneGroupInItsPlace() {
        val before = line(y = -50f)
        val dashed = line(pattern = InkPattern.Dashed)
        val after = line(y = 50f)
        val strokes = mutableListOf(before, dashed, after)
        val pieces = layPattern(strokes, listOf(dashed))
        assertTrue("a 100 pt dashed line is more than one dash", pieces.size > 1)
        val group = pieces.first().group
        assertNotNull(group)
        for (piece in pieces) {
            assertSame(group, piece.group)
            assertEquals(InkPattern.Dashed, piece.pattern)
        }
        assertEquals(listOf(before) + pieces + listOf(after), strokes)
    }

    @Test
    fun aSolidLineIsItself() {
        val solid = line()
        val strokes = mutableListOf(solid)
        assertEquals(listOf(solid), layPattern(strokes, listOf(solid)))
        assertEquals(listOf(solid), strokes)
    }
}
