package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkOutlineBuilder
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkOutlineSink
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.PencilGrain
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureRange
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkSegment
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkSmoothSegment
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkSmoothSegments
import io.github.artcraftandroid.pdfcraft.ui.viewer.pressureInkRadii
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * #609: the stroke being written does work for its new points only, and its outline is one ribbon
 * that paints what a disc at every point and a quadrilateral between samples painted.
 */
class InkOutlineBuilderTest {
    private class Pieces : InkOutlineSink {
        val discs = ArrayList<Pair<Offset, Float>>()
        val polygons = ArrayList<List<Offset>>()
        override fun disc(center: Offset, radius: Float) { discs += center to radius }
        override fun polygon(corners: List<Offset>) { polygons += corners }
    }

    private fun line(n: Int) = List(n) { Offset(it * 0.8f, 6f * sin(it * 0.21f)) }
    private fun radii(n: Int) = List(n) { 0.6f + 0.3f * sin(it * 0.13f) }

    /** The whole outline of a finished line, in one pass. */
    private fun whole(points: List<Offset>, radii: List<Float>, level: (Int) -> Int = { 0 }): Map<Int, Pieces> {
        val all = HashMap<Int, Pieces>()
        InkOutlineBuilder().update(
            points.size, points.size, points.size, closed = true, point = { points[it] }, radius = { radii[it] },
            segmentLevel = level, loneLevel = { 0 }, settledSink = { all.getOrPut(it) { Pieces() } }, liveSink = { throw AssertionError("nothing is live after the lift") },
        )
        return all
    }

    @Test fun oneSegmentIsTheSameAsTheListOfThem() {
        val pts = line(12)
        val all = inkSmoothSegments(pts)
        for (i in 0 until pts.lastIndex) assertEquals(all[i], inkSmoothSegment({ pts[it] }, i, pts.lastIndex))
    }

    @Test fun aGrowingLineIsTheSameOutlineForNewPointsOnly() {
        val pts = line(600)
        val r = radii(600)
        val builder = InkOutlineBuilder()
        val settled = Pieces()
        var live = Pieces()
        val work = ArrayList<Int>()
        for (n in 1..pts.size) {
            live = Pieces()
            builder.update(n, n, n, closed = false, point = { pts[it] }, radius = { r[it] }, segmentLevel = { 0 }, loneLevel = { 0 }, settledSink = { settled }, liveSink = { live })
            work += builder.touched
            // at every length: what is kept and what is live are together the whole outline, each
            // piece once, and one disc more where the two paths meet
            if (n % 97 == 0 || n == pts.size) {
                val all = whole(pts.subList(0, n), r.subList(0, n)).getValue(0)
                assertEquals(all.polygons.toSet(), (settled.polygons + live.polygons).toSet())
                assertEquals(all.polygons.size, settled.polygons.size + live.polygons.size)
                assertEquals(all.discs.toSet() + (pts[builder.segments] to r[builder.segments]), (settled.discs + live.discs).toSet())
                // a smooth line is a ribbon with a disc at each end: no disc is kept but the first
                assertEquals(listOf(pts[0] to r[0]), settled.discs)
            }
        }
        // a new point costs the same at the 20th point and at the 600th
        assertTrue("work per frame: ${work.take(30)} … ${work.takeLast(5)}", work.drop(20).max() <= 5)
        assertEquals(work[25], work.last())
    }

    @Test fun thePointedEndIsRebuiltAndNothingElse() {
        // the last 12 points have provisional widths and the line runs on to the pen (#611)
        val pts = line(400)
        val r = radii(400)
        val builder = InkOutlineBuilder()
        val settled = Pieces()
        var live = Pieces()
        val work = ArrayList<Int>()
        for (n in 20..pts.size) {
            live = Pieces()
            val from = n - 12
            val tip = pts[n - 1] + Offset(0.3f, 0.1f)
            builder.update(
                n, n + 1, from, closed = false,
                point = { if (it < n) pts[it] else tip }, radius = { if (it < from) r[it] else r[it.coerceAtMost(n - 1)] * 0.5f },
                segmentLevel = { 0 }, loneLevel = { 0 }, settledSink = { settled }, liveSink = { live },
            )
            work += builder.touched
            // nothing that depends on a provisional width was kept
            assertTrue(builder.segments <= from - 2)
        }
        // what was kept along the way is what the finished line has there
        val all = whole(pts, r).getValue(0)
        assertTrue(all.polygons.containsAll(settled.polygons))
        // the work is for the provisional end, as much at the 30th point as at the 400th
        assertTrue("work per frame: ${work.takeLast(5)}", work.drop(5).max() <= 14 + 6)
        assertTrue(work.takeLast(100).max() <= work.subList(5, 105).max())
    }

