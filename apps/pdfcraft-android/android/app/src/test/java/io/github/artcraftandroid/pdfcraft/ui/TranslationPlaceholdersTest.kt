package io.github.artcraftandroid.pdfcraft.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * A translation must keep its English string's format placeholders (%1$s, %2$d, %.1f…): a missing or
 * different one shows the wrong value or makes getString throw at run time in that language only.
 */
class TranslationPlaceholdersTest {
    private val placeholder = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfx%]""")

    private fun strings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.documentElement.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
    }

    // A literal percent (%%) may be written with the language's own sign, such as Arabic ٪.
    private fun placeholders(s: String) = placeholder.findAll(s).map { it.value }.filter { it != "%%" }.sorted().toList()

    @Test
    fun everyTranslationKeepsItsPlaceholders() {
        val res = File("src/main/res")
        val base = strings(File(res, "values/strings.xml"))
        val locales = res.listFiles { f -> f.name.startsWith("values-") && File(f, "strings.xml").isFile }.orEmpty()
        assertTrue("no translations found", locales.isNotEmpty())
        val wrong = locales.flatMap { dir ->
            strings(File(dir, "strings.xml")).mapNotNull { (name, text) ->
                val english = base[name] ?: return@mapNotNull null
                if (placeholders(english) != placeholders(text)) "${dir.name}/$name: ${placeholders(english)} vs ${placeholders(text)}" else null
            }
        }
        assertTrue("Translations with different placeholders:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }
}
