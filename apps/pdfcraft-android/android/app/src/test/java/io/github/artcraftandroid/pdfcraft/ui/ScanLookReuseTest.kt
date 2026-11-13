package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.core.ScanTiming
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Clean scan keeps the page's light estimate between changes (#446): the testing option, the request, the log and the page file. */
class ScanLookReuseTest {
    private val corners = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        val lab = ScanLab()
        assertTrue(lab.lookReuse)
        assertFalse(lab.copy(lookReuse = false).asDesigned)
        assertEquals(ScanLab(lookReuse = false), ScanLab.decode(ScanLab(lookReuse = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto").lookReuse)
        assertEquals(false, ScanLab(lookReuse = false).diag().toMap()["look_reuse"])
        assertEquals(false, ScanLab(lookReuse = false).json().getBoolean("look_reuse"))
    }

    @Test
    fun theRequestKeepsTheLightOnlyWithTheOptionOn() {
        assertTrue(scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(), "").reuseLook)
        assertFalse(scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(lookReuse = false), "").reuseLook)
    }

    @Test
    fun theLooksPartsComeFromThePreviewsTimings() {
        val timings = listOf(
            ScanTiming("scan_preview_look", 24u),
            ScanTiming("scan_preview_look_tone", 21u),
        )
        assertEquals(mapOf("look_tone" to 21L), scanLookParts(timings))
        // a page straightened just now: its own stages are not the look's parts
        val first = listOf(
            ScanTiming("scan_preview_base", 180u), ScanTiming("scan_decode", 90u), ScanTiming("scan_preview_look", 230u),
            ScanTiming("scan_preview_look_light", 170u), ScanTiming("scan_preview_look_noise", 5u), ScanTiming("scan_preview_look_tone", 20u),
        )
        assertEquals(mapOf("look_light" to 170L, "look_noise" to 5L, "look_tone" to 20L), scanLookParts(first))
        assertTrue(scanLookParts(listOf(ScanTiming("scan_preview_look", 40u))).isEmpty())
    }

    @Test
    fun aKeptPageSaysWhetherTheLightWasKeptAndWhatTheLookTook() {
        val result = ScanTestResult(1000, 1400, "jpeg", 200.0, "a4", "known_focal", false, false, 0.0)
        val preview = ScanTestPreview(true, true, 31, 905, 1280, "auto", lightReused = true, lookParts = mapOf("look_tone" to 21L))
        val json = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 60, true, ScanLab(), "page-01.out.jpg", result, preview))
        val shown = json.getJSONObject("preview")
        assertTrue(shown.getBoolean("light_reused"))
        assertEquals(21, shown.getJSONObject("look_parts_ms").getInt("look_tone"))
        assertTrue(json.getJSONObject("options").getBoolean("look_reuse"))
        // a page saved without a preview: nothing kept, no parts
        val none = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 60, true, ScanLab(), "page-01.out.jpg", result, ScanTestPreview(false, false, 0, 0, 0, null)))
        assertFalse(none.getJSONObject("preview").getBoolean("light_reused"))
        assertEquals(0, none.getJSONObject("preview").getJSONObject("look_parts_ms").length())
    }
}
