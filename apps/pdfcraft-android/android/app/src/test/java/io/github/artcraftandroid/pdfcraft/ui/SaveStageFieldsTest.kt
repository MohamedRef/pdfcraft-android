package io.github.artcraftandroid.pdfcraft.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #595: the engine's save stages reach the `doc_save_stages` log line as `<stage>_ms`, in order. */
class SaveStageFieldsTest {
    @Test
    fun eachEngineStageBecomesAMillisecondsFieldInOrder() {
        val out = JSONObject(
            """{"incremental":true,"save_timings":[{"stage":"save_build","duration_ms":3},""" +
                """{"stage":"save_write","duration_ms":250},{"stage":"save_refresh","duration_ms":3100}]}""",
        )
        assertEquals(listOf("build_ms" to 3L, "write_ms" to 250L, "refresh_ms" to 3100L), saveStageFields(out).toList())
    }

    @Test
    fun aSaveWithoutTimingsAddsNoFields() {
        assertTrue(saveStageFields(JSONObject("""{"incremental":true}""")).isEmpty())
    }
}