    @Test fun aPencilsStepsMeetUnderADisc() {
        val pts = line(200)
        val r = pressureInkRadii(List(200) { 1.5f })
        val densities = List(200) { 0.35f + 0.3f * (1f + sin(it * 0.07f)) }
        fun segmentLevel(i: Int) = PencilGrain.level((PencilGrain.saved(densities[i]) + PencilGrain.saved(densities[i + 1])) / 2.0)
        val builder = InkOutlineBuilder()
        val settled = HashMap<Int, Pieces>()
        var live = HashMap<Int, Pieces>()
        for (n in 2..pts.size) {
            live = HashMap()
            builder.update(n, n, n, closed = false, point = { pts[it] }, radius = { r[it] }, segmentLevel = ::segmentLevel, loneLevel = { 0 }, settledSink = { settled.getOrPut(it) { Pieces() } }, liveSink = { live.getOrPut(it) { Pieces() } })
            assertTrue(builder.touched <= 6)
        }
        val all = whole(pts, r, ::segmentLevel)
        var changes = 0
        for (i in 1 until pts.lastIndex) {
            val (a, b) = segmentLevel(i - 1) to segmentLevel(i)
            if (a == b) continue
            changes++
            // where the step changes the lighter step has a disc at the point, the other has none
            for (pieces in listOf(all, settled)) {
                if (pieces === settled && i >= builder.segments) continue
                assertEquals("point $i", 1, pieces.getValue(minOf(a, b)).discs.count { it.first == pts[i] })
                assertEquals("point $i", 0, pieces.getValue(maxOf(a, b)).discs.count { it.first == pts[i] })
            }
        }
        assertTrue(changes >= 4)
        // every segment's ribbon is at its own step
        for ((level, pieces) in all) assertEquals((0 until pts.lastIndex).count { segmentLevel(it) == level }, pieces.polygons.size)
    }

