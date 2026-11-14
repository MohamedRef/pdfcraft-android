package io.github.artcraftandroid.pdfcraft.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unlock PDF (#517): what a file's security says, the copy's name, the counts and the log. */
class UnlockPdfTest {
    @Test
    fun aFileIsRestrictedWhenItsSecurityLimitsAnything() {
        assertFalse(unlockRestricted(null))
        assertFalse(unlockRestricted(JSONObject().put("protected", false)))
        val open = JSONObject("""{"protected":true,"printing":"high","modify":true,"assemble":true,"extract_pages":true,"copy":true,"annotate":true,"fill_forms":true,"accessibility":true}""")
        assertFalse(unlockRestricted(open))
        assertTrue(unlockRestricted(JSONObject(open.toString()).put("copy", false)))
        assertTrue(unlockRestricted(JSONObject(open.toString()).put("printing", "low")))
    }

    @Test
    fun theCopyIsNamedAfterTheFile() {
        assertEquals("Report_unlocked.pdf", unlockOutName("Report.pdf"))
        assertEquals("scan_unlocked.pdf", unlockOutName("scan"))
    }

    @Test
    fun theEndSaysHowManyWereUnlockedSkippedAndFailedNamingTheFailedOnes() {
        val files = listOf(
            UnlockFile("a.pdf", result = UnlockResult.Password),
            UnlockFile("b.pdf", result = UnlockResult.Restrictions),
            UnlockFile("c.pdf", result = UnlockResult.SkippedPassword),
            UnlockFile("d.pdf", result = UnlockResult.NotProtected),
            UnlockFile("e.pdf", result = UnlockResult.Failed, reason = "broken"),
        )
        assertEquals(UnlockSummary(2, 2, 1, listOf("e.pdf")), unlockSummary(files))
        val run = unlockRunDiag(files, neededPassword = 2, wrongPassword = 1, ms = 40L, cancelled = false).toMap()
        assertEquals(5, run["files"])
        assertEquals(2, run["unlocked"])
        assertEquals(1, run["restrictions_only"])
        assertEquals(2, run["skipped"])
        assertEquals(1, run["failed"])
    }

    @Test
    fun theLogNeverHoldsANameOrAPassword() {
        val line = unlockFileDiag(encrypted = true, hadOpenPassword = true, hadRestrictions = false, signed = false, result = UnlockResult.Password, ms = 12L, reason = "engine").toList()
        assertEquals("unlocked", line.toMap()["result"])
        val text = line.joinToString { "${it.first}=${it.second}" }
        assertFalse(text.contains(".pdf"))
        val run = unlockRunDiag(listOf(UnlockFile("secret-name.pdf", result = UnlockResult.Failed, reason = "x")), 1, 0, 1L, false).joinToString { "${it.first}=${it.second}" }
        assertFalse(run.contains("secret-name"))
    }
}
