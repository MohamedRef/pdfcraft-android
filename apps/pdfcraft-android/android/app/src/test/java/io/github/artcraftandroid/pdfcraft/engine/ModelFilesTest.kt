package io.github.artcraftandroid.pdfcraft.engine

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #289: a model replaced under the same name by an app update is copied again, not left stale. */
class ModelFilesTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun entry(name: String, bytes: ByteArray) = ModelEntry(name, bytes.size.toLong(), sha(bytes))

    /** An app whose `models` assets are [files]. */
    private class App(val files: Map<String, ByteArray>) {
        var opened = 0
        fun open(name: String) = ByteArrayInputStream(files.getValue(name)).also { opened++ }
    }

    private fun install(dir: File, app: App, force: Boolean = false) =
        ModelFiles.install(dir, app.files.map { (n, b) -> ModelEntry(n, b.size.toLong(), sha(b)) }, app::open, force)

    private fun tempDir(): File = Files.createTempDirectory("models").toFile()

    @Test
    fun aModelReplacedUnderTheSameNameIsCopiedAgain() {
        val dir = tempDir()
        val v1 = App(mapOf("scan-edges.onnx" to ByteArray(100) { 1 }, "text-detection.rten" to ByteArray(50) { 2 }))
        val first = install(dir, v1)
        assertEquals(listOf("scan-edges.onnx" to ModelCopyReason.New, "text-detection.rten" to ModelCopyReason.New), first.copied)

        // the update ships a new model under the same name and the same size
        val newEdges = ByteArray(100) { 9 }
        val v2 = App(mapOf("scan-edges.onnx" to newEdges, "text-detection.rten" to ByteArray(50) { 2 }))
        val second = install(dir, v2)
        assertEquals(listOf("scan-edges.onnx" to ModelCopyReason.Changed), second.copied)
        assertEquals(listOf("text-detection.rten"), second.kept)
        assertArrayEquals(newEdges, File(dir, "scan-edges.onnx").readBytes())
        assertEquals(1, v2.opened)
    }

    @Test
    fun nothingIsCopiedWhenNothingChanged() {
        val dir = tempDir()
        val app = App(mapOf("a.onnx" to ByteArray(10) { 3 }))
        install(dir, app)
        app.opened = 0
        val again = install(dir, app)
        assertTrue(again.copied.isEmpty())
        assertEquals(listOf("a.onnx"), again.kept)
        assertEquals(0, app.opened)
    }

    @Test
    fun aFileFromABuildBeforeTheListIsCopiedOnce() {
        val dir = tempDir()
        // the old code left the file and no record
        File(dir, "a.onnx").writeBytes(ByteArray(10) { 7 })
        val app = App(mapOf("a.onnx" to ByteArray(10) { 3 }))
        assertEquals(listOf("a.onnx" to ModelCopyReason.Unrecorded), install(dir, app).copied)
        assertArrayEquals(ByteArray(10) { 3 }, File(dir, "a.onnx").readBytes())
        assertTrue(install(dir, app).copied.isEmpty())
    }

    @Test
    fun aFileCutShortOnDiskIsCopiedAgain() {
        val dir = tempDir()
        val app = App(mapOf("a.onnx" to ByteArray(10) { 3 }))
        install(dir, app)
        File(dir, "a.onnx").writeBytes(ByteArray(4))
        assertEquals(listOf("a.onnx" to ModelCopyReason.Size), install(dir, app).copied)
        assertEquals(10L, File(dir, "a.onnx").length())
    }

    @Test
    fun forceCopiesEveryFile() {
        val dir = tempDir()
        val app = App(mapOf("a.onnx" to ByteArray(10) { 3 }, "b.rten" to ByteArray(5)))
        install(dir, app)
        assertEquals(setOf(ModelCopyReason.Forced), install(dir, app, force = true).copied.map { it.second }.toSet())
    }

    @Test
    fun aCopyThatDoesNotMatchItsListingIsNotPutInPlace() {
        val dir = tempDir()
        val good = ByteArray(10) { 3 }
        // the listing says one thing, the app's file is another
        val report = ModelFiles.install(dir, listOf(entry("a.onnx", good)), { ByteArrayInputStream(ByteArray(10) { 4 }) })
        assertEquals(listOf("a.onnx"), report.failed)
        assertFalse(File(dir, "a.onnx").exists())
        assertFalse(File(dir, "a.onnx.part").exists())
        // not recorded, so the next run tries again
        assertEquals(listOf("a.onnx" to ModelCopyReason.New), ModelFiles.install(dir, listOf(entry("a.onnx", good)), { ByteArrayInputStream(good) }).copied)
    }

    @Test
    fun theListReadsBackAndSkipsWhatItCannotUse() {
        val entries = listOf(entry("a.onnx", ByteArray(3)), entry("b.rten", ByteArray(4) { 1 }))
        assertEquals(entries, ModelFiles.parseIndex(ModelFiles.formatIndex(entries)))
        assertTrue(ModelFiles.parseIndex("x\t1\tnot-a-hash\n../a\t1\t${"0".repeat(64)}\n\n").isEmpty())
    }

    @Test
    fun withoutAListTheFilesAreHashedFromTheApp() {
        val app = App(mapOf("a.onnx" to ByteArray(10) { 3 }))
        assertEquals(listOf(entry("a.onnx", ByteArray(10) { 3 })), ModelFiles.indexFromFiles(listOf("a.onnx", ModelFiles.INDEX), app::open))
    }

    @Test
    fun theLogLineHoldsCountsAndShortWordsOnly() {
        val line = diagLine(java.time.LocalDateTime.of(2026, 11, 12, 10, 0), DiagArea.Scan, "model_files", ModelInstallReport(listOf("scan-edges.onnx" to ModelCopyReason.Changed), listOf("b"), emptyList(), 12).diag().asList())
        assertTrue(line, line.endsWith("[scan] model_files copied=1 kept=1 failed=0 ms=12 new=0 changed=1 size=0 unrecorded=0 forced=0"))
    }
}
