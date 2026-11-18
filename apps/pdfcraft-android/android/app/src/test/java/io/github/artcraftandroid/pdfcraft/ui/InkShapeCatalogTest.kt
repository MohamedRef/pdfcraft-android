package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.CATALOG_FLAT_SHAPES
import io.github.artcraftandroid.pdfcraft.ui.viewer.CATALOG_INK_STEP
import io.github.artcraftandroid.pdfcraft.ui.viewer.CATALOG_SOLIDS
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkShapeMode
import io.github.artcraftandroid.pdfcraft.ui.viewer.SnappedShape
import io.github.artcraftandroid.pdfcraft.ui.viewer.catalogHiddenDash
import io.github.artcraftandroid.pdfcraft.ui.viewer.catalogShapeCommands
import io.github.artcraftandroid.pdfcraft.ui.viewer.catalogShapeParts
import io.github.artcraftandroid.pdfcraft.ui.viewer.densified
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkShapeGeometry
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkShapeOutline
import io.github.artcraftandroid.pdfcraft.ui.viewer.isAnnotation
import io.github.artcraftandroid.pdfcraft.ui.viewer.isCatalog
import io.github.artcraftandroid.pdfcraft.ui.viewer.isTapped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #693: more shapes from one drag, flat ones as editable annotations, solids with dashed hidden edges. */
class InkShapeCatalogTest {
    private val box = SnappedShape.Rectangle(100f, 200f, 220f, 300f)

    @Test fun everyCatalogShapeFillsItsBoxAndNoMore() {
        for (mode in CATALOG_FLAT_SHAPES + CATALOG_SOLIDS) {
            assertTrue(mode.isCatalog && mode.isAnnotation && !mode.isTapped)
            val parts = catalogShapeParts(mode, 100f, 200f, 220f, 300f)!!
            val pts = parts.flatMap { it.points }
            assertTrue("$mode has points", pts.size >= 2)
            assertTrue("$mode stays in its box", pts.all { it.x in 99.99f..220.01f && it.y in 199.99f..300.01f })
            assertTrue("$mode reaches its box's width", pts.maxOf { it.x } - pts.minOf { it.x } > 100f)
            // a box too small to draw
            assertNull(catalogShapeParts(mode, 100f, 200f, 101f, 201f, Offset(100f, 201f), Offset(101f, 200f)))
            // drawn by itself while dragged, not through the single-path preview
            assertNull(inkShapeOutline(mode, Offset(100f, 200f), Offset(220f, 300f), 600f, 800f))
        }
        assertTrue(InkShapeMode.Line !in CATALOG_FLAT_SHAPES && !InkShapeMode.Polygon.isCatalog)
    }

    @Test fun flatShapesHaveNoHiddenEdgesAndSolidsDo() {
        for (mode in CATALOG_FLAT_SHAPES) assertTrue("$mode", catalogShapeParts(mode, 0f, 0f, 100f, 80f)!!.none { it.hidden })
        for (mode in CATALOG_SOLIDS) {
            val parts = catalogShapeParts(mode, 0f, 0f, 100f, 80f)!!
            assertTrue("$mode has hidden edges", parts.any { it.hidden })
            assertTrue("$mode has visible edges", parts.any { !it.hidden })
        }
        // the cube's three hidden edges meet at its back corner, bottom left
        val cube = catalogShapeParts(InkShapeMode.Cube, 0f, 0f, 100f, 100f)!!
        val corner = Offset(30f, 70f)
        assertTrue(cube.filter { it.hidden }.all { part -> part.points.any { (it - corner).getDistance() < 1e-3f } })
        assertEquals(3, cube.filter { it.hidden }.sumOf { it.points.size - 1 })
    }

