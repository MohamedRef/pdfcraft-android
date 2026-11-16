package io.github.artcraftandroid.pdfcraft.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every language the app ships has every English string (#616). A string added in English only
 * shows in English to Arabic, German, Spanish, French, Italian and Portuguese readers, so this
 * fails until the same key is in each values-xx folder. Strings marked translatable="false" are
 * left out. Placeholders must match too, or the text crashes or shows the wrong number.
 */
class TranslationCompletenessTest {
    private class Entry(val kind: String, val body: String)

    private val res = File("src/main/res")

    private fun load(dir: File): Map<String, Entry> =
        dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }.flatMap { file ->
            val text = file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            Regex("<(string|plurals) name=\"([^\"]+)\"([^>]*)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL).findAll(text)
                .filter { "translatable=\"false\"" !in it.groupValues[3] }
                .map { it.groupValues[2] to Entry(it.groupValues[1], it.groupValues[4]) }.toList()
        }.toMap()

    private val english by lazy { load(File(res, "values")) }

    /** The language folders: values-xx with strings, not values-night or values-v29. */
    private val languages by lazy {
        res.listFiles { f ->
            f.isDirectory && Regex("values-[a-z]{2}(-r[A-Z]{2})?").matches(f.name) &&
                f.listFiles { s -> s.name.startsWith("strings") }.orEmpty().isNotEmpty()
        }.orEmpty().sortedBy { it.name }
    }

    /** "%d" and "%1$d" are the same argument, so both count as "%1$d". */
    private fun placeholders(text: String) =
        Regex("%(\\d+\\$)?([sdf])").findAll(text).map { "%${it.groupValues[1].ifEmpty { "1$" }}${it.groupValues[2]}" }.sorted().toList()

    @Test
    fun theLanguagesAreFound() {
        assertTrue("expected the shipped languages, found ${languages.map { it.name }}", languages.size >= 6)
        assertTrue(english.size > 1000)
    }

    @Test
    fun everyLanguageHasEveryEnglishString() {
        val missing = languages.associate { dir ->
            val have = load(dir).keys
            dir.name to english.keys.filter { it !in have }
        }.filterValues { it.isNotEmpty() }.mapValues { (_, keys) -> "${keys.size}: ${keys.take(20)}" }
        assertEquals("Translate these keys in each language (values-xx/strings*.xml)", emptyMap<String, String>(), missing)
    }

    @Test
    fun noLanguageKeepsStringsEnglishDropped() {
        val extra = languages.associate { dir -> dir.name to load(dir).keys.filter { it !in english } }.filterValues { it.isNotEmpty() }
        assertEquals("Remove these keys: English no longer has them", emptyMap<String, List<String>>(), extra)
    }

    @Test
    fun placeholdersMatchEnglish() {
        val wrong = languages.flatMap { dir ->
            load(dir).mapNotNull { (key, entry) ->
                val en = english[key] ?: return@mapNotNull null
                when {
                    en.kind != entry.kind -> "${dir.name}/$key: ${entry.kind} but English has ${en.kind}"
                    en.kind == "string" && placeholders(en.body) != placeholders(entry.body) ->
                        "${dir.name}/$key: ${placeholders(entry.body)} but English has ${placeholders(en.body)}"
                    en.kind == "plurals" && "quantity=\"other\"" !in entry.body -> "${dir.name}/$key: no \"other\" quantity"
                    en.kind == "plurals" && !placeholders(en.body).toSet().containsAll(placeholders(entry.body)) ->
                        "${dir.name}/$key: placeholders ${placeholders(entry.body)} that English lacks"
                    else -> null
                }
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }
}
