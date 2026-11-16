package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.INK_POINT_SPACING_DP
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkPointThinner
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #609 step 1: a freehand line keeps a point every 0.75 dp on the glass, and stays the same line. */
class InkThinningTest {
    private val hand = PressureRange(0.08f, 0.18f)

    /**
     * How far [p], given by the pen at [time], is from the pieces of [line] written around that
     * time (a loop crosses itself: the other pass is not the same part of the line), and the
     * line's width there (linear between points).
     */
    private fun nearest(p: Offset, time: Long, line: PenSheet.Line): Pair<Float, Float> {
        val pts = line.points
        val widths = line.widths
        var best = Float.MAX_VALUE
        var width = widths[0]
        for (i in 1 until pts.size) {
            if (line.times[i] < time || line.times[i - 1] > time) continue
            val line = pts
            val a = line[i - 1]
            val ab = line[i] - a
            val l2 = ab.x * ab.x + ab.y * ab.y
            val t = if (l2 <= 0f) 0f else (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / l2).coerceIn(0f, 1f)
            val d = (p - (a + ab * t)).getDistance()
            if (d < best) {
                best = d
                width = widths[i - 1] + (widths[i] - widths[i - 1]) * t
            }
        }
        return best to width
    }

    @Test fun theThinnedLineIsTheSameLine() {
        var all = 0
        var kept = 0
        for (style in listOf(InkStyle.Ballpoint, InkStyle.Fountain, InkStyle.Brush, InkStyle.Pencil)) for (dpPerPt in listOf(0.61f, 1.2f, 3f)) {
            for ((name, stroke) in PenSheet.strokes(false, dpPerPt)) {
                if (name == "dot") continue
                val full = PenSheet.draw(style, stroke, false, 1.5f, dpPerPt, hand, spacingDp = 0f)
                val thin = PenSheet.draw(style, stroke, false, 1.5f, dpPerPt, hand)
                all += full.points.size
                kept += thin.points.size
                // every point the pen gave is within 0.1 dp of the thinned line, centre and edge
                for (i in full.points.indices) {
                    val (d, w) = nearest(full.points[i], full.times[i], thin)
                    assertTrue("$style $name at $dpPerPt dp/pt: centre off by ${d * dpPerPt} dp", d * dpPerPt <= 0.1f)
                    val edge = kotlin.math.abs(w - full.widths[i]) / 2f
                    assertTrue("$style $name at $dpPerPt dp/pt: edge off by ${(d + edge) * dpPerPt} dp", (d + edge) * dpPerPt <= 0.1f)
                }
                // and it starts and ends where the pen did
                assertEquals(full.points.first(), thin.points.first())
                assertEquals(full.points.last(), thin.points.last())
            }
        }
        assertTrue("kept $kept of $all points", kept < all * 0.5f)
    }

    @Test fun pointsAreKeptTheSameDistanceApartOnTheGlassAtEveryZoom() {
        val counts = listOf(0.61f, 1.5f, 4f).map { dpPerPt ->
            PenSheet.draw(InkStyle.Fountain, PenSheet.strokes(false, dpPerPt).getValue("word"), false, 1.5f, dpPerPt, hand).points.size
        }
        assertTrue("$counts", counts.max() - counts.min() <= counts.min() / 10)
    }

    @Test fun aDotAndAShapeKeepTheirPoints() {
        // a dot: the pen hardly moves, and the line still has its first and its last sample
        val dot = PenSheet.draw(InkStyle.Fountain, PenSheet.strokes(false, 0.61f).getValue("dot"), false, 2f, 0.61f, hand)
        assertTrue(dot.points.size >= 2)
        // no spacing (a shape, or a size that cannot be right) keeps every sample
        for (spacing in listOf(0f, Float.NaN, -1f)) {
            val t = InkPointThinner(spacing)
            var n = 0
            repeat(20) { i -> t.add(Offset(i * 0.01f, 0f), 1f, 1f) { _, _, _ -> n++ } }
            assertEquals(20, n)
            assertNull(t.flush())
        }
    }

    @Test fun theLastSampleWaitsForTheLift() {
        val t = InkPointThinner(INK_POINT_SPACING_DP)
        val kept = ArrayList<Offset>()
        fun give(x: Float, scale: Float, density: Float) = t.add(Offset(x, 0f), scale, density) { p, _, _ -> kept += p }
        give(0f, 1f, 1f)
        give(0.3f, 1.1f, 0.5f)
        give(0.6f, 1.2f, 0.6f)
        assertEquals(listOf(Offset(0f, 0f)), kept)
        assertEquals(Triple(Offset(0.6f, 0f), 1.2f, 0.6f), t.pending)
        give(0.9f, 1.3f, 0.7f)
        assertEquals(listOf(Offset(0f, 0f), Offset(0.9f, 0f)), kept)
        assertNull(t.pending)
        give(1.2f, 1.4f, 0.8f)
        assertEquals(Triple(Offset(1.2f, 0f), 1.4f, 0.8f), t.flush())
        assertNull(t.flush())
    }

    @Test fun aCornerKeepsItsPoint() {
        // along x, then straight up: the sample at the corner is closer than the spacing and is kept all the same
        val t = InkPointThinner(1f, 0.05f)
        val kept = ArrayList<Offset>()
        val path = List(8) { Offset(it * 0.3f, 0f) } + List(8) { Offset(2.1f, (it + 1) * 0.3f) }
        for (p in path) t.add(p, 1f, 1f) { q, _, _ -> kept += q }
        assertTrue("$kept", path[7] in kept)
        assertTrue(kept.size < path.size / 2 + 2)
    }
}
