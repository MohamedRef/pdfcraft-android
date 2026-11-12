package io.github.artcraftandroid.pdfcraft.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page's strength against the document type's (#424): the testing option, the log and the page file. */
class ScanPageStrengthTest {
    private val corners = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        assertTrue(ScanLab().pageStrength)
        assertFalse(ScanLab(pageStrength = false).asDesigned)
        assertEquals(ScanLab(pageStrength = false), ScanLab.decode(ScanLab(pageStrength = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto").pageStrength)
        assertEquals(false, ScanLab(pageStrength = false).diag().toMap()["page_strength"])
        assertEquals(false, ScanLab(pageStrength = false).json().getBoolean("page_strength"))
        // a preview made with the option is not the one made without it
        val key = ScanPreviewKey("/a", corners.toList(), ScanFilter.CleanScan, false, ScanLab())
        assertFalse(key == key.copy(lab = ScanLab(pageStrength = false)))
    }

    @Test
    fun theLogSaysWhatStrengthThePageWasMadeAt() {
        val clean = io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageLook(ScanFilter.CleanScan, 20)
        val fields = scanAppliedStrengthDiag(scanAppliedStrength(clean, ScanLab(), 80), ScanLab()).toMap()
        assertEquals(20, fields["strength_applied"])
        assertEquals("page", fields["strength_applied_source"])
        assertEquals(true, fields["strength_fixed"])
        assertEquals(true, fields["page_strength"])
        assertEquals(30, fields["sharpen_asked"])
        val before = scanAppliedStrengthDiag(scanAppliedStrength(clean, ScanLab(pageStrength = false), 80), ScanLab(pageStrength = false)).toMap()
        assertEquals(80, before["strength_applied"])
        assertEquals("kind", before["strength_applied_source"])
        // only booleans, numbers and short words
        assertTrue(fields.values.all { it is Boolean || it is Number || (it is String && it.length < 16) })
    }

    @Test
    fun aKeptPageSaysWhatStrengthItWasMadeAt() {
        val json = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 20, false, ScanLab(), "page-01.out.jpg", null, applied = ScanAppliedStrength(20, "page", true)))
        val a = json.getJSONObject("strength_applied")
        assertEquals(20, a.getInt("strength"))
        assertEquals("page", a.getString("source"))
        assertTrue(a.getBoolean("fixed"))
        assertTrue(json.getJSONObject("options").getBoolean("page_strength"))
        val plain = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, null, "auto", 20, false, ScanLab(), null, null))
        assertTrue(plain.isNull("strength_applied"))
    }
}
