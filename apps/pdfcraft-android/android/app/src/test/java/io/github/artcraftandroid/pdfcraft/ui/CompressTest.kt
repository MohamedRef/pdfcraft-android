package io.github.artcraftandroid.pdfcraft.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CompressTest {
    @Test fun defaultLevelsAreUnchanged() {
        // the exact arguments the three levels sent before Advanced existed
        val medium = mapOf("downsample" to true, "ppi" to 150, "above_ppi" to 225, "compression" to "jpeg", "quality" to 60)
        assertEquals(
            mapOf("color" to medium, "gray" to medium, "discard_thumbnails" to true, "remove_unreferenced_dests" to true),
            compressArgs(1, CompressOptions()),
        )
        assertEquals(72, (compressArgs(0, CompressOptions())["color"] as Map<*, *>)["ppi"])
        assertEquals(80, (compressArgs(2, CompressOptions())["gray"] as Map<*, *>)["quality"])
        // advanced options are ignored while Advanced is off
        assertEquals(compressArgs(1, CompressOptions()), compressArgs(1, CompressOptions(targetMb = 1.0, discardTags = true)))
    }

    @Test fun advancedOptionsMapToOptimizeArguments() {
        val a = compressArgs(1, CompressOptions(enabled = true, targetMb = 2.0, ppi = 100, quality = 45, discardTags = true, discardFormActions = true))
        assertEquals(mapOf("downsample" to true, "ppi" to 100, "above_ppi" to 150, "compression" to "jpeg", "quality" to 45), a["color"])
        assertEquals(2L * 1024 * 1024, a["target_bytes"])
        assertEquals(true, a["discard_tags"])
        assertEquals(true, a["discard_form_actions"])
        assertEquals(true, a["remove_invalid_links"])
    }

    @Test fun phonePresetLeavesImagesToThePreset() {
        val a = compressArgs(0, CompressOptions(enabled = true, mobile = true))
        assertEquals("mobile", a["preset"])
        assertFalse(a.containsKey("color"))
        assertFalse(a.containsKey("target_bytes"))
    }
}
