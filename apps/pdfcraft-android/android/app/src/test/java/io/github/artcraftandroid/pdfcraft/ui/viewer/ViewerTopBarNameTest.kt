package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import io.github.artcraftandroid.pdfcraft.ui.PdfcraftTheme
import io.github.artcraftandroid.pdfcraft.ui.ThemeChoice
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The document's name on the reading page's top bar (#706). On a 384 dp phone, the owner's,
 * "PdfCraft sample" shows in full at 15 sp, and so does "Unsaved changes" under it; on a 360 dp
 * phone the name shows in full too, a point smaller at most. Back, Undo, Contents, Find and More
 * keep their 46 dp beside it, and a name too long for the bar is still cut in the middle. Before
 * #706 the name had 114 dp for its letters at 384 dp and read "PdfCraf…ample".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ViewerTopBarNameTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val sample = "PdfCraft sample"
    private val buttons = listOf("Back", "Undo", "Bookmarks & contents", "Find in document", "More options")

    private fun show(name: String, widthDp: Int, state: SaveState? = null) {
        rule.setContent {
            PdfcraftTheme(ThemeChoice.Light) {
                ViewerTopBar(
                    toolActive = false, doneIsCheck = true, canUndo = true, canRedo = false,
                    onBack = {}, onUndo = {}, onRedo = {}, readingAloud = false, onReadAloud = {},
                    onSearch = {}, onShare = {}, onMore = {}, moreOpen = false, docName = name, saveState = state,
                    onContents = {}, showShare = topBarHoldsShare(widthDp),
                )
            }
        }
        rule.waitForIdle()
    }

    private fun layout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    private fun Rect.dp(): Rect {
        val d = rule.density.density
        return Rect(left / d, top / d, right / d, bottom / d)
    }

    private fun bounds(description: String): Rect = rule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot.dp()

    /** [text] lies on one line, as wide as it is: nothing of it cut. */
    private fun assertWhole(text: String, where: String) {
        val l = layout(text)
        val wide = ceil(l.multiParagraph.intrinsics.maxIntrinsicWidth).toInt()
        val room = l.layoutInput.constraints.maxWidth
        assertEquals("$where: \"$text\" lines", 1, l.lineCount)
        assertTrue("$where: \"$text\" needs $wide px and has $room px", wide <= room)
        assertFalse("$where: \"$text\" is cut", l.isLineEllipsized(0))
    }

    /** Every button keeps its 46 dp on the screen, and the name lies between Back and Undo. */
    private fun assertButtonsKeepTheirSize(name: String, widthDp: Int) {
        for (label in buttons) {
            val b = bounds(label)
            assertTrue("$label is ${b.width} dp wide at $widthDp dp", b.width >= 46f - 0.01f)
            assertTrue("$label lies off the screen at $widthDp dp: $b", b.left >= -0.01f && b.right <= widthDp + 0.01f)
        }
        val title = rule.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.dp()
        val back = bounds("Back")
        val undo = bounds("Undo")
        assertTrue("the name $title overlaps Back $back", title.left >= back.right - 0.01f)
        assertTrue("the name $title overlaps Undo $undo", title.right <= undo.left + 0.01f)
    }

    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi")
    fun onA384DpPhoneTheSampleNameShowsInFull() {
        show(sample, 384)
        assertWhole(sample, "384 dp")
        assertEquals(15f, layout(sample).layoutInput.style.fontSize.value, 0.01f)
        assertButtonsKeepTheirSize(sample, 384)
    }

    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi")
    fun withUnsavedChangesTheNameAndTheStateBothShowInFull() {
        show(sample, 384, SaveState.Unsaved)
        assertWhole(sample, "384 dp, unsaved")
        assertWhole("Unsaved changes", "384 dp, unsaved")
        val name = rule.onNodeWithText(sample, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.dp()
        val state = rule.onNodeWithText("Unsaved changes", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.dp()
        assertTrue("the save state $state is not under the name $name", state.top >= name.bottom - 0.01f)
        assertButtonsKeepTheirSize(sample, 384)
    }

    @Test @Config(qualifiers = "en-w360dp-h800dp-xhdpi")
    fun onA360DpPhoneTheSampleNameShowsInFullAPointSmallerAtMost() {
        show(sample, 360)
        assertWhole(sample, "360 dp")
        val size = layout(sample).layoutInput.style.fontSize.value
        assertTrue("the name is $size sp", size >= 14f)
        assertButtonsKeepTheirSize(sample, 360)
    }

    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi")
    fun aNameTooLongForTheBarIsStillCutInTheMiddle() {
        val long = "First Aid for the USMLE Step 1 2025"
        show(long, 384)
        val l = layout(long)
        assertEquals(1, l.lineCount)
        assertEquals(TextOverflow.MiddleEllipsis, l.layoutInput.overflow)
        assertTrue("the long name fits its ${l.layoutInput.constraints.maxWidth} px", l.multiParagraph.intrinsics.maxIntrinsicWidth > l.layoutInput.constraints.maxWidth)
        // one step down before it is cut, never smaller
        assertEquals(14f, l.layoutInput.style.fontSize.value, 0.01f)
        assertButtonsKeepTheirSize(long, 384)
    }
}
