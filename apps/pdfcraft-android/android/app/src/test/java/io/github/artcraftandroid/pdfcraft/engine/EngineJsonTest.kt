package io.github.artcraftandroid.pdfcraft.engine

import io.github.artcraftandroid.pdfcraft.core.MobileException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** What every tool call sends: Kotlin values as the engine's JSON, and failure messages (#125). */
class EngineJsonTest {
    @Test fun unsignedAndFloatValuesBecomeJsonNumbers() {
        val o = Engine.toJson(mapOf("page" to 3u, "big" to 5uL, "f" to 1.5f, "skip" to null)) as JSONObject
        assertEquals(3L, o.get("page"))
        assertEquals(5L, o.get("big"))
        assertEquals(1.5, o.get("f"))
        assertFalse(o.has("skip"))
    }

    @Test fun arraysAndListsNest() {
        val o = Engine.toJson(mapOf("rect" to floatArrayOf(1f, 2f), "ints" to intArrayOf(4), "list" to listOf(mapOf("a" to 1u)))) as JSONObject
        assertEquals("[1,2]", (o.get("rect") as JSONArray).toString().replace(".0", ""))
        assertEquals("[4]", o.get("ints").toString())
        assertEquals("""[{"a":1}]""", o.get("list").toString())
        assertEquals(JSONObject.NULL, Engine.toJson(null))
    }

    @Test fun aRefusalIsToldInTheEnginesWords() {
        assertEquals("page 9 is out of range", MobileException.Command("page 9 is out of range").userMessage)
        assertEquals("disk full", java.io.IOException("disk full").userMessage)
    }
}
