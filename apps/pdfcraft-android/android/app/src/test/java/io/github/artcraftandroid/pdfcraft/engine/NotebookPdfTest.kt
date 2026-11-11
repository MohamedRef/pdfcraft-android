package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookPdfTest {
    private fun latin1(b: ByteArray) = String(b, Charsets.ISO_8859_1)

    @Test
    fun everyTemplateIsAWellFormedPdf() {
        for (size in NotebookSize.entries) for (paper in NotebookPaper.entries) for (color in NotebookColor.entries) {
            val bytes = NotebookPdf.write(NotebookTemplate(size, paper, color), pages = 3)
            val s = latin1(bytes)
            assertTrue(s.startsWith("%PDF-1.5"))
            assertTrue(s.trimEnd().endsWith("%%EOF"))
            assertTrue("$size $paper $color", s.contains("/MediaBox [0 0 ${NotebookPdf.n(size.width)} ${NotebookPdf.n(size.height)}]"))
            assertTrue(s.contains("/Count 3"))
            // the cross-reference table points at each object's start
            val xref = s.substringAfterLast("startxref\n").substringBefore('\n').trim().toInt()
            assertTrue(s.startsWith("xref", xref))
            val entries = s.substring(xref).lines().drop(2).takeWhile { it.endsWith(" n ") || it.endsWith(" f ") }
            for ((i, e) in entries.withIndex()) {
                if (i == 0) continue
                val off = e.substring(0, 10).toInt()
                assertTrue("object $i of $size $paper", s.startsWith("$i 0 obj", off))
            }
        }
    }

    /** NOTEBOOK_SAMPLES=dir writes every template there, to look at in other PDF readers. */
    @Test
    fun writeSamplesWhenAsked() {
        val dir = System.getenv("NOTEBOOK_SAMPLES")?.let(::File) ?: return
        dir.mkdirs()
        for (paper in NotebookPaper.entries) for (color in NotebookColor.entries) {
            File(dir, "$paper-$color.pdf").writeBytes(NotebookPdf.write(NotebookTemplate(NotebookSize.A4, paper, color), pages = 2))
        }
    }

    @Test
    fun aPageCostsLessThanTenKilobytes() {
        for (paper in NotebookPaper.entries) {
            val one = NotebookPdf.write(NotebookTemplate(NotebookSize.A4, paper, NotebookColor.Cream), pages = 1).size
            val many = NotebookPdf.write(NotebookTemplate(NotebookSize.A4, paper, NotebookColor.Cream), pages = 101).size
            assertTrue("$paper one page: $one bytes", one < 10_000)
            // pages share the drawing: each extra page is its page dictionary only
            assertTrue("$paper per page: ${(many - one) / 100}", (many - one) / 100 < 200)
        }
    }

    @Test
    fun linesStayInsideThePage() {
        for (size in NotebookSize.entries) for (paper in NotebookPaper.entries) {
            val content = NotebookPdf.content(NotebookTemplate(size, paper))
            val numbers = Regex("""(-?\d+(?:\.\d+)?) (-?\d+(?:\.\d+)?) [ml]\b""").findAll(content)
            for (m in numbers) {
                val (x, y) = m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
                assertTrue("$size $paper x=$x", x >= 0 && x <= size.width + 0.01)
                assertTrue("$size $paper y=$y", y >= 0 && y <= size.height + 0.01)
            }
        }
    }

    @Test
    fun numbersAreWrittenPlainly() {
        assertEquals("1", NotebookPdf.n(1.0))
        assertEquals("0.5", NotebookPdf.n(0.5))
        assertEquals("841.89", NotebookPdf.n(841.889))
        assertEquals("14.17", NotebookPdf.n(14.173))
    }

    @Test
    fun notebookPagesRoundTrip() {
        val dir = Files.createTempDirectory("nb").toFile()
        try {
            val store = Notebooks(File(dir, "notebooks.json"))
            val t = NotebookPage.Builtin(NotebookTemplate(NotebookSize.A5, NotebookPaper.Cornell, NotebookColor.Dark))
            store.put("content://a", t)
            store.put("content://b", NotebookPage.User("Lab sheet.pdf"))
            assertEquals(t, store.get("content://a"))
            assertEquals(NotebookPage.User("Lab sheet.pdf"), store.get("content://b"))
            assertNull(store.get("content://c"))
            store.remove("content://a")
            assertNull(store.get("content://a"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun userTemplateNamesCannotLeaveTheFolder() {
        assertNull(NotebookPage.fromJson(JSONObject().put("user", "../secret.pdf")))
        assertNull(NotebookPage.fromJson(JSONObject().put("user", "a/b.pdf")))
        assertNull(NotebookPage.fromJson(JSONObject().put("user", "notes.txt")))
        assertNull(NotebookPage.fromJson(JSONObject().put("size", "A9").put("paper", "Dots").put("color", "White")))
        val dir = Files.createTempDirectory("tpl").toFile()
        try {
            val templates = NotebookTemplates(dir)
            val a = templates.newFile("Worksheet")
            a.writeText("x")
            val b = templates.newFile("Worksheet")
            assertEquals("Worksheet.pdf", a.name)
            assertEquals("Worksheet 2.pdf", b.name)
            assertEquals(listOf("Worksheet.pdf"), templates.list())
            assertNull(templates.file("../x.pdf"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
