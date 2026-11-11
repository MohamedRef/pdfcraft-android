package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.ColorBlindSafeColors
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkPalette
import io.github.artcraftandroid.pdfcraft.ui.viewer.PALETTE_COLOR_BLIND
import io.github.artcraftandroid.pdfcraft.ui.viewer.PALETTE_STANDARD
import io.github.artcraftandroid.pdfcraft.ui.viewer.PaletteImport
import io.github.artcraftandroid.pdfcraft.ui.viewer.PaletteImportError
import io.github.artcraftandroid.pdfcraft.ui.viewer.argbToHsv
import io.github.artcraftandroid.pdfcraft.ui.viewer.decodePalettes
import io.github.artcraftandroid.pdfcraft.ui.viewer.encodePalettes
import io.github.artcraftandroid.pdfcraft.ui.viewer.hsvToArgb
import io.github.artcraftandroid.pdfcraft.ui.viewer.paletteFileName
import io.github.artcraftandroid.pdfcraft.ui.viewer.parseInkPalette
import io.github.artcraftandroid.pdfcraft.ui.viewer.toJson
import io.github.artcraftandroid.pdfcraft.ui.viewer.uniquePaletteName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkPaletteTest {
    private val biology = InkPalette(
        "Biology",
        listOf(InkPalette.PaletteColor(0xFF0072B2.toInt(), "Cells"), InkPalette.PaletteColor(0x80E69F00.toInt())),
    )

    private fun reason(text: String) = (parseInkPalette(text).exceptionOrNull() as PaletteImport).reason

    @Test
    fun exportedPaletteImportsUnchanged() {
        assertEquals(biology, parseInkPalette(biology.toJson().toString()).getOrThrow())
        assertEquals(listOf(biology, biology.copy(name = "Maths")), decodePalettes(encodePalettes(listOf(biology, biology.copy(name = "Maths")))))
    }

    @Test
    fun importRejectsOtherFilesAndNewerFormats() {
        assertEquals(PaletteImportError.NotAPalette, reason("not json"))
        assertEquals(PaletteImportError.NotAPalette, reason("""{"name":"x","colors":[{"color":"#000000"}]}"""))
        assertEquals(PaletteImportError.NewerVersion, reason("""{"format":"pdfcraft-ink-palette","version":2,"colors":[{"color":"#000000"}]}"""))
        assertEquals(PaletteImportError.NoColors, reason("""{"format":"pdfcraft-ink-palette","version":1,"colors":[{"color":"blue"}]}"""))
    }

    @Test
    fun importCleansNamesSkipsBadColoursAndCapsTheList() {
        val many = (0 until 100).joinToString(",") { """{"color":"#%06X"}""".format(it) }
        val json = """{"format":"pdfcraft-ink-palette","version":1,"name":"  A\u0007${"b".repeat(100)}  ",
            "colors":[{"color":"#zz0000"},{"color":"#FF0000","name":"Red\n"},{"color":"#ff0000"},$many]}"""
        val p = parseInkPalette(json).getOrThrow()
        assertEquals("A" + "b".repeat(InkPalette.MAX_NAME - 1), p.name)
        assertEquals(InkPalette.PaletteColor(0xFFFF0000.toInt(), "Red"), p.colors.first())
        assertEquals(InkPalette.MAX_COLORS, p.colors.size)
        assertEquals(p.colors.size, p.colors.map { it.argb }.toSet().size)
    }

    @Test
    fun damagedSavedPalettesAreDroppedNotAll() {
        val good = biology.toJson().toString()
        assertEquals(listOf(biology), decodePalettes("[$good, {\"format\":\"x\"}, 5]"))
        assertEquals(emptyList<InkPalette>(), decodePalettes("garbage"))
        assertEquals(emptyList<InkPalette>(), decodePalettes(null))
    }

    @Test
    fun namesStayUniqueAndNeverMatchTheBuiltInKeys() {
        assertEquals("Biology 2", uniquePaletteName("Biology", listOf("Biology")))
        assertEquals("Biology 3", uniquePaletteName("Biology", listOf("Biology", "Biology 2")))
        // the built-in keys hold a control character, which names never keep
        assertEquals("standard", uniquePaletteName(PALETTE_STANDARD, listOf(PALETTE_STANDARD, PALETTE_COLOR_BLIND)))
        assertEquals("Palette", uniquePaletteName("  ", emptyList()))
    }

    @Test
    fun fileNamesAreSafe() {
        assertEquals("Bio_Chem 1.json", paletteFileName("Bio/Chem 1"))
        assertEquals("palette.json", paletteFileName(""))
    }

    @Test
    fun hsvMatchesKnownColoursAndRoundTrips() {
        assertEquals(0xFFFF0000.toInt(), hsvToArgb(0f, 1f, 1f))
        assertEquals(0xFF00FF00.toInt(), hsvToArgb(120f, 1f, 1f))
        assertEquals(0xFF0000FF.toInt(), hsvToArgb(240f, 1f, 1f))
        assertEquals(0xFFFFFFFF.toInt(), hsvToArgb(77f, 0f, 1f))
        assertEquals(0xFF000000.toInt(), hsvToArgb(200f, 1f, 0f))
        assertEquals(0xFFFF0000.toInt(), hsvToArgb(360f, 1f, 1f))
        // every colour of the shipped palette survives HSV and back exactly
        for (argb in ColorBlindSafeColors) {
            val (h, s, v) = argbToHsv(argb).let { Triple(it[0], it[1], it[2]) }
            assertEquals("%08X".format(argb), argb, hsvToArgb(h, s, v))
        }
        // and so does a coarse sweep of the RGB cube
        for (r in 0..255 step 15) for (g in 0..255 step 15) for (b in 0..255 step 15) {
            val argb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            val hsv = argbToHsv(argb)
            assertTrue(hsv[0] in 0f..360f)
            assertEquals("%08X".format(argb), argb, hsvToArgb(hsv[0], hsv[1], hsv[2]))
        }
    }
}
