package io.github.artcraftandroid.pdfcraft.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyDigestTest {
    private fun comment(page: Int, type: String, color: String?, contents: String?, y: Double = 100.0, status: String? = null, marked: Boolean = false, replies: List<Pair<String, String>> = emptyList()) =
        JSONObject().put("page", page).put("type", type).put("color", color ?: JSONObject.NULL).put("contents", contents ?: JSONObject.NULL)
            .put("author", "Sam").put("rect", JSONArray(listOf(10.0, y, 200.0, y + 12)))
            .put("status", status ?: JSONObject.NULL).put("marked", marked)
            .put("replies", JSONArray().apply { replies.forEach { (a, t) -> put(JSONObject().put("author", a).put("contents", t)) } })

    private fun list(vararg c: JSONObject) = JSONObject().put("comments", JSONArray(c.toList()))

    @Test
    fun twentyHighlightsInThreeColoursGroupByColourWithPages() {
        val colors = listOf("#FFEB3B", "#4CAF50", "#2196F3")
        val comments = (0 until 20).map { i -> comment(page = i % 7 + 1, type = "Highlight", color = colors[i % 3], contents = "point $i", y = i * 10.0) }
        val entries = StudyDigest.parse(list(*comments.toTypedArray()))
        assertEquals(20, entries.size)
        val md = StudyDigest.markdown("Notes", "Bio.pdf", entries, DigestGrouping.Color)
        val headings = md.lines().filter { it.startsWith("## ") }
        assertEquals(listOf("## Yellow", "## Green", "## Blue"), headings)
        assertTrue(md.contains("[[Bio.pdf#page=1|p. 1]]"))
        assertEquals(20, md.lines().count { it.startsWith("- ") })
    }

    @Test
    fun groupsByPageInOrderWithMarkedTextRepliesAndChecks() {
        val entries = StudyDigest.parse(list(
            comment(3, "Text", null, "Ask about this", status = "Accepted"),
            comment(1, "Highlight", "#FFEE00", null, marked = true, replies = listOf("Kim" to "Agreed\nfully")),
            comment(1, "Ink", "#000000", null, y = 300.0),
        ))
        entries.first { it.type == "Highlight" }.marked = "Krebs cycle"
        entries.first { it.isInk }.image = "images/page-1-2.png"
        val md = StudyDigest.markdown("Notes", "Bio.pdf", entries, DigestGrouping.Page, DigestLabels(page = "Seite %1\$d"))
        val pages = md.lines().filter { it.startsWith("## ") }
        assertEquals(listOf("## Seite 1", "## Seite 3"), pages)
        assertTrue(md, md.contains("- [x] [[Bio.pdf#page=1|p. 1]]\n  > Krebs cycle"))
        assertTrue(md, md.contains("  - **Kim**: Agreed fully"))
        assertTrue(md, md.contains("![Drawing](images/page-1-2.png)"))
        assertTrue(md, md.contains("Ask about this — [[Bio.pdf#page=3|p. 3]] · Status: Accepted"))
    }

    @Test
    fun filtersCheckedAndUnresolved() {
        val entries = StudyDigest.parse(list(
            comment(1, "Text", null, "a", marked = true),
            comment(1, "Text", null, "b", status = "Completed"),
            comment(1, "Text", null, "c", status = "Rejected"),
            comment(1, "Text", null, "d"),
        ))
        assertEquals(listOf("a"), StudyDigest.filter(entries, DigestFilter.Checked).map { it.note })
        assertEquals(listOf("a", "d"), StudyDigest.filter(entries, DigestFilter.Unresolved).map { it.note })
    }

    @Test
    fun markdownCannotBreakOutOfAListItem() {
        assertEquals("\\# not a heading", StudyDigest.inline("# not a heading"))
        assertEquals("one two", StudyDigest.inline("one\r\ntwo"))
    }

    @Test
    fun csvQuotesAndDefusesFormulas() {
        val entries = StudyDigest.parse(list(comment(2, "Text", "#FF0000", "=SUM(A1), \"x\"\nnext")))
        val csv = StudyDigest.csv(entries)
        val row = csv.split("\r\n")[1]
        assertTrue(row, row.startsWith("2,Text,#FF0000,Sam,,\"'=SUM(A1), \"\"x\"\"\nnext\",,no,"))
        assertFalse(csv.contains(",=SUM"))
    }

    @Test
    fun coloursSnapToTheirNearestName() {
        assertEquals("yellow", StudyDigest.colorKey("#FFF200"))
        assertEquals("green", StudyDigest.colorKey("#66BB6A"))
        assertEquals(null, StudyDigest.colorKey(null))
    }
}