    /** Whether [p] is inside the closed polygon [ring] wound with positive area: its winding number there. */
    private fun winding(ring: List<Offset>, p: Offset): Int {
        var w = 0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            val side = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
            if (a.y <= p.y && b.y > p.y && side > 0f) w++ else if (a.y > p.y && b.y <= p.y && side < 0f) w--
        }
        return w
    }

    /**
     * Samples along the smoothed line with their radii: as the outline had them before (about one
     * per smaller radius along each segment, at most 8), or [dense], 16 a segment.
     */
    private fun samplesBefore(pts: List<Offset>, radii: List<Float>, dense: Boolean = false): List<Pair<Offset, Float>> {
        val out = arrayListOf(pts[0] to radii[0])
        inkSmoothSegments(pts).forEachIndexed { i, (c1, c2, end) ->
            val p0 = pts[i]
            val steps = if (dense) 16 else kotlin.math.ceil((end - p0).getDistance() / maxOf(minOf(radii[i], radii[i + 1]), 0.5f)).coerceIn(1f, 8f).toInt()
            for (k in 1..steps) {
                val t = k.toFloat() / steps
                val u = 1f - t
                out += (p0 * (u * u * u) + c1 * (3f * u * u * t) + c2 * (3f * u * t * t) + end * (t * t * t)) to (radii[i] + (radii[i + 1] - radii[i]) * t)
            }
        }
        return out
    }

    @Test fun theRibbonPaintsWhatTheDiscsAndQuadsPainted() {
        // Within 0.035 pt, which is 0.1 dp on a page shown at 3 dp a point: everything the old
        // outline inked is inked, and nothing is inked outside the old outline or the line itself
        // (a disc of the line's width at every place along its middle). The old outline had a
        // notch between two quads on the outside of a bend where there was no point; the ribbon
        // has none.
        val tol = 0.035f
        val hand = PressureRange(0.08f, 0.18f)
        var lines = 0
        var discs = 0
        var points = 0
        for ((style, width) in listOf(InkStyle.Fountain to 1.5f, InkStyle.Brush to 3f, InkStyle.Ballpoint to 0.8f)) {
            for ((name, stroke) in PenSheet.strokes(false, 1.2f)) {
                val line = PenSheet.draw(style, stroke, false, width, 1.2f, hand)
                val pts = line.points
                if (pts.size < 2) continue
                val radii = pressureInkRadii(line.widths)
                val now = whole(pts, radii).getValue(0)
                val samples = samplesBefore(pts, radii)
                val theLine = samplesBefore(pts, radii, dense = true)
                fun before(p: Offset, grow: Float): Boolean {
                    if (pts.indices.any { (p - pts[it]).getDistance() <= radii[it] + grow }) return true
                    if (grow > 0f && theLine.any { (p - it.first).getDistance() <= it.second + grow }) return true
                    for (i in 1 until samples.size) {
                        val (a, ra) = samples[i - 1]
                        val (b, rb) = samples[i]
                        val d = b - a
                        val len = d.getDistance()
                        if (len < 1e-6f || (p - a).getDistance() > len + ra + rb + 1f) continue
                        val n = Offset(-d.y / len, d.x / len)
                        val (ga, gb) = maxOf(ra + grow, 0f) to maxOf(rb + grow, 0f)
                        if (winding(listOf(a + n * ga, a - n * ga, b - n * gb, b + n * gb), p) != 0) return true
                    }
                    return false
                }
                fun painted(p: Offset): Boolean {
                    var w = 0
                    for (ring in now.polygons) {
                        val one = winding(ring, p)
                        assertTrue("$style $name: a piece winds the other way", one >= 0)
                        w += one
                    }
                    return w > 0 || now.discs.any { (c, r) -> (p - c).getDistance() <= r }
                }
                val x0 = pts.minOf { it.x } - 2f
                val x1 = pts.maxOf { it.x } + 2f
                val y0 = pts.minOf { it.y } - 2f
                val y1 = pts.maxOf { it.y } + 2f
                var inked = 0
                var y = y0
                while (y <= y1) {
                    var x = x0
                    while (x <= x1) {
                        val p = Offset(x, y)
                        val isInked = painted(p)
                        if (before(p, -tol)) assertTrue("$style $name: $p was inked and is not", isInked)
                        if (isInked) {
                            inked++
                            assertTrue("$style $name: $p is inked and was not", before(p, tol))
                        }
                        x += 0.093f
                    }
                    y += 0.093f
                }
                assertTrue("$style $name: $inked", inked > 20)
                lines++
                discs += now.discs.size
                points += pts.size
            }
        }
        assertTrue(lines >= 12)
        // far fewer discs: before, one at every point. Now at the ends and where the pen turns
        // within the line's own width (a pause, a wide brush)
        assertTrue("$discs discs for $points points", discs <= points / 8)
    }

    /** A number as the engine writes it: three decimals, without trailing zeros. */
    private fun n(v: Float) = String.format(java.util.Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')

    @Test fun theSheetCostsAFractionOfWhatItDid() {
        // #609 measured the sheet's six strokes (Fountain, 1.5 pt pen, 1.2 dp a point) as the
        // engine writes them: 2,056 points, 532 KB and 23,296 path operators for 3.7 s of pen on
        // glass. Counted here the same way: a ribbon is `m`, an `l` for each further corner and
        // `h`; a disc is `m`, four `c` and `h`.
        var points = 0
        var operators = 0
        var bytes = 0
        for ((_, stroke) in PenSheet.strokes(false, 1.2f)) {
            val line = PenSheet.draw(InkStyle.Fountain, stroke, false, 1.5f, 1.2f, PressureRange(0.08f, 0.18f))
            val radii = pressureInkRadii(line.widths)
            points += line.points.size
            // as the engine joins them: one ribbon while neighbouring quads share their ends
            var open = false
            fun corner(p: Offset) { operators++; bytes += n(p.x).length + n(p.y).length + 4 }
            for (i in 0 until line.points.lastIndex) for (q in inkSegment({ line.points[it] }, { radii[it] }, i, line.points.lastIndex).quads) {
                if (q == null) { open = false; continue }
                if (!(q.shared && open)) {
                    operators++
                    bytes += 2
                    q.corners.take(2).forEach(::corner)
                }
                q.corners.drop(2).forEach(::corner)
                open = q.shared
            }
            val discs = whole(line.points, radii).getValue(0).discs
            operators += 6 * discs.size
            bytes += discs.sumOf { (c, r) -> 2 + 13 * (n(c.x + r).length + n(c.y + r).length + 2) }
        }
        val seconds = 3.7f
        println("the sheet: $points points, $operators operators, $bytes bytes; a minute: ${operators / seconds * 60f} operators, ${bytes / seconds * 60f / 1e6f} MB before Flate")
        assertTrue("$operators operators", operators < 23_296 / 5)
        assertTrue("$bytes bytes", bytes < 532_000 / 6)
    }
}
