package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.FieldFormat
import io.github.artcraftandroid.pdfcraft.ui.viewer.FieldKind
import io.github.artcraftandroid.pdfcraft.ui.viewer.FieldProps
import io.github.artcraftandroid.pdfcraft.ui.viewer.boxAt
import io.github.artcraftandroid.pdfcraft.ui.viewer.flipRect
import io.github.artcraftandroid.pdfcraft.ui.viewer.grabEdges
import io.github.artcraftandroid.pdfcraft.ui.viewer.inTabOrder
import io.github.artcraftandroid.pdfcraft.ui.viewer.parseOptions
import io.github.artcraftandroid.pdfcraft.ui.viewer.propsArgs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prepare form (#164): placing, moving, properties and tab order, as the form_* tools take them. */
class PrepareFormTest {
    @Test
    fun kindsAreTheEnginesTypes() {
        assertEquals(
            listOf("text", "date", "checkbox", "radio", "combo", "list", "button", "image", "signature"),
            FieldKind.entries.map { it.arg },
        )
    }

    @Test
    fun detectedBoxesFlipToTopLeft() {
        assertArrayEquals(floatArrayOf(110f, 77.6f, 257f, 94.4f), flipRect(floatArrayOf(110f, 697.6f, 257f, 714.4f), 792f), 0.01f)
    }

    @Test
    fun tappedBoxIsCentredAndKeptOnThePage() {
        assertArrayEquals(floatArrayOf(20f, 89f, 180f, 111f), boxAt(100f, 100f, 160f, 22f, 612f, 792f), 0.01f)
        assertArrayEquals(floatArrayOf(0f, 0f, 160f, 22f), boxAt(5f, 5f, 160f, 22f, 612f, 792f), 0.01f)
        assertArrayEquals(floatArrayOf(452f, 770f, 612f, 792f), boxAt(610f, 790f, 160f, 22f, 612f, 792f), 0.01f)
    }

    @Test
    fun grabbingInsideMovesAndNearAnEdgeResizes() {
        val r = floatArrayOf(100f, 100f, 200f, 120f)
        assertTrue(grabEdges(r, 150f, 110f, 4f)!!.all { it })
        assertEquals(listOf(false, false, true, false), grabEdges(r, 201f, 110f, 4f)!!.toList())
        assertEquals(listOf(true, true, false, false), grabEdges(r, 99f, 99f, 4f)!!.toList())
        assertNull(grabEdges(r, 300f, 110f, 4f))
        // a box thinner than the reach: only the nearer edge
        assertEquals(listOf(false, true, false, false), grabEdges(floatArrayOf(0f, 0f, 100f, 6f), 50f, 1f, 4f)!!.toList())
    }

    @Test
    fun onlyChangedPropertiesAreSent() {
        val old = FieldProps("Text1", "text")
        assertEquals(emptyMap<String, Any?>(), propsArgs(old, old))
        val a = propsArgs(old, old.copy(name = " First ", required = true, format = FieldFormat.Date))
        assertEquals("First", a["name"])
        assertEquals(true, a["required"])
        assertEquals(mapOf("type" to "date", "pattern" to "mm/dd/yyyy"), a["format"])
        assertEquals(setOf("name", "required", "format"), a.keys)
        val combo = FieldProps("Dropdown1", "combo", options = "A, B")
        assertEquals(listOf("A", "B", "C"), propsArgs(combo, combo.copy(options = "A,B,, C"))["options"])
        assertEquals(emptyMap<String, Any?>(), propsArgs(combo, combo.copy(options = "A,B", format = FieldFormat.Number)))
    }

    @Test
    fun optionsSplitOnCommasAndLines() {
        assertEquals(listOf("Yes", "No", "Maybe"), parseOptions(" Yes ,No\n\nMaybe,"))
    }

    @Test
    fun tabOrderLeadsAndTheRestKeepTheirPlace() {
        assertEquals(listOf("c", "a", "b", "d"), inTabOrder(listOf("a", "b", "c", "d"), listOf("c", "a")) { it })
        assertEquals(listOf("a", "b"), inTabOrder(listOf("a", "b"), emptyList()) { it })
    }
}
