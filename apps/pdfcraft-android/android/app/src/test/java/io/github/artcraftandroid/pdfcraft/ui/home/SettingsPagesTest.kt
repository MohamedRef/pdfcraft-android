package io.github.artcraftandroid.pdfcraft.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings' pages (#447): where each one opens from, and that Diagnostics stays reachable from About. */
class SettingsPagesTest {
    @Test
    fun everyPageBacksOutToSettings() {
        for (page in SettingsPage.entries) {
            val stack = settingsStack(page)
            assertEquals(SettingsPage.Root, stack.first())
            assertEquals(page, stack.last())
        }
    }

    @Test
    fun scannerTestingSitsInDiagnostics() {
        assertEquals(listOf(SettingsPage.Root, SettingsPage.Diagnostics, SettingsPage.ScanTesting), settingsStack(SettingsPage.ScanTesting))
    }

    private val source = File("src/main/java/io/github/artcraftandroid/pdfcraft/ui/home/Settings.kt").readText()

    /** The owner shares logs through Settings > About > Diagnostics; About keeps that row. */
    @Test
    fun aboutStillOpensDiagnostics() {
        val about = source.substringAfter("private fun AboutPage(")
        assertTrue(about.contains("open(SettingsPage.Diagnostics)"))
    }

    /** Every page but Settings' own is opened from somewhere. */
    @Test
    fun everyPageIsOpenedSomewhere() {
        val unreachable = SettingsPage.entries.filter { it != SettingsPage.Root && !source.contains("open(SettingsPage.${it.name})") }
        assertEquals(emptyList<SettingsPage>(), unreachable)
    }
}
