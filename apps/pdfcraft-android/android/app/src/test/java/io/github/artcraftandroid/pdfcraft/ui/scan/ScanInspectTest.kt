package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.core.ScanInspection
import io.github.artcraftandroid.pdfcraft.core.ScanRegions
import io.github.artcraftandroid.pdfcraft.core.ScanTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The research detectors' report (#305, #306) as the log and a kept page file get it. */
class ScanInspectTest {
    private val seen = ScanInspection(
        width = 1654u, height = 2339u, moire = "screen", moirePeriod = 4.4567f,
        moireTiles = 54u, moireBusyTiles = 40u, moirePeriodicTiles = 31u,
        fingers = ScanRegions(1u, 0u, 0.023456f), glare = ScanRegions(0u, 0u, 0f), stains = null,
        flagged = 0u,
        timings = listOf(ScanTiming("scan_inspect_base", 410u), ScanTiming("scan_moire", 612u), ScanTiming("scan_inspect_look", 380u), ScanTiming("scan_defects", 95u)),
    )

    @Test
    fun theLogGetsWordsAndNumbersOnly() {
        val fields = scanInspectDiag(seen).toMap()
        assertEquals("screen", fields["moire"])
        assertEquals(4.46, fields["moire_period"])
        assertEquals(31L, fields["moire_periodic_tiles"])
        assertEquals(1L, fields["fingers"])
        assertEquals(0.0235, fields["fingers_area"])
        // a search that did not run is -1, not 0
        assertEquals(-1L, fields["stains"])
        assertEquals(-1.0, fields["stains_area"])
        assertEquals(612L, fields["moire_ms"])
        assertEquals(95L, fields["defects_ms"])
        for ((key, value) in fields) {
            assertTrue(key, key.all { it in 'a'..'z' || it == '_' })
            assertTrue(key, value is Long || value is Double || (value is String && value.all { it in 'a'..'z' }))
        }
        // no pattern: no period
        assertEquals(-1.0, scanInspectDiag(seen.copy(moire = "none", moirePeriod = null)).toMap()["moire_period"])
    }

    @Test
    fun aKeptPageGetsTheSameAsJson() {
        val json = scanInspectJson(seen)
        assertEquals("screen", json.getJSONObject("moire").getString("kind"))
        assertEquals(4.46, json.getJSONObject("moire").getDouble("period"), 0.0)
        assertEquals(1, json.getJSONObject("fingers").getInt("count"))
        assertEquals(0, json.getJSONObject("fingers").getInt("over_print"))
        assertTrue(json.isNull("stains"))
        assertEquals(0, json.getInt("flagged"))
        assertEquals(410, json.getJSONObject("timings_ms").getInt("scan_inspect_base"))
        assertTrue(scanInspectJson(seen.copy(moirePeriod = Float.NaN)).getJSONObject("moire").isNull("period"))
    }
}
