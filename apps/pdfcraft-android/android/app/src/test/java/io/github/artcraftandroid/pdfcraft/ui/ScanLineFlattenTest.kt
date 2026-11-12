package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.core.ScanFlattenCheck
import io.github.artcraftandroid.pdfcraft.core.ScanLineFlatten
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Flattening a curled page from its lines of text (#303, part B): the testing option, the request, the log and the page file. */
class ScanLineFlattenTest {
    private val corners = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
    private val fitted = ScanLineFlatten(null, 14u, 402u, 0.312f, -0.118f, 6.04f, 0.71f, 30u, ScanFlattenCheck(true, "straighter", 4.29f, 0.91f, 17u, 17u))

    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        val lab = ScanLab()
        assertTrue(lab.lineFlatten)
        assertFalse(lab.copy(lineFlatten = false).asDesigned)
        assertEquals(ScanLab(lineFlatten = false), ScanLab.decode(ScanLab(lineFlatten = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto").lineFlatten)
        assertEquals(false, ScanLab(lineFlatten = false).diag().toMap()["line_flatten"])
        assertEquals(false, ScanLab(lineFlatten = false).json().getBoolean("line_flatten"))
    }

    @Test
    fun theRequestAsksForTheFallbackOnlyWithTheOptionOn() {
        assertTrue(scanPageRequest("/a", corners, ScanFilter.CleanScan, true, ScanLab(), "").lineFlatten)
        assertFalse(scanPageRequest("/a", corners, ScanFilter.CleanScan, true, ScanLab(lineFlatten = false), "").lineFlatten)
        // a preview made with the option is not the one made without it
        val page = ScanPreviewKey("/a", corners.toList(), ScanFilter.CleanScan, true, ScanLab())
        assertFalse(page == page.copy(lab = ScanLab(lineFlatten = false)))
    }

    @Test
    fun theLogSaysWhatTheFallbackDidInNumbersAndShortWords() {
        val ran = scanLineFlattenDiag(true, fitted).toMap()
        assertEquals(true, ran["line_ran"])
        assertEquals("none", ran["line_gave_up"])
        assertEquals(true, ran["line_kept"])
        assertEquals("straighter", ran["line_why"])
        assertEquals(14L, ran["line_lines"])
        assertEquals(0.312, ran["line_alpha"] as Double, 1e-9)
        assertEquals(0.91, ran["line_flat_px"] as Double, 1e-9)
        val gaveUp = scanLineFlattenDiag(true, ScanLineFlatten("few_lines", 0u, 0u, 0f, 0f, 0f, 0f, 0u, null)).toMap()
        assertEquals("few_lines", gaveUp["line_gave_up"])
        assertEquals(false, gaveUp["line_kept"])
        assertEquals(-1.0, gaveUp["line_px"])
        val notRun = scanLineFlattenDiag(false, null).toMap()
        assertEquals(false, notRun["line_flatten"])
        assertEquals(false, notRun["line_ran"])
        // only booleans, numbers and short words
        assertTrue(ran.values.all { it is Boolean || it is Number || (it is String && it.length < 16) })
    }

    @Test
    fun aKeptPageSaysWhatTheFallbackDid() {
        val result = ScanTestResult(1000, 1400, "jpeg", 200.0, "a4", "known_focal", false, true, 0.0, lineFlatten = fitted)
        val json = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 60, true, ScanLab(), "page-01.out.jpg", result))
        val l = json.getJSONObject("result").getJSONObject("line_flatten")
        assertTrue(l.isNull("gave_up"))
        assertEquals(402, l.getInt("points"))
        assertEquals(-0.118, l.getDouble("beta"), 1e-9)
        assertEquals(0.71, l.getDouble("rms_after_px"), 1e-9)
        assertTrue(l.getJSONObject("check").getBoolean("kept"))
        assertTrue(json.getJSONObject("options").getBoolean("line_flatten"))
        val plain = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 60, true, ScanLab(), "page-01.out.jpg", result.copy(lineFlatten = null)))
        assertTrue(plain.getJSONObject("result").isNull("line_flatten"))
    }
}
