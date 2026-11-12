package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.engine.diagLine
import io.github.artcraftandroid.pdfcraft.ui.viewer.redactHow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDateTime

/** The `[edit]` diagnostics lines: what they keep, and that the user's words never reach them. */
class EditDiagnosticsTest {
    private val at = LocalDateTime.of(2026, 11, 12, 6, 0, 0)

    @Test
    fun aRedactionSaysHowItWasMarkedButNotWhat() {
        assertEquals("page", redactHow(mapOf("page" to 1, "whole_pages" to true)))
        assertEquals("area", redactHow(mapOf("page" to 1, "rect" to listOf(1.0, 2.0, 3.0, 4.0))))
        assertEquals("pattern", redactHow(mapOf("pattern" to "email")))
        assertEquals("regex", redactHow(mapOf("regex" to "\\d{3}-\\d{4}")))
        assertEquals("words", redactHow(mapOf("words" to listOf("Jane", "Doe"))))
        assertEquals("phrase", redactHow(mapOf("find" to "Jane Doe")))
    }

    @Test
    fun linesKeepCountsAndRuleNames() {
        val line = diagLine(at, DiagArea.Edit, "a11y_fix", listOf("doc" to 3, "rule" to "primary-language", "status" to "passed"))
        assertEquals("11-12 06:00:00.000 [edit] a11y_fix doc=3 rule=primary-language status=passed", line)
        val spacing = diagLine(at, DiagArea.Edit, "text_spacing", listOf("doc" to 1, "line" to 1.2f, "letter" to -0.5f))
        assertEquals("11-12 06:00:00.000 [edit] text_spacing doc=1 line=1.20 letter=-0.50", spacing)
        // a typed word that slipped in would still be redacted
        assertFalse("Jane" in diagLine(at, DiagArea.Edit, "redact_mark", listOf("how" to "Jane Doe")))
    }

    @Test
    fun aSignatureLineKeepsOnlyKindsAndResults() {
        val line = diagLine(at, DiagArea.Edit, "sign_document", listOf(
            "doc" to 2, "placement" to "field", "look" to "typed", "reason" to true, "location" to false,
            "ms" to 412L, "result" to "signed", "saved" to true,
        ))
        assertEquals("11-12 06:00:00.000 [edit] sign_document doc=2 placement=field look=typed reason=true location=false ms=412 result=signed saved=true", line)
        // a signer's name would be redacted if it ever reached a line
        assertFalse("Jane" in diagLine(at, DiagArea.Edit, "sign_trust", listOf("signer" to "Jane Doe")))
    }
}
