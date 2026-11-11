package io.github.artcraftandroid.pdfcraft.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupNamesTest {
    private val day = 24 * 60 * 60 * 1000L
    private val t0 = 1_700_000_000_000L

    @Test
    fun baseDropsTheExtensionAndCharactersFoldersRefuse() {
        assertEquals("Lecture 3", BackupNames.base("Lecture 3.PDF"))
        assertEquals("a_b_c_d", BackupNames.base("a/b:c*d.pdf"))
        assertEquals("Document", BackupNames.base(".pdf"))
        assertEquals("hidden", BackupNames.base("..hidden.pdf"))
        assertEquals(80, BackupNames.base("x".repeat(200)).length)
        // a document can't land in the app data folder
        assertEquals(BackupNames.APP_DATA_FOLDER + "_", BackupNames.base(BackupNames.APP_DATA_FOLDER + ".pdf"))
    }

    @Test
    fun namesCarryTheirTimeToTheSecond() {
        val t = t0 - t0 % 1000
        val name = BackupNames.pdfName("Notes", t)
        assertEquals(t, BackupNames.timeOf("Notes", name))
        assertEquals(t, BackupNames.timeOf("Notes", BackupNames.xfdfName("Notes", t)))
        // another document's file, or a file that isn't a backup, is not one of these
        assertNull(BackupNames.timeOf("Note", name))
        assertNull(BackupNames.timeOf("Notes", "Notes.pdf"))
        assertNull(BackupNames.timeOf("Notes", "Notes 2026-13-99 xx.pdf"))
    }

    @Test
    fun versionsPairEachPdfWithItsCommentsNewestFirst() {
        val files = mapOf(
            BackupNames.pdfName("A", t0) to 100L,
            BackupNames.xfdfName("A", t0) to 5L,
            BackupNames.pdfName("A", t0 + day) to 200L,
            BackupNames.pdfName("B", t0 + 2 * day) to 300L,
            "readme.txt" to 1L,
        )
        val v = BackupNames.versions("A", files)
        assertEquals(2, v.size)
        assertEquals(BackupNames.pdfName("A", t0 + day), v[0].pdf)
        assertNull(v[0].xfdf)
        assertEquals(BackupNames.xfdfName("A", t0), v[1].xfdf)
        assertEquals(100L, v[1].size)
    }

    @Test
    fun pruningKeepsTheNewestVersionsAndDropsOrphanComments() {
        val files = HashMap<String, Long>()
        for (i in 0 until 12) {
            files[BackupNames.pdfName("A", t0 + i * day)] = 1L
            files[BackupNames.xfdfName("A", t0 + i * day)] = 1L
        }
        // comments whose PDF is gone, and another document's files
        files[BackupNames.xfdfName("A", t0 - day)] = 1L
        files[BackupNames.pdfName("B", t0)] = 1L
        val prune = BackupNames.toPrune("A", files, keep = 10)
        assertEquals(5, prune.size)
        val left = files.keys - prune.toSet()
        assertEquals(10, BackupNames.versions("A", left.associateWith { 1L }).size)
        assertEquals(true, BackupNames.pdfName("B", t0) in left)
        assertEquals(false, BackupNames.pdfName("A", t0) in left)
        // at least one version always stays
        assertEquals(1, BackupNames.versions("A", (files.keys - BackupNames.toPrune("A", files, 0).toSet()).associateWith { 1L }).size)
    }
}
