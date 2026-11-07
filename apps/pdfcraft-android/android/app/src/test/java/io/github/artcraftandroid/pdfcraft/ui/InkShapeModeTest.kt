package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkShapeMode
import io.github.artcraftandroid.pdfcraft.ui.viewer.SnappedShape
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkShapeGeometry
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkShapeOutline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkShapeModeTest {
    private val start = Offset(80f, 70f)
    private val end = Offset(20f, 30f)

    @Test fun explicitLineRespectsDrawingDirection() {
        val line = inkShapeGeometry(InkShapeMode.Line, start, end, 200f, 150f)
        assertEquals(SnappedShape.Line(start, end), line)
        val points = inkShapeOutline(InkShapeMode.Line, start, end, 200f, 150f)!!
        assertEquals(start, points.first())
        assertEquals(end, points.last())
    }

    @Test fun rectangleSupportsReverseDragAndCloses() {
        assertEquals(SnappedShape.Rectangle(20f, 30f, 80f, 70f),
            inkShapeGeometry(InkShapeMode.Rectangle, start, end, 200f, 150f))
        val points = inkShapeOutline(InkShapeMode.Rectangle, start, end, 200f, 150f)!!
        assertEquals(points.first(), points.last())
        assertTrue(points.zipWithNext().all { (a,b) -> (a-b).getDistance() <= 1.6f })
    }

    @Test fun ovalSupportsReverseDragAndCloses() {
        assertEquals(SnappedShape.Oval(20f, 30f, 80f, 70f),
            inkShapeGeometry(InkShapeMode.Oval, start, end, 200f, 150f))
        val points = inkShapeOutline(InkShapeMode.Oval, start, end, 200f, 150f)!!
        assertEquals(points.first(), points.last())
        assertEquals(49, points.size)
    }

    @Test fun shapesAreClampedToTheirSourcePage() {
        val rect = inkShapeGeometry(InkShapeMode.Rectangle, Offset(-40f, -20f), Offset(500f, 300f), 200f, 150f)
        assertEquals(SnappedShape.Rectangle(0f, 0f, 200f, 150f), rect)
    }

    @Test fun invalidOrTinyShapesAreNotCommitted() {
        for (mode in InkShapeMode.entries) {
            assertNull(inkShapeGeometry(mode, Offset(Float.NaN, 3f), end, 200f, 150f))
        }
        assertNull(inkShapeGeometry(InkShapeMode.Rectangle, start, Offset(80.3f, 70.4f), 200f, 150f))
        assertNull(inkShapeGeometry(InkShapeMode.Line, start, start, 200f, 150f))
        assertNull(inkShapeGeometry(InkShapeMode.Oval, start, end, 0f, 150f))
        assertNull(inkShapeGeometry(InkShapeMode.Freehand, start, end, 200f, 150f))
    }
}
