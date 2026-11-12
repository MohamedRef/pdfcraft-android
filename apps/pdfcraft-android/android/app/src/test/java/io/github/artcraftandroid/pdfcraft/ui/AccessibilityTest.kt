package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.A11Y_FIXABLE
import io.github.artcraftandroid.pdfcraft.ui.viewer.A11yFinding
import io.github.artcraftandroid.pdfcraft.ui.viewer.A11yRule
import io.github.artcraftandroid.pdfcraft.ui.viewer.a11yLanguage
import io.github.artcraftandroid.pdfcraft.ui.viewer.a11yPages
import io.github.artcraftandroid.pdfcraft.ui.viewer.sortA11yRules
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The Accessibility sheet's ordering and arguments (#182). */
class AccessibilityTest {
    private fun rule(id: String, status: String, vararg pages: Int?) =
        A11yRule(id, id, "document", status, pages.map { A11yFinding("m", it) })

    @Test
    fun failedFirstThenManualKeepingTheCheckersOrder() {
        val rules = listOf(rule("a", "passed"), rule("b", "failed"), rule("c", "manual"), rule("d", "skipped"), rule("e", "failed"))
        assertEquals(listOf("b", "e", "c", "a", "d"), sortA11yRules(rules).map { it.rule })
    }

    @Test
    fun pagesAreEachOnceInOrder() {
        assertEquals(listOf(0, 2), a11yPages(rule("x", "failed", 2, null, 0, 2)))
        assertEquals(emptyList<Int>(), a11yPages(rule("x", "failed", null)))
    }

    @Test
    fun languageFallsBackToTheDevice() {
        assertEquals("ar-EG", a11yLanguage("  ", Locale.forLanguageTag("ar-EG")))
        assertEquals("fr", a11yLanguage(" fr ", Locale.US))
    }

    @Test
    fun onlyTheEnginesAutomaticFixes() {
        assertEquals(setOf("primary-language", "title", "tab-order"), A11Y_FIXABLE)
    }
}
