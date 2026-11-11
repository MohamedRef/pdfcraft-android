package io.github.artcraftandroid.pdfcraft.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditLabelsTest {
    private val say: (Int, String?) -> String = { id, value -> "#$id" + (value?.let { "($it)" } ?: "") }

    @Test fun everyEngineWordingHasATranslation() {
        // The engine lists every wording an edit can have; a new one must be added to EditLabels.kt.
        val source = File("../../../../crates/engine/src/edit/labels.rs").readText()
        val lists = Regex("""pub const (?:EDIT|BATCH)_LABELS: &\[&str\] =\s*&\[(.*?)\];""", RegexOption.DOT_MATCHES_ALL)
            .findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(2, lists.size)
        val wordings = lists.flatMap { list -> Regex(""""((?:[^"\\]|\\.)*)"""").findAll(list).map { it.groupValues[1] }.toList() }
        assertTrue(wordings.size > 100)
        val missing = wordings.filter { it !in EditLabelResources }
        assertTrue("Engine edit labels without a translation: $missing", missing.isEmpty())
    }

    @Test fun aWordingIsLookedUpAndItsHoleFilled() {
        val id = EditLabelResources.getValue("Rotate pages")
        assertEquals("#$id", editLabel("Rotate pages", null, "Rotate pages", say))
        val withHole = EditLabelResources.getValue("Insert pages from {name}")
        assertEquals("#$withHole(a.pdf)", editLabel("Insert pages from {name}", "a.pdf", "Insert pages from a.pdf", say))
    }

    @Test fun aBatchIsLookedUpByItsName() {
        val id = EditLabelResources.getValue("Erase ink")
        assertEquals("#$id", editLabel("{batch}", "Erase ink", "Erase ink", say))
    }

    @Test fun anUnknownWordingFallsBackToTheEnglishLabel() {
        assertEquals("Something new", editLabel("Something new", null, "Something new", say))
        assertEquals("Made in the app", editLabel("{batch}", "Made in the app", "Made in the app", say))
        assertEquals("English", editLabel(null, null, "English", say))
    }
}
