package io.github.artcraftandroid.pdfcraft.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingTest {
    @Test
    fun blankPageIsAWellFormedPdf() {
        val pdf = String(Handwriting.blankPdf(595.28, 841.89), Charsets.ISO_8859_1)
        assertTrue(pdf.startsWith("%PDF-1.5"))
        assertTrue("/MediaBox [0 0 595.28 841.89]" in pdf)
        // every xref offset points at its object
        val xref = pdf.substring(pdf.indexOf("xref\n"))
        val offsets = Regex("""(\d{10}) 00000 n""").findAll(xref).map { it.groupValues[1].toInt() }.toList()
        assertEquals(3, offsets.size)
        offsets.forEachIndexed { i, o -> assertTrue(pdf.startsWith("${i + 1} 0 obj", o)) }
        assertEquals(pdf.indexOf("xref\n"), Regex("""startxref\n(\d+)""").find(pdf)!!.groupValues[1].toInt())
    }

    @Test
    fun onlyInkWithoutTextIsRead() {
        val list = JSONObject().put("comments", JSONArray(listOf(
            JSONObject().put("type", "Ink").put("index", 1).put("contents", JSONObject.NULL).put("rect", JSONArray(listOf(10, 10, 100, 40))),
            JSONObject().put("type", "Ink").put("index", 2).put("contents", "my note").put("rect", JSONArray(listOf(10, 50, 100, 80))),
            JSONObject().put("type", "Highlight").put("index", 3).put("rect", JSONArray(listOf(10, 90, 100, 100))),
            JSONObject().put("type", "Ink").put("index", 4).put("contents", " ").put("rect", JSONArray(listOf(10, 110, 100, 140))),
        )))
        assertEquals(listOf(1, 4), Handwriting.inksWithoutText(list).map { it.index })
    }

    @Test
    fun wordsGoToTheInkTheyLieInInReadingOrder() {
        val inks = listOf(
            InkBox(1, 0f, 0f, 300f, 100f),
            // a small ink inside the big one: its words are its own
            InkBox(2, 200f, 60f, 290f, 95f),
            InkBox(3, 0f, 200f, 300f, 240f),
        )
        val words = listOf(
            InkWord("world", 80f, 10f, 50f, 20f, 90f),
            InkWord("hello", 10f, 12f, 50f, 20f, 85f),
            InkWord("second", 10f, 50f, 60f, 20f, 80f),
            InkWord("tiny", 210f, 65f, 40f, 20f, 80f),
            InkWord("noise", 10f, 205f, 40f, 20f, 12f),
            InkWord("kept", 60f, 205f, 40f, 20f, null),
            InkWord("nowhere", 500f, 500f, 40f, 20f, 99f),
        )
        val got = Handwriting.assign(inks, words)
        assertEquals("hello world\nsecond", got[1])
        assertEquals("tiny", got[2])
        assertEquals("kept", got[3])
        assertEquals(3, got.size)
    }

    @Test
    fun wordsAreReadFromTheFirstPage() {
        val r = JSONObject().put("pages", JSONArray(listOf(JSONObject().put("page", 1).put("words", JSONArray(listOf(
            JSONObject().put("text", "Ab").put("box", JSONArray(listOf(1, 2, 3, 4))).put("confidence", 77.5),
            JSONObject().put("text", " ").put("box", JSONArray(listOf(1, 2, 3, 4))),
        ))))))
        val w = Handwriting.words(r)
        assertEquals(1, w.size)
        assertEquals(InkWord("Ab", 1f, 2f, 3f, 4f, 77.5f), w[0])
    }
}
