package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.core.ScanFindOptions
import io.github.artcraftandroid.pdfcraft.core.ScanFinder
import io.github.artcraftandroid.pdfcraft.core.ScanLooksBy
import io.github.artcraftandroid.pdfcraft.ui.ScanFinding
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.scanLooksById
import io.github.artcraftandroid.pdfcraft.ui.scanTestPageJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page search's turned looks ordered by how the page lies (#785): the option, the request, the log's words and the page file. */
class ScanOrderLooksTest {
    private val quad = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    @Test
    fun theTestingOptionIsOnByDefaultAndReachesTheSearch() {
        assertTrue(ScanLab().orderLooks)
        assertTrue(ScanLab().findOptions().orderLooks)
        assertFalse(ScanLab(orderLooks = false).findOptions().orderLooks)
        // the search asked without the option keeps the fixed order, as before
        assertFalse(ScanFindOptions(ScanFinder.AUTO, true).orderLooks)
        assertFalse(ScanLab(orderLooks = false).asDesigned)
    }

    @Test
    fun theOptionSurvivesBeingSavedAndIsInTheLogAndThePageFile() {
        val off = ScanLab(orderLooks = false)
        assertEquals(off, ScanLab.decode(off.encode()))
        assertEquals(false, off.diag().toMap()["order_looks"])
        assertEquals(false, off.json().getBoolean("order_looks"))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto;refine=1;sheet_check=1").orderLooks)
    }

    @Test
    fun whatTheOrderWentByHasAWordOfItsOwn() {
        assertEquals("fixed", scanLooksById(ScanLooksBy.FIXED))
        assertEquals("outline", scanLooksById(ScanLooksBy.OUTLINE))
        assertEquals("first_look", scanLooksById(ScanLooksBy.FIRST_LOOK))
        assertEquals(ScanLooksBy.entries.size, ScanLooksBy.entries.map { scanLooksById(it) }.toSet().size)
    }

    @Test
    fun theKeptPageSaysItToo() {
        val finding = ScanFinding("model", 0.85, 30, 2, 4, 0, false, ScanLab().findOptions(), lookOrder = "outline")
        val json = JSONObject(scanTestPageJson("t", true, quad, quad, finding, "auto", 60, false, ScanLab(), null, null)).getJSONObject("finding")
        assertEquals("outline", json.getString("look_order"))
        // a finding made before the option says the fixed order
        val before = ScanFinding("model", 0.9, 0, 1, 4, 0)
        assertEquals("fixed", JSONObject(scanTestPageJson("t", true, quad, quad, before, "auto", 60, false, ScanLab(), null, null)).getJSONObject("finding").getString("look_order"))
    }
}
