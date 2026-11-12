package io.github.artcraftandroid.pdfcraft.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDateTime

class DiagnosticsTest {
    private val at = LocalDateTime.of(2026, 11, 11, 21, 3, 12, 481_000_000)

    @After
    fun off() {
        Diag.sink = null
    }

    @Test
    fun lineHasTimeTagNameAndFields() {
        val line = diagLine(at, DiagArea.Save, "write_back", listOf("doc" to 2, "bytes" to 1024L, "ok" to true, "ratio" to 1.41421, "trigger" to "pause"))
        assertEquals("11-11 21:03:12.481 [save] write_back doc=2 bytes=1024 ok=true ratio=1.41 trigger=pause", line)
    }

    @Test
    fun pageToolLinesKeepTheirFixedWords() {
        // the page tools' values: tool names, mark kinds, print layouts and PDF/A levels as the app writes them
        val line = diagLine(at, DiagArea.Pages, "page_edit", listOf("tool" to "page_rotate", "kind" to io.github.artcraftandroid.pdfcraft.ui.MarkKind.HeaderFooter,
            "layout" to io.github.artcraftandroid.pdfcraft.ui.PrintLayout.Booklet, "level" to "pdfa2b"))
        assertEquals("11-11 21:03:12.481 [pages] page_edit tool=page_rotate kind=headerfooter layout=booklet level=pdfa2b", line)
    }

    @Test
    fun anythingThatCouldBeUserTextIsRedacted() {
        // a file name, a path, a URI, a sentence, a password-like string
        for (v in listOf("Thesis Final.pdf", "/storage/emulated/0/Download/a.pdf", "content://com.android.providers/document/1", "Hello world", "Secret123")) {
            assertEquals(Diag.REDACTED, diagValue(v))
        }
        assertEquals("content", diagValue("content"))
        assertEquals("autocolor", diagValue(TestLook.AutoColor))
        assertEquals("-", diagValue(null))
        assertEquals("-", diagValue(Double.NaN))
        assertEquals(Diag.REDACTED, diagValue(listOf(1, 2)))
        // a key or event name built from user text is replaced too
        val line = diagLine(at, DiagArea.Open, "Opened Report.pdf", listOf("My File.pdf" to 1))
        assertFalse("Report" in line)
        assertFalse("My File" in line)
    }

    @Test
    fun nothingIsRecordedWhileOff() {
        Diag.sink = null
        Diag.event(DiagArea.App, "x")
        assertEquals(42, Diag.measure(DiagArea.App, "y") { 42 })
        assertFalse(Diag.enabled())
    }

    @Test
    fun sinkKeepsLinesAcrossInstancesAndRotates() {
        val dir = Files.createTempDirectory("diag").toFile()
        try {
            val sink = DiagSink(dir)
            Diag.sink = sink
            Diag.now = { at }
            Diag.event(DiagArea.Scan, "photo", "edges_found" to true)
            val result = Diag.measure(DiagArea.Scan, "ocr", "pages" to 3) { "done" }
            assertEquals("done", result)
            runCatching { Diag.measure(DiagArea.Save, "write_back") { error("disk full at /secret/path") } }
            Diag.failure(DiagArea.Save, "write_back", IllegalStateException("/secret/path/file.pdf"))
            // a new process reads what the old one wrote
            sink.flush()
            val lines = DiagSink(dir).lines()
            assertEquals("11-11 21:03:12.481 [scan] photo edges_found=true", lines.first { "photo" in it })
            assertTrue(lines.any { "[scan] ocr ms=" in it && it.endsWith("pages=3 ok=true") })
            assertTrue(lines.any { "[save] write_back ms=" in it && it.endsWith("ok=false") })
            assertTrue(lines.any { it.endsWith("[error] failed area=save stage=write_back error_class=IllegalStateException") })
            assertFalse(lines.any { "secret" in it })
            assertEquals(4, lines.size)
            sink.clear()
            assertEquals(emptyList<String>(), sink.lines())
        } finally {
            Diag.now = { LocalDateTime.now() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun rotationKeepsAtMostTwoFiles() {
        val dir = Files.createTempDirectory("diag").toFile()
        try {
            val sink = DiagSink(dir, maxBytes = 100)
            repeat(50) { sink.add("line $it ".padEnd(40, 'x')) }
            val lines = sink.lines()
            assertTrue(lines.size < 50)
            assertEquals("line 49 ".padEnd(40, 'x'), lines.last())
            assertEquals(setOf("diagnostics.log", "diagnostics.1.log"), dir.list()!!.toSet())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun crashLineHasClassesAndCodeLocationsButNoMessage() {
        val error = RuntimeException("cannot open /storage/emulated/0/Private Notes.pdf", IllegalArgumentException("Private Notes"))
        val line = crashLine(at, mainThread = true, error = error)
        assertTrue(line.startsWith("11-11 21:03:12.481 [error] crash main_thread=true error_class=RuntimeException>IllegalArgumentException frames="))
        assertTrue(line, "DiagnosticsTest.crashLineHasClassesAndCodeLocationsButNoMessage:" in line)
        assertFalse("Private" in line)
        assertFalse("storage" in line)
    }

    @Test
    fun reportSummarisesAreasWithTheirIssues() {
        val lines = listOf(
            "11-11 21:03:12.481 [open] open_start doc=1",
            "11-11 21:03:13.000 [open] native_open duration_ms=40",
            "11-11 21:03:20.000 [ink] stroke ms=640",
        )
        val text = diagReport(listOf("app" to "0.0.24-beta (24012)"), lines)
        assertEquals("PdfCraft diagnostics log", text.lines()[0])
        assertTrue("app: 0.0.24-beta (24012)" in text.lines())
        assertTrue("  [open] 2 · #211 #212 #234 #235 #317" in text.lines())
        assertTrue("  [ink] 1 · #74 #75 #76 #81 #84 #85 #128 #130 #144 #148 #149 #150 #156" in text.lines())
        assertTrue(text.lines().indexOf(lines[0]) < text.lines().indexOf(lines[2]))
    }

    private enum class TestLook { AutoColor }

    @Test
    fun viewerLinesKeepTheirEnumValues() {
        // the viewer's log values are enums and numbers: none of them may come out redacted (#152 #153 #155 #157)
        val line = diagLine(
            LocalDateTime.of(2026, 11, 11, 21, 0), DiagArea.Viewer, "layout_open",
            listOf(
                "mode" to io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode.Horizontal,
                "spread" to io.github.artcraftandroid.pdfcraft.ui.viewer.Spread.TwoUpCover,
                "action" to io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.ZoomIn,
                "layout" to io.github.artcraftandroid.pdfcraft.ui.OpenLayout.TwoUpContinuousCover,
                "colors" to io.github.artcraftandroid.pdfcraft.ui.viewer.ReadingColors.YellowOnBlack,
                "turn" to io.github.artcraftandroid.pdfcraft.ui.viewer.PresentGesture.TapNext,
                "source" to "remembered",
            ),
        )
        assertEquals(
            "11-11 21:00:00.000 [viewer] layout_open mode=horizontal spread=twoupcover action=zoomin layout=twoupcontinuouscover colors=yellowonblack turn=tapnext source=remembered",
            line,
        )
        assertFalse(line.contains(Diag.REDACTED))
    }
}
