package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.ScanModelInfo
import io.github.artcraftandroid.pdfcraft.core.ScanModelState
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.scanTestPageJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #289: the Models list and a kept scan's page file say which models the engine accepted. */
class ScanModelsTest {
    private fun info(id: String, state: ScanModelState, url: String = "") =
        ScanModelInfo(id, "scan-$id.onnx", 100uL, "MIT", url.isEmpty(), "a".repeat(64), url, state)

    @Test
    fun thePageFileSaysWhichModelsWereAccepted() {
        val models = scanModelsJson(listOf(info("edges", ScanModelState.READY), info("flatten", ScanModelState.CORRUPT), info("upright", ScanModelState.MISSING)))
        val corners = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val page = JSONObject(scanTestPageJson("1.0 (1)", true, corners, null, null, "auto", null, false, ScanLab(), null, null, models = models))
        val got = page.getJSONObject("models")
        assertEquals("ready", got.getString("edges"))
        assertEquals("corrupt", got.getString("flatten"))
        assertEquals("missing", got.getString("upright"))
        // unknown when the states could not be read
        assertNull(scanModelsJson(null))
        assertTrue(JSONObject(scanTestPageJson("1.0 (1)", true, corners, null, null, "auto", null, false, ScanLab(), null, null)).isNull("models"))
    }

    @Test
    fun everyModelAndStateHasText() {
        assertEquals(R.string.scan_model_edges, scanModelName("edges"))
        assertEquals(R.string.scan_model_flatten, scanModelName("flatten"))
        assertEquals(R.string.scan_model_upright, scanModelName("upright"))
        assertNull(scanModelName("later"))
        assertEquals(setOf(R.string.scan_model_ready, R.string.scan_model_missing, R.string.scan_model_corrupt), ScanModelState.entries.map { scanModelStateText(it) }.toSet())
        // an optional model that is not there yet is "Not downloaded", not "Not installed"
        assertEquals(R.string.scan_model_not_downloaded, scanModelStateText(ScanModelState.MISSING, bundled = false))
    }

    @Test
    fun onlyAnOptionalModelCanBeDownloaded() {
        assertNull(scanModelPack(info("edges", ScanModelState.READY)))
        val url = io.github.artcraftandroid.pdfcraft.engine.ModelDownload.URL_PREFIX + "1/scan-flatten.onnx"
        val pack = scanModelPack(info("flatten", ScanModelState.MISSING, url))!!
        assertEquals("scan-flatten.onnx", pack.file)
        assertEquals(100L, pack.bytes)
        assertTrue(io.github.artcraftandroid.pdfcraft.engine.ModelDownload.trusted(pack))
    }
}
