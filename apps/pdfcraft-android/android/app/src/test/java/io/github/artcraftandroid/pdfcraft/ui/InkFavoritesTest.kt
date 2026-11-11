package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.InkPreset
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.movedBy
import io.github.artcraftandroid.pdfcraft.ui.viewer.parseHexColor
import io.github.artcraftandroid.pdfcraft.ui.viewer.withRecentColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InkFavoritesTest {
    private fun pen(i: Int) = InkPreset.create(0xFF000000.toInt() or i, 1f + i * 0.1f, 1f, InkStyle.entries[i % InkStyle.entries.size])!!

    @Test
    fun twelveAndMoreFavoritesRoundTripInOrder() {
        val list = (0 until 15).map(::pen)
        assertEquals(list, InkPreset.decodeList(InkPreset.encodeList(list)))
    }

    @Test
    fun listIsBoundedAndSkipsCorruptEntries() {
        val list = (0 until InkPreset.MAX_PRESETS + 5).map { pen(it % 20) }
        assertEquals(InkPreset.MAX_PRESETS, InkPreset.decodeList(InkPreset.encodeList(list))!!.size)
        val encoded = InkPreset.encodeList(listOf(pen(1), pen(2)))
        assertEquals(listOf(pen(1), pen(2)), InkPreset.decodeList(encoded.replace(";", ";garbage;")))
        assertNull(InkPreset.decodeList("v2|ff000000|1.0|1.0|ballpoint"))
        assertEquals(emptyList<InkPreset>(), InkPreset.decodeList(InkPreset.encodeList(emptyList())))
    }

    @Test
    fun finerWidthsAreAccepted() {
        assertEquals(0.3f, InkPreset.create(0, 0.3f, 1f)!!.width, 0f)
        assertEquals(20f, InkPreset.create(0, 20f, 1f)!!.width, 0f)
        assertNull(InkPreset.create(0, 0.05f, 1f))
        assertNull(InkPreset.create(0, 20.5f, 1f))
    }

    @Test
    fun reorderMovesOneItem() {
        val l = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "a", "c", "d"), l.movedBy(1, -1))
        assertEquals(listOf("a", "c", "d", "b"), l.movedBy(1, 5))
        assertEquals(l, l.movedBy(0, -1))
        assertEquals(l, l.movedBy(9, 1))
    }

    @Test
    fun hexInputAcceptsRgbAndArgbOnly() {
        assertEquals(0xFF12AB34.toInt(), parseHexColor("#12ab34"))
        assertEquals(0x8012AB34.toInt(), parseHexColor("#8012AB34"))
        assertEquals(0xFF12AB34.toInt(), parseHexColor(" 12AB34 "))
        assertNull(parseHexColor("#12AB3"))
        assertNull(parseHexColor("#GG0000"))
        assertNull(parseHexColor(""))
    }

    @Test
    fun recentColoursAreMostRecentFirstWithoutDuplicates() {
        var r = emptyList<Int>()
        (1..10).forEach { r = withRecentColor(r, it) }
        assertEquals(listOf(10, 9, 8, 7, 6, 5, 4, 3), r)
        assertEquals(listOf(5, 10, 9, 8, 7, 6, 4, 3), withRecentColor(r, 5))
    }
}

class InkEyedropperTest {
    private val at = androidx.compose.ui.geometry.Offset(50f, 50f)

    @Test
    fun picksTheExactColourOfTheNearestDraftStroke() {
        val near = io.github.artcraftandroid.pdfcraft.ui.viewer.EyedropperStroke(listOf(androidx.compose.ui.geometry.Offset(52f, 50f)), 1f, 0xFF123456.toInt())
        val far = io.github.artcraftandroid.pdfcraft.ui.viewer.EyedropperStroke(listOf(androidx.compose.ui.geometry.Offset(58f, 50f)), 1f, 0xFF654321.toInt())
        assertEquals(0xFF123456.toInt(), io.github.artcraftandroid.pdfcraft.ui.viewer.eyedropperColor(listOf(far, near), emptyList(), at, 10f))
    }

    @Test
    fun savedInkBeatsOtherCommentsAndSmallerRectWins() {
        val big = io.github.artcraftandroid.pdfcraft.ui.viewer.EyedropperTarget(listOf(0f, 0f, 200f, 200f), 0x00FF00, ink = true)
        val small = io.github.artcraftandroid.pdfcraft.ui.viewer.EyedropperTarget(listOf(40f, 40f, 60f, 60f), 0xAA00BB, ink = true)
        val note = io.github.artcraftandroid.pdfcraft.ui.viewer.EyedropperTarget(listOf(45f, 45f, 55f, 55f), 0xFFFF00, ink = false)
        assertEquals(0xFFAA00BB.toInt(), io.github.artcraftandroid.pdfcraft.ui.viewer.eyedropperColor(emptyList(), listOf(big, note, small), at, 2f))
        assertNull(io.github.artcraftandroid.pdfcraft.ui.viewer.eyedropperColor(emptyList(), listOf(small), androidx.compose.ui.geometry.Offset(300f, 300f), 2f))
    }
}
