package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.artcraftandroid.pdfcraft.ui.PdfcraftTheme
import io.github.artcraftandroid.pdfcraft.ui.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Contents button on the reading page's top bar (#647): one tap, a full-size target, on a
 * phone and on a tablet. On a phone Share's place goes to it, so the document's name keeps the
 * width it had; on a wider window both are there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ViewerTopBarContentsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var opened = 0
    private val name = "First Aid for the USMLE Step 1 2025"

    private fun show(widthDp: Int, toolActive: Boolean = false) {
        rule.setContent {
            PdfcraftTheme(ThemeChoice.Light) {
                ViewerTopBar(
                    toolActive = toolActive, doneIsCheck = true, canUndo = false, canRedo = false,
                    onBack = {}, onUndo = {}, onRedo = {}, readingAloud = false, onReadAloud = {},
                    onSearch = {}, onShare = {}, onMore = {}, moreOpen = false, docName = name,
                    onContents = { opened++ }, showShare = topBarHoldsShare(widthDp),
                )
            }
        }
        rule.waitForIdle()
    }

    private fun Rect.dp(): Rect {
        val d = rule.density.density
        return Rect(left / d, top / d, right / d, bottom / d)
    }

    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi")
    fun onAPhoneContentsIsOneTapAndTakesSharesPlace() {
        show(384)
        val button = rule.onNodeWithTag(TOP_BAR_CONTENTS_TAG).fetchSemanticsNode().boundsInRoot.dp()
        val screen = rule.onRoot().fetchSemanticsNode().boundsInRoot.dp()
        assertTrue("the button is ${button.width} × ${button.height} dp", button.width >= 44f && button.height >= 44f)
        assertTrue("on screen: $button in $screen", button.left >= 0f && button.right <= screen.right)
        rule.onAllNodesWithContentDescription("Share").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Find in document").assertCountEquals(1)
        // the name keeps the room it had before the button came: at least 100 dp between Back and the buttons
        val title = rule.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.dp()
        assertTrue("the name has ${title.width} dp", title.width >= 100f)
        rule.onNodeWithTag(TOP_BAR_CONTENTS_TAG).performClick()
        rule.waitForIdle()
        assertEquals(1, opened)
    }

    @Test @Config(qualifiers = "en-w800dp-h1280dp-mdpi")
    fun onATabletContentsAndShareAreBothThere() {
        show(800)
        val button = rule.onNodeWithTag(TOP_BAR_CONTENTS_TAG).fetchSemanticsNode().boundsInRoot.dp()
        assertTrue("the button is ${button.width} × ${button.height} dp", button.width >= 44f && button.height >= 44f)
        rule.onAllNodesWithContentDescription("Share").assertCountEquals(1)
    }

    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi")
    fun withAToolActiveTheBarHoldsUndoAndRedoInstead() {
        show(384, toolActive = true)
        rule.onAllNodesWithTag(TOP_BAR_CONTENTS_TAG).assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Undo").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Redo").assertCountEquals(1)
    }
}
