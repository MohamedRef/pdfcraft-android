package io.github.artcraftandroid.pdfcraft.ui.viewer

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class InkPresetTest {
    @Test fun profilesKeepArgbBitsWidthAndOpacityAcrossPersistence() {
        for (color in listOf(0xFFFFFFFF.toInt(), 0x80123456.toInt(), 0xFF000000.toInt())) {
            val preset = InkPreset.create(color, 1.5f, 0.25f)!!
            assertEquals(preset, InkPreset.decode(preset.encode()))
        }
        assertEquals(3, InkPreset.defaults.size)
        assertEquals(3, InkPreset.defaults.distinct().size)
    }

    @Test fun corruptAndFutureVersionsCannotEnterTheDrawingPreferences() {
        for (value in listOf(null, "", "x".repeat(129), "v2|ff000000|2.0|1.0", "v1|fffffffff|2|1", "v1|-f000000|2|1", "v1|zz000000|2|1", "v1|ff000000|NaN|1", "v1|ff000000|Infinity|1", "v1|ff000000|0|1", "v1|ff000000|11|1", "v1|ff000000|2|0", "v1|ff000000|2|NaN", "v1|ff000000|2|1|extra")) {
            assertNull(value, InkPreset.decode(value))
        }
    }

    @Test fun legacyProfilesStillLoadWithoutChangingAppearance() {
        val legacy = InkPreset.decode("v1|ff0000ff|2.5|0.75")!!
        assertEquals(InkStyle.Ballpoint, legacy.style)
        assertEquals(2.5f, legacy.width, 0.001f)
        assertEquals(0.75f, legacy.opacity, 0.001f)
    }

    @Test fun profileStorageDoesNotDependOnDecimalLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val preset = InkPreset.create(0xFF0000FF.toInt(), 2.5f, 0.75f)!!
            assertEquals("v2|ff0000ff|2.5|0.75|ballpoint", preset.encode())
            assertEquals(preset, InkPreset.decode(preset.encode()))
        } finally { Locale.setDefault(previous) }
    }
}
