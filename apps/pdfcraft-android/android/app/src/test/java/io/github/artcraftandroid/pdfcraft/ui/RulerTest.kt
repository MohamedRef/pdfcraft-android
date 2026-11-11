package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.Ruler
import io.github.artcraftandroid.pdfcraft.ui.viewer.rulerDisplayAngle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RulerTest {
    private val flat = Ruler(Offset(500f, 500f), 0f, halfWidth = 40f, halfLength = 1000f)

    @Test
    fun strokesBesideAnEdgeSnapToItAndOthersDoNot() {
        assertEquals(0, flat.snapEdge(Offset(300f, 450f), reach = 24f))
        assertEquals(1, flat.snapEdge(Offset(300f, 555f), reach = 24f))
        assertNull(flat.snapEdge(Offset(300f, 600f), reach = 24f))
        assertNull(flat.snapEdge(Offset(3000f, 455f), reach = 24f))
    }

    @Test
    fun projectedPointsOfAWobblyStrokeAreCollinearAtAnyAngle() {
        for (angle in listOf(0f, 15f, 37f, 90f, -60f)) {
            val r = flat.copy(angleDeg = angle)
            val wobbly = (0..20).map { i -> r.center + r.along * (i * 20f) + r.normal * (45f + (i % 3) * 4f) }
            val edge = r.snapEdge(wobbly.first(), 24f)!!
            val pts = wobbly.map { r.project(it, edge, outset = 1.5f) }
            val a = pts.first()
            val b = pts.last()
            pts.forEach { p ->
                val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
                assertTrue("angle $angle", abs(cross) / (b - a).getDistance() < 0.01f)
            }
        }
    }

    @Test
    fun angleSnapsToFifteenDegreeStepsOnlyWhenClose() {
        assertEquals(45f, rulerDisplayAngle(44f), 0f)
        assertEquals(90f, rulerDisplayAngle(91.5f), 0f)
        assertEquals(0f, rulerDisplayAngle(-1f), 0f)
        assertEquals(38f, rulerDisplayAngle(38f), 0f)
    }

    @Test
    fun twoFingerMoveAndRotateAboutTheirCentroid() {
        val moved = flat.transformed(Offset(10f, -5f), 90f, pivot = Offset(500f, 500f))
        assertEquals(510f, moved.center.x, 0.01f)
        assertEquals(495f, moved.center.y, 0.01f)
        assertEquals(90f, moved.angleDeg, 0.01f)
        assertTrue(moved.contains(Offset(510f, 900f)))
        assertFalse(moved.contains(Offset(900f, 495f)))
    }
}
