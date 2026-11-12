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
}
