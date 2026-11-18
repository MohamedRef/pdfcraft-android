package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkAppearance
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkLassoBounds
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStroke
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.eraseInkRun
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkEraserMisses
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkStylePointWidths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** #687: the eraser leaves a draft it cannot reach by its kept box, before copying its points. */
class InkEraserBoundsTest {
    @Test fun aStrokesBoxFollowsItsPoints() {
        val s = InkStroke(0, InkAppearance(0xFF000000.toInt(), 1f, 2f, null))
        assertNull(s.bounds())
        s.points.addAll(listOf(Offset(10f, 20f), Offset(30f, 5f)))
        assertEquals(InkLassoBounds(10f, 5f, 30f, 20f), s.bounds())
        s.points.add(Offset(50f, 60f))
        assertEquals(InkLassoBounds(10f, 5f, 50f, 60f), s.bounds())
        // a lasso move shifts every point in place
        for (i in s.points.indices) s.points[i] = s.points[i] + Offset(100f, 0f)
        assertEquals(InkLassoBounds(110f, 5f, 150f, 60f), s.bounds())
    }

    @Test fun theEraserMissesOnlyWhatItCannotReach() {
        val box = InkLassoBounds(100f, 100f, 200f, 150f)
        assertTrue(inkEraserMisses(box, Offset(0f, 0f), Offset(50f, 50f), 10f))
        // within the reach of an edge
        assertFalse(inkEraserMisses(box, Offset(95f, 120f), Offset(95f, 130f), 10f))
        // a fast move across the box from outside it on both sides
        assertFalse(inkEraserMisses(box, Offset(150f, 0f), Offset(150f, 300f), 1f))
    }

    /** Whatever the box rejects, the full cut finds nothing to erase either. */
    @Test fun aRejectedDraftIsOneTheCutWouldLeave() {
        val r = Random(687)
        var rejected = 0
        repeat(2000) {
            val n = 2 + r.nextInt(30)
            var p = Offset(r.nextFloat() * 500f, r.nextFloat() * 700f)
            val points = List(n) { p = p + Offset(r.nextFloat() * 20f - 10f, r.nextFloat() * 20f - 10f); p }
            val scales = List(n) { 0.05f + r.nextFloat() * 2.95f }
            val style = InkStyle.entries[r.nextInt(InkStyle.entries.size)]
            val width = 0.5f + r.nextFloat() * 10f
            val half = inkStylePointWidths(scales, width, style).map { it / 2f }
            val a = Offset(r.nextFloat() * 500f, r.nextFloat() * 700f)
            val b = a + Offset(r.nextFloat() * 60f - 30f, r.nextFloat() * 60f - 30f)
            val radius = 2f + r.nextFloat() * 30f
            val s = InkStroke(0, InkAppearance(0xFF000000.toInt(), 1f, width, null, style)).also { it.points.addAll(points) }
            // as eraseDrafts: the widest half of a line is at most three times its pen's width
            val widest = inkStylePointWidths(listOf(3f), width, style).first() / 2f
            if (inkEraserMisses(s.bounds()!!, a, b, radius + widest)) {
                rejected++
                assertNull("stroke $it", eraseInkRun(points, scales, half, a, b, radius))
            }
        }
        assertTrue("most random moves are far from a short stroke: $rejected", rejected > 1000)
    }
}