    @Test fun flatShapesAreSavedAsEditableAnnotations() {
        val tri = catalogShapeCommands(InkShapeMode.Triangle, box, 0, "#112233", 1f, 2f, "Ada")!!.single()
        assertEquals("polygon", tri["type"])
        assertEquals(1, tri["page"])
        assertEquals(listOf(listOf(160.0, 200.0), listOf(220.0, 300.0), listOf(100.0, 300.0)), tri["points"])
        assertEquals(6, (catalogShapeCommands(InkShapeMode.Hexagon, box, 0, "#000000", 1f, 2f, null)!!.single()["points"] as List<*>).size)
        val axes = catalogShapeCommands(InkShapeMode.Axes, box, 2, "#000000", 1f, 2f, null)!!.single()
        assertEquals("polyline", axes["type"])
        assertEquals(listOf("OpenArrow", "OpenArrow"), axes["endings"])
        assertEquals(listOf(listOf(100.0, 200.0), listOf(100.0, 300.0), listOf(220.0, 300.0)), axes["points"])
        assertEquals(3, axes["page"])
        // the double arrow keeps the direction it was dragged in
        val line = inkShapeGeometry(InkShapeMode.DoubleArrow, Offset(200f, 100f), Offset(50f, 300f), 600f, 800f)!!
        val arrow = catalogShapeCommands(InkShapeMode.DoubleArrow, line, 0, "#000000", 1f, 2f, null)!!.single()
        assertEquals("line", arrow["type"])
        assertEquals(listOf(200.0, 100.0), arrow["from"])
        assertEquals(listOf(50.0, 300.0), arrow["to"])
        assertEquals(listOf("OpenArrow", "OpenArrow"), arrow["endings"])
    }

    @Test fun aSolidIsItsVisibleEdgesAndItsDashedHiddenOnes() {
        val cube = catalogShapeCommands(InkShapeMode.Cube, box, 0, "#112233", 0.8f, 1.5f, "Ada")!!
        assertEquals(2, cube.size)
        val (visible, hidden) = cube
        assertEquals(listOf("ink", "ink"), cube.map { it["type"] })
        assertNull(visible["dash"])
        assertEquals(listOf(4.5, 3.75), hidden["dash"])
        assertEquals(listOf("#112233", "#112233"), cube.map { it["color"] })
        // a closed face goes back to its first corner
        @Suppress("UNCHECKED_CAST")
        val face = (visible["strokes"] as List<List<List<Double>>>).first()
        assertEquals(face.first(), face.last())
        // a fine pen still dashes visibly
        assertEquals(listOf(2.0, 2.0), catalogHiddenDash(0.3f))
    }

    /**
     * #701: a solid's saved drawings hold a point at least every [CATALOG_INK_STEP] page points along
     * each edge, every corner of its parts among them and every point on its parts' own lines, so the
     * smoothing saved ink gets keeps a cube's edges straight and its corners sharp. Before #701 a cube
     * edge was its two corners only, and the saved cube came out with bowed edges and rounded corners.
     */
    @Test fun aSolidsSavedEdgesStayStraight() {
        for (mode in CATALOG_SOLIDS) {
            val parts = catalogShapeParts(mode, box.left, box.top, box.right, box.bottom)!!
            @Suppress("UNCHECKED_CAST")
            val strokes = catalogShapeCommands(mode, box, 0, "#000000", 1f, 1.5f, null)!!
                .flatMap { it["strokes"] as List<List<List<Double>>> }
                .map { s -> s.map { Offset(it[0].toFloat(), it[1].toFloat()) } }
            // the visible parts first, then the hidden ones, each its own stroke
            val lines = parts.sortedBy { it.hidden }.map { if (it.closed) it.points + it.points.first() else it.points }
            assertEquals("$mode", lines.size, strokes.size)
            for ((line, stroke) in lines.zip(strokes)) {
                stroke.zipWithNext().forEach { (a, b) ->
                    assertTrue("$mode: a piece ${(b - a).getDistance()} pt long", (b - a).getDistance() <= CATALOG_INK_STEP + 1e-3f)
                }
                line.forEach { c -> assertTrue("$mode keeps its corner $c", stroke.any { (it - c).getDistance() < 1e-3f }) }
                stroke.forEach { q -> assertTrue("$mode: $q is off its edges", line.zipWithNext().any { (a, b) -> distanceToSegment(q, a, b) < 1e-2f }) }
            }
        }
        // a long edge gets its points, a short one stays as it was
        assertEquals(11, densified(listOf(Offset(0f, 0f), Offset(30f, 0f))).size)
        assertEquals(2, densified(listOf(Offset(0f, 0f), Offset(2f, 0f))).size)
    }

    private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
        val ab = b - a
        val len2 = ab.getDistanceSquared()
        val t = if (len2 == 0f) 0f else (((p - a).x * ab.x + (p - a).y * ab.y) / len2).coerceIn(0f, 1f)
        return (p - (a + ab * t)).getDistance()
    }
}
