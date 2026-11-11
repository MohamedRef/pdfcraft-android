package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkShapeMode
import io.github.artcraftandroid.pdfcraft.ui.viewer.STANDARD_STAMPS
import io.github.artcraftandroid.pdfcraft.ui.viewer.SnappedShape
import io.github.artcraftandroid.pdfcraft.ui.viewer.arrowPreview
import io.github.artcraftandroid.pdfcraft.ui.viewer.calloutBox
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkShapeGeometry
import io.github.artcraftandroid.pdfcraft.ui.viewer.isAnnotation
import io.github.artcraftandroid.pdfcraft.ui.viewer.PolyTap
import io.github.artcraftandroid.pdfcraft.ui.viewer.polyTap
import io.github.artcraftandroid.pdfcraft.ui.viewer.polyAnnotationArgs
import io.github.artcraftandroid.pdfcraft.ui.viewer.areaHighlightRect
import io.github.artcraftandroid.pdfcraft.ui.viewer.shapeAnnotationArgs
import io.github.artcraftandroid.pdfcraft.ui.viewer.squigglePoints
import io.github.artcraftandroid.pdfcraft.ui.viewer.stampLabel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationTypesTest {
    @Test fun everyShapeToolMakesARealAnnotation() {
        // #170: lines, rectangles and ovals are PDF shape annotations, as arrows and clouds are
        for (m in listOf(InkShapeMode.Arrow, InkShapeMode.Cloud, InkShapeMode.Line, InkShapeMode.Rectangle, InkShapeMode.Oval, InkShapeMode.Polygon, InkShapeMode.Polyline)) {
            assertTrue(m.name, m.isAnnotation)
        }
        assertFalse(InkShapeMode.Freehand.isAnnotation)
    }

    @Test fun lineRectangleAndOvalArgs() {
        val line = inkShapeGeometry(InkShapeMode.Line, Offset(10f, 20f), Offset(60f, 20f), 200f, 200f)!!
        val l = shapeAnnotationArgs(InkShapeMode.Line, line, 0, "#000000", 1f, 2f, null)!!
        assertEquals("line", l["type"])
        assertEquals(listOf(10.0, 20.0), l["from"])
        assertEquals(listOf(60.0, 20.0), l["to"])
        val rect = inkShapeGeometry(InkShapeMode.Rectangle, Offset(50f, 40f), Offset(10f, 90f), 200f, 200f)!!
        val r = shapeAnnotationArgs(InkShapeMode.Rectangle, rect, 1, "#FF0000", 1f, 2f, "me")!!
        assertEquals("rectangle", r["type"])
        assertEquals(listOf(10.0, 40.0, 50.0, 90.0), r["rect"])
        val oval = inkShapeGeometry(InkShapeMode.Oval, Offset(10f, 40f), Offset(50f, 90f), 200f, 200f)!!
        assertEquals("oval", shapeAnnotationArgs(InkShapeMode.Oval, oval, 1, "#FF0000", 1f, 2f, "me")!!["type"])
    }

    @Test fun tappedPolygonsFinishOnTheFirstOrLastPoint() {
        val pts = mutableListOf<Offset>()
        fun tap(x: Float, y: Float, closed: Boolean = true) = polyTap(pts, Offset(x, y), 6f, closed)
        assertEquals(PolyTap.Add, tap(10f, 10f)); pts.add(Offset(10f, 10f))
        // the same point again is not a corner (and too few points to finish)
        assertEquals(PolyTap.Ignore, tap(12f, 11f))
        assertEquals(PolyTap.Add, tap(100f, 10f)); pts.add(Offset(100f, 10f))
        // a polygon needs three corners; connected lines finish on the last point with two
        assertEquals(PolyTap.Ignore, tap(100f, 12f))
        assertEquals(PolyTap.Finish, tap(100f, 12f, closed = false))
        assertEquals(PolyTap.Add, tap(100f, 100f)); pts.add(Offset(100f, 100f))
        assertEquals(PolyTap.Finish, tap(11f, 9f))
        assertEquals(PolyTap.Finish, tap(99f, 101f))
        val args = polyAnnotationArgs(true, pts, 0, "#000000", 1f, 1f, null)!!
        assertEquals("polygon", args["type"])
        assertEquals(3, (args["points"] as List<*>).size)
        assertEquals("polyline", polyAnnotationArgs(false, pts.take(2), 0, "#000000", 1f, 1f, null)!!["type"])
        assertNull(polyAnnotationArgs(true, pts.take(2), 0, "#000000", 1f, 1f, null))
    }

    @Test fun areaHighlightNeedsARealDrag() {
        assertArrayEquals(floatArrayOf(10f, 20f, 60f, 80f), areaHighlightRect(60f, 80f, 10f, 20f), 0f)
        assertNull(areaHighlightRect(10f, 20f, 12f, 80f))
        assertNull(areaHighlightRect(10f, 20f, Float.NaN, 80f))
    }

    @Test fun anArrowKeepsItsDirectionAndPage() {
        val g = inkShapeGeometry(InkShapeMode.Arrow, Offset(10f, 20f), Offset(110f, 70f), 200f, 200f)!!
        val args = shapeAnnotationArgs(InkShapeMode.Arrow, g, 2, "#FF0000", 0.5f, 2f, "me")!!
        assertEquals("arrow", args["type"])
        assertEquals(3, args["page"])
        assertEquals(listOf(10.0, 20.0), args["from"])
        assertEquals(listOf(110.0, 70.0), args["to"])
        assertEquals(0.5, args["opacity"])
    }

    @Test fun aCloudSurroundsTheDraggedRectangleWithinThePage() {
        val g = inkShapeGeometry(InkShapeMode.Cloud, Offset(150f, 90f), Offset(250f, 10f), 200f, 100f)!!
        assertTrue(g is SnappedShape.Rectangle)
        val args = shapeAnnotationArgs(InkShapeMode.Cloud, g, 0, "#000000", 1f, 1f, null)!!
        assertEquals("cloud", args["type"])
        assertEquals(listOf(listOf(150.0, 10.0), listOf(200.0, 10.0), listOf(200.0, 90.0), listOf(150.0, 90.0)), args["points"])
    }

    @Test fun tooSmallOrMismatchedShapesAreNotPlaced() {
        assertNull(inkShapeGeometry(InkShapeMode.Arrow, Offset(1f, 1f), Offset(1.5f, 1.5f), 100f, 100f))
        val line = SnappedShape.Line(Offset(0f, 0f), Offset(10f, 10f))
        assertNull(shapeAnnotationArgs(InkShapeMode.Cloud, line, 0, "#000000", 1f, 1f, null))
        assertNull(shapeAnnotationArgs(InkShapeMode.Rectangle, line, 0, "#000000", 1f, 1f, null))
    }

    @Test fun theArrowPreviewEndsInAnOpenHeadAtTheTip() {
        val pts = arrowPreview(Offset(0f, 0f), Offset(100f, 0f))
        assertEquals(5, pts.size)
        assertEquals(Offset(100f, 0f), pts[1])
        assertEquals(Offset(100f, 0f), pts[3])
        assertTrue(pts[2].x < 100f && pts[4].x < 100f)
        assertTrue(pts[2].y * pts[4].y < 0f)
    }

    @Test fun aCalloutBoxSitsBesideItsPointAndInsideThePage() {
        val up = calloutBox(100f, 300f, 80f, 40f, 600f, 800f)!!
        assertArrayEquals(floatArrayOf(136f, 224f, 216f, 264f), up, 0.01f)
        val flipped = calloutBox(580f, 20f, 80f, 40f, 600f, 800f)!!
        assertTrue(flipped[2] <= 580f && flipped[1] >= 20f)
        assertTrue(flipped.all { it >= 0f } && flipped[2] <= 600f && flipped[3] <= 800f)
        assertNull(calloutBox(10f, 10f, 700f, 40f, 600f, 800f))
        assertNull(calloutBox(Float.NaN, 10f, 70f, 40f, 600f, 800f))
    }

    @Test fun squigglesZigzagAlongTheLine() {
        val pts = squigglePoints(0f, 30f, 10f, 2f)
        assertTrue(pts.size >= 3)
        assertEquals(0f, pts.first().x)
        assertEquals(30f, pts.last().x)
        assertTrue(pts.all { it.y == 10f || it.y == 8f })
        assertTrue(squigglePoints(10f, 5f, 0f, 1f).isEmpty())
    }

    @Test fun stampsUseTheEngineNames() {
        assertEquals(17, STANDARD_STAMPS.size)
        assertEquals(STANDARD_STAMPS.size, STANDARD_STAMPS.toSet().size)
        assertEquals("For Public Release", stampLabel("for public release"))
    }
}
