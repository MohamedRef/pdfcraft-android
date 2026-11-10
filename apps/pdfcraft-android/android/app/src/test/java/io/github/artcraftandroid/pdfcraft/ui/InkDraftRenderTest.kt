package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkNeedsLayer
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkPathKey
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkSmoothSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkDraftRenderTest {
    private fun close(a: Offset, b: Offset) {
        assertEquals(a.x, b.x, 1e-4f)
        assertEquals(a.y, b.y, 1e-4f)
    }

    @Test fun smoothingPassesEveryPointLikeTheEngineAppearance() {
        val pts = listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(20f, 10f), Offset(30f, 10f))
        val segs = inkSmoothSegments(pts)
        assertEquals(3, segs.size)
        segs.forEachIndexed { i, s -> close(pts[i + 1], s.end) }
        // pdfcraft_annot::appearance::smooth_segments, first segment: p0 = p1 (run start).
        close(Offset(0f + (10f - 0f) / 6f, 0f), segs[0].c1)
        close(Offset(10f - (20f - 0f) / 6f, 0f - (10f - 0f) / 6f), segs[0].c2)
        // Interior segment uses both real neighbours.
        close(Offset(10f + (20f - 0f) / 6f, 0f + (10f - 0f) / 6f), segs[1].c1)
    }

    @Test fun aRunIsSmoothedOnItsOwnPointsOnly() {
        val pts = listOf(Offset(0f, 0f), Offset(5f, 5f), Offset(10f, 0f), Offset(15f, 5f), Offset(20f, 0f))
        val run = inkSmoothSegments(pts, 1, 3)
        assertEquals(2, run.size)
        close(pts[2], run[0].end)
        close(pts[3], run[1].end)
        // The run's own start is its missing left neighbour, not the point before the run.
        close(Offset(5f + (10f - 5f) / 6f, 5f + (0f - 5f) / 6f), run[0].c1)
    }

    @Test fun degenerateRunsProduceNoCurves() {
        assertTrue(inkSmoothSegments(emptyList()).isEmpty())
        assertTrue(inkSmoothSegments(listOf(Offset(1f, 1f))).isEmpty())
        assertTrue(inkSmoothSegments(listOf(Offset(1f, 1f), Offset(2f, 2f)), 1, 1).isEmpty())
        assertTrue(inkSmoothSegments(listOf(Offset(1f, 1f), Offset(2f, 2f)), 0, 5).isEmpty())
    }

    @Test fun cacheKeyChangesWhenAStrokeGrowsMovesOrSnaps() {
        val a = listOf(Offset(0f, 0f), Offset(1f, 1f))
        val base = inkPathKey(a, null, 2, true, 3f)
        assertEquals(base, inkPathKey(a.toList(), null, 2, true, 3f))
        assertNotEquals(base, inkPathKey(a + Offset(2f, 2f), null, 3, true, 3f))
        assertNotEquals(base, inkPathKey(a.map { it + Offset(4f, 0f) }, null, 2, true, 3f))
        assertNotEquals(base, inkPathKey(a, listOf(Offset(0f, 0f), Offset(9f, 9f)), 2, true, 3f))
        assertNotEquals(base, inkPathKey(a, null, 2, false, 3f))
    }

    @Test fun onlyTranslucentMultiRunStrokesUseALayer() {
        assertFalse(inkNeedsLayer(1, 0.5f, highlighter = false))
        assertFalse(inkNeedsLayer(4, 1f, highlighter = false))
        assertTrue(inkNeedsLayer(4, 0.8f, highlighter = false))
        assertTrue(inkNeedsLayer(2, 1f, highlighter = true))
    }
}
