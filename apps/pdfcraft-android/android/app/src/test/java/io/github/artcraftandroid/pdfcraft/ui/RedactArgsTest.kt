package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.RedactLook
import io.github.artcraftandroid.pdfcraft.ui.viewer.RedactPattern
import io.github.artcraftandroid.pdfcraft.ui.viewer.RedactSearch
import io.github.artcraftandroid.pdfcraft.ui.viewer.lookArgs
import io.github.artcraftandroid.pdfcraft.ui.viewer.redactSearchArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Redact's Find & mark (#163), as `redact_mark` takes it. */
class RedactArgsTest {
    private val look = RedactLook()

    @Test
    fun onePhraseIsFindSeveralAreWords() {
        val one = redactSearchArgs(RedactSearch.Text(" Alpha ", wholeWords = true, caseSensitive = false), look)!!
        assertEquals("Alpha", one["find"])
        assertEquals(true, one["whole_words"])
        assertEquals(false, one["case_sensitive"])
        assertFalse("words" in one)
        val many = redactSearchArgs(RedactSearch.Text("Alpha, Bravo ,, ", wholeWords = false, caseSensitive = true), look)!!
        assertEquals(listOf("Alpha", "Bravo"), many["words"])
        assertFalse("find" in many)
        assertNull(redactSearchArgs(RedactSearch.Text(" , ", false, false), look))
    }

    @Test
    fun patternsUseTheEngineNames() {
        assertEquals(listOf("phone", "email", "credit-card", "ssn", "date", "iban"), RedactPattern.entries.map { it.arg })
        assertEquals("credit-card", redactSearchArgs(RedactSearch.Pattern(RedactPattern.Card), look)!!["pattern"])
    }

    @Test
    fun regexMustNotBeBlank() {
        assertEquals("\\d{4}", redactSearchArgs(RedactSearch.Regex("\\d{4}"), look)!!["regex"])
        assertNull(redactSearchArgs(RedactSearch.Regex("  "), look))
    }

    @Test
    fun lookAddsFillAndOnlyANonEmptyOverlay() {
        assertEquals(mapOf("fill" to "#000000"), lookArgs(RedactLook()))
        assertEquals(mapOf("fill" to "#ffffff", "overlay" to "(b)(6)"), lookArgs(RedactLook("#ffffff", " (b)(6) ")))
    }
}
