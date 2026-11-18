package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.artcraftandroid.pdfcraft.ui.DarkPdfColors
import io.github.artcraftandroid.pdfcraft.ui.LightPdfColors
import io.github.artcraftandroid.pdfcraft.ui.PdfcraftTheme
import io.github.artcraftandroid.pdfcraft.ui.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Saving where people look for it (#190). On the owner's tablet, 0.0.43-beta (43012), tapping the
 * top bar's "Unsaved changes" saved the document, but nothing showed it could be tapped (the
 * same grey as "Saved"), and ⋮ had no Save. "Unsaved changes" is now a Save button that looks
 * like one, and ⋮ → File has Save while there are unsaved changes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w384dp-h832dp-xhdpi")
class SaveButtonTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var state by mutableStateOf<SaveState?>(null)
    private var taps = 0

    private fun bar(shown: SaveState?) {
        state = shown
        rule.setContent {
            PdfcraftTheme(ThemeChoice.Light) {
                ViewerTopBar(
                    toolActive = false, doneIsCheck = true, canUndo = true, canRedo = false,
                    onBack = {}, onUndo = {}, onRedo = {}, readingAloud = false, onReadAloud = {},
                    onSearch = {}, onShare = {}, onMore = {}, moreOpen = false,
                    saveState = state, onSaveState = { taps++ }, docName = "PdfCraft sample",
                    onContents = {}, showShare = topBarHoldsShare(384),
                )
            }
        }
        rule.waitForIdle()
    }

    private fun clickLabel(text: String): String? =
        rule.onNodeWithText(text).fetchSemanticsNode().config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label

    private fun role(text: String): Role? =
        rule.onNodeWithText(text).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.Role) { null }

    @Test
    fun unsavedChangesIsASaveButton() {
        bar(SaveState.Unsaved)
        // TalkBack: "Unsaved changes, button, double-tap to save"
        assertEquals(Role.Button, role("Unsaved changes"))
        assertEquals("Save", clickLabel("Unsaved changes"))
        rule.onNodeWithText("Unsaved changes").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun keptInPdfCraftIsASaveACopyButton() {
        bar(SaveState.KeptInApp)
        assertEquals(Role.Button, role("Kept in PdfCraft"))
        assertEquals("Save a copy", clickLabel("Kept in PdfCraft"))
    }

    @Test
    fun savingAndSavedAreNotButtons() {
        bar(SaveState.Saving)
        assertNull(role("Saving…"))
        assertNull(clickLabel("Saving…"))
        state = SaveState.Saved
        rule.waitForIdle()
        assertNull(role("Saved"))
        assertNull(clickLabel("Saved"))
        rule.onNodeWithText("Saved").performClick()
        assertEquals(0, taps)
    }

    @Test
    fun aSaveButtonLooksLikeOneAndItsWordsStayLegible() {
        for (c in listOf(LightPdfColors, DarkPdfColors)) {
            val theme = if (c.dark) "dark" else "light"
            for (s in listOf(SaveState.Unsaved, SaveState.KeptInApp)) {
                val look = saveStateLook(s, c)
                // the top bar is drawn on the surface colour
                val behind = look.background.compositeOver(c.surface)
                assertNotEquals("$s has no chip behind it in $theme", c.surface, behind)
                assertNotEquals("$s's icon is grey in $theme", c.textSecondary, look.icon)
                assertTrue("$s's words in $theme: ${contrast(look.text, behind)}:1", contrast(look.text, behind) >= 4.5f)
                assertTrue("$s's icon in $theme: ${contrast(look.icon, behind)}:1", contrast(look.icon, behind) >= 3f)
            }
            for (s in listOf(SaveState.Saving, SaveState.Saved)) {
                val look = saveStateLook(s, c)
                assertEquals("$s is quiet in $theme", Color.Transparent, look.background)
                assertEquals("$s is quiet in $theme", c.textSecondary, look.text)
            }
        }
    }

    @Test
    fun moreHasSaveOnlyWhileThereAreUnsavedChanges() {
        state = SaveState.Unsaved
        var saves = 0
        var copies = 0
        rule.setContent {
            PdfcraftTheme(ThemeChoice.Light) {
                Column { SaveRows(state, onSave = { saves++ }, onSaveCopy = { copies++ }) }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Save").performClick()
        assertEquals(1, saves)
        rule.onNodeWithText("Save a copy").performClick()
        assertEquals(1, copies)
        // nothing to save, a save running, or a file PdfCraft can't write: Save a copy only
        for (s in listOf(null, SaveState.Saving, SaveState.Saved, SaveState.KeptInApp)) {
            state = s
            rule.waitForIdle()
            rule.onAllNodesWithText("Save").assertCountEquals(0)
            rule.onAllNodesWithText("Save a copy").assertCountEquals(1)
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }
}
