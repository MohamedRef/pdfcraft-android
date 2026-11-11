package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.InkPenSettings
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkPrefsReader
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.Picker
import io.github.artcraftandroid.pdfcraft.ui.viewer.loadInkPenSettings
import io.github.artcraftandroid.pdfcraft.ui.viewer.penButtonPicker
import org.junit.Assert.assertEquals
import org.junit.Test

class InkPenSettingsTest {
    private class MapReader(val map: MutableMap<String, Any> = mutableMapOf()) : InkPrefsReader {
        override fun contains(key: String) = key in map
        override fun int(key: String, default: Int) = map[key] as? Int ?: default
        override fun float(key: String, default: Float) = map[key] as? Float ?: default
        override fun string(key: String) = map[key] as? String
        fun store(style: InkStyle, s: InkPenSettings) {
            map[InkPenSettings.colorKey(style)] = s.argb
            map[InkPenSettings.widthKey(style)] = s.width
            map[InkPenSettings.opacityKey(style)] = s.opacity
        }
    }

    @Test
    fun freshInstallGivesEveryPenItsOwnDefault() {
        val loaded = loadInkPenSettings(MapReader())
        InkStyle.entries.forEach { assertEquals(InkPenSettings.default(it), loaded.getValue(it)) }
        assertEquals(0xFFFFD100.toInt(), loaded.getValue(InkStyle.Highlighter).argb)
        assertEquals(0xFF000000.toInt(), loaded.getValue(InkStyle.Ballpoint).argb)
    }

    @Test
    fun legacySharedValuesMoveToTheLastUsedPenOnly() {
        val reader = MapReader(mutableMapOf("ink.color" to 0xFF00FF00.toInt(), "ink.width" to 4f, "ink.opacity" to 0.5f, "ink.style" to "pencil"))
        val loaded = loadInkPenSettings(reader)
        assertEquals(InkPenSettings(0xFF00FF00.toInt(), 4f, 0.5f), loaded.getValue(InkStyle.Pencil))
        assertEquals(InkPenSettings.default(InkStyle.Ballpoint), loaded.getValue(InkStyle.Ballpoint))
        assertEquals(InkPenSettings.default(InkStyle.Highlighter), loaded.getValue(InkStyle.Highlighter))
    }

    @Test
    fun legacyUserWhoNeverChangedColourKeepsTheOldRedDefault() {
        val loaded = loadInkPenSettings(MapReader(mutableMapOf("ink.style" to "ballpoint")))
        assertEquals(InkPenSettings.LEGACY_DEFAULT, loaded.getValue(InkStyle.Ballpoint))
    }

    @Test
    fun perPenValuesRoundTripAndSwitchingNeverChangesTheOtherPen() {
        val reader = MapReader(mutableMapOf("ink.color" to 0xFF123456.toInt(), "ink.style" to "ballpoint"))
        val ball = InkPenSettings(0xFF000000.toInt(), 1f, 1f)
        val marker = InkPenSettings(0xFFFFD100.toInt(), 10f, 0.6f)
        reader.store(InkStyle.Ballpoint, ball)
        reader.store(InkStyle.Highlighter, marker)
        // The user last used the marker; that must not pull legacy values into it.
        reader.map["ink.style"] = "highlighter"
        val loaded = loadInkPenSettings(reader)
        assertEquals(ball, loaded.getValue(InkStyle.Ballpoint))
        assertEquals(marker, loaded.getValue(InkStyle.Highlighter))
    }

    @Test
    fun corruptStoredValuesAreClamped() {
        val reader = MapReader()
        reader.store(InkStyle.Brush, InkPenSettings(0xFF000000.toInt(), Float.NaN, 9f))
        val brush = loadInkPenSettings(reader).getValue(InkStyle.Brush)
        assertEquals(InkPenSettings.MIN_WIDTH, brush.width, 0f)
        assertEquals(1f, brush.opacity, 0f)
    }

    @Test
    fun secondTapOnTheSelectedPenOpensItsStyleAndThirdClosesIt() {
        assertEquals(Picker.None, penButtonPicker(penAlreadyActive = false, current = Picker.None))
        assertEquals(Picker.None, penButtonPicker(penAlreadyActive = false, current = Picker.PenStyle))
        assertEquals(Picker.PenStyle, penButtonPicker(penAlreadyActive = true, current = Picker.None))
        assertEquals(Picker.PenStyle, penButtonPicker(penAlreadyActive = true, current = Picker.Presets))
        assertEquals(Picker.None, penButtonPicker(penAlreadyActive = true, current = Picker.PenStyle))
    }
}
