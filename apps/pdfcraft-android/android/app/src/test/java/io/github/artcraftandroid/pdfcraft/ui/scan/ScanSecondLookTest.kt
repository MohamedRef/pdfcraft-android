package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.core.ScanFindOptions
import io.github.artcraftandroid.pdfcraft.core.ScanFinder
import io.github.artcraftandroid.pdfcraft.core.ScanSecondLook
import io.github.artcraftandroid.pdfcraft.ui.ScanFinding
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.scanSecondLookId
import io.github.artcraftandroid.pdfcraft.ui.scanTestPageJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The second look (#290): the line detector asked too when the network is not convinced. */
class ScanSecondLookTest {
    private val quad = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    @Test
    fun theSearchAsksForItAsDesigned() {
        assertTrue(ScanLab().secondLook)
        assertEquals(ScanFindOptions(ScanFinder.AUTO, true, true, orderLooks = true), ScanLab().findOptions())
        // off: the network alone, as before
        assertFalse(ScanLab(secondLook = false).findOptions().secondLook)
    }

    @Test
    fun theTestingOptionIsRemembered() {
        val off = ScanLab(secondLook = false)
        assertFalse(ScanLab.decode(off.encode()).secondLook)
        assertFalse(off.asDesigned)
        assertEquals(false, off.diag().toMap()["second_look"])
        assertEquals(false, off.json().getBoolean("second_look"))
        // a phone that saved its options before #290's second look has it on
        assertTrue(ScanLab.decode("keep=1;finder=auto;refine=1").secondLook)
    }

    @Test
    fun theLogSaysWhatItDid() {
        assertEquals("not_asked", scanSecondLookId(ScanSecondLook.NOT_ASKED))
        assertEquals("kept", scanSecondLookId(ScanSecondLook.KEPT))
        assertEquals("taken", scanSecondLookId(ScanSecondLook.TAKEN))
    }

    @Test
    fun theKeptPageSaysItToo() {
        val finding = ScanFinding("lines", 0.5, 0, 7, 4, 0, false, ScanLab().findOptions(), secondLook = "taken", networkSides = 0, linesSides = 4)
        val json = JSONObject(scanTestPageJson("t", true, quad, quad, finding, "auto", 60, false, ScanLab(), null, null)).getJSONObject("finding")
        assertEquals("taken", json.getString("second_look"))
        assertEquals(0, json.getInt("network_sides"))
        assertEquals(4, json.getInt("lines_sides"))
        // a finding made before it says it was not asked
        assertEquals("not_asked", ScanFinding("model", 0.9, 0, 1, 4, 0).secondLook)
    }
}
