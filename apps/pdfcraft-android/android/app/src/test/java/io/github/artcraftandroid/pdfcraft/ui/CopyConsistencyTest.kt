package io.github.artcraftandroid.pdfcraft.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The English text follows the glossary at the top of values/strings.xml (#201): US spelling,
 * "…" rather than three dots, one word per thing, and no engine words outside About and the licenses.
 */
class CopyConsistencyTest {
    private val banned = listOf(
        Regex("(?i)colour"), Regex("(?i)organisation"), Regex("(?i)licence"), Regex("\\.\\.\\."),
        Regex("\\bMiB\\b"), Regex("\\bJSON\\b"), Regex("(?i)\\bn/a\\b"),
        Regex("(?i)\\bnative\\b"), Regex("(?i)\\bengine\\b"), Regex("\\bAPK\\b"), Regex("SHA-256"),
        // one word per thing: Recognize text, Image (Pictures/PdfCraft is a folder), Highlighter, Width
        Regex("\\bOCR\\b"), Regex("(?i)\\bpictures?\\b(?!/)"), Regex("\\bMarker\\b"), Regex("(?i)thickness|stroke width"),
    )

    /** About and the licenses name the engine on purpose. */
    private val about = setOf("home_version", "home_engine", "home_legal_notice", "root_licence_pdfcraft", "root_licence_rust")

    @Test
    fun englishTextKeepsToTheGlossary() {
        val values = File("src/main/res/values")
        val found = values.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().flatMap { file ->
            val text = file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            Regex("<(string|plurals) name=\"([^\"]+)\"[^>]*>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL).findAll(text)
                .filter { it.groupValues[2] !in about }
                .flatMap { m -> banned.filter { it.containsMatchIn(m.groupValues[3]) }.map { "${m.groupValues[2]}: ${it.pattern}" } }
                .toList()
        }
        assertEquals(emptyList<String>(), found)
    }

    /** Every English string, comments removed, as key to text (#345). */
    private fun english(): List<Pair<String, String>> =
        File("src/main/res/values").listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().flatMap { file ->
            val text = file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(text)
                .map { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("\\\"", "\"") }.toList()
        }

    /** Long explanations get cut on phones and are hard to translate; keep each to 160 characters (#345). */
    @Test
    fun englishStringsStayShort() {
        val long = english().filter { it.second.length > 160 }.map { "${it.first}: ${it.second.length}" }
        assertEquals(emptyList<String>(), long)
    }

    /** Names that are capitalized on purpose: the app, keys, formats and abbreviations. */
    private val titleCaseOnPurpose = setOf(
        "app_name", "home_sort_name_az", "home_sort_name_za", "home_about_app", "home_leaving_title", "key_page_up",
        "key_page_down", "tools_format_rtf", "msg_busy_creating_pdf", "edit_label_run_javascript", "redact_pattern_ssn",
    )

    /** Labels use sentence case: "Bulleted list", not "Bulleted List" (#345). */
    @Test
    fun labelsUseSentenceCase() {
        val titleCase = english().filter { (key, text) ->
            val words = text.replace(Regex("\\(.*?\\)"), "").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            key !in titleCaseOnPurpose && words.size >= 2 && words.all { it.first().isUpperCase() } &&
                words.drop(1).none { Regex("[A-Z0-9]{2,}").matches(it) }
        }.map { it.first }
        assertEquals(emptyList<String>(), titleCase)
    }
}
