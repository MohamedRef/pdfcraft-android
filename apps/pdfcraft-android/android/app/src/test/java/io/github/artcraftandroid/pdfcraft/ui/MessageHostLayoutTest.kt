package io.github.artcraftandroid.pdfcraft.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Where the message at the bottom of the screen lies (#635): in the middle and no wider than
 * [MessageMaxWidth] on a tablet, with the page beside it still taking touches; from side to side
 * on a phone, as before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MessageHostLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var vm: AppViewModel
    private var pageTaps = 0

    /** A page that counts its taps, with the message over its foot as AppRoot lays it; returns the message's box and the screen's, in dp. */
    private fun show(): Pair<Rect, Rect> {
        rule.setContent {
            PdfcraftTheme(ThemeChoice.Light) {
                vm = viewModel()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().testTag("page").clickable { pageTaps++ })
                    MessageHost(vm, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp).padding(horizontal = 8.dp))
                }
            }
        }
        rule.waitForIdle()
        rule.runOnUiThread { vm.tell("Copied") }
        rule.waitForIdle()
        val d = rule.density.density
        fun Rect.dp() = Rect(left / d, top / d, right / d, bottom / d)
        val bar = rule.onNodeWithTag(MESSAGE_BAR_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.dp()
        val screen = rule.onRoot().fetchSemanticsNode().boundsInRoot.dp()
        return bar to screen
    }

    @Test @Config(qualifiers = "en-w1280dp-h800dp-mdpi")
    fun onATabletTheMessageIsInTheMiddleAndThePageBesideItTakesTouches() {
        val (bar, screen) = show()
        assertTrue("the message is ${bar.width} dp wide", bar.width > 0f && bar.width <= MessageMaxWidth.value + 0.5f)
        assertEquals("the same room on both sides: $bar in $screen", bar.left - screen.left, screen.right - bar.right, 1.5f)
        assertTrue("it starts at ${bar.left} dp", bar.left > 300f)
        // a touch on the message's line, beside the message, is the page's
        val d = rule.density.density
        rule.onNodeWithTag("page").performTouchInput { click(Offset(100f * d, bar.center.y * d)) }
        rule.waitForIdle()
        assertEquals(1, pageTaps)
    }

    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi")
    fun onAPhoneTheMessageKeepsItsSideGaps() {
        val (bar, screen) = show()
        assertTrue("it starts at ${bar.left} dp", bar.left >= 7.5f)
        assertTrue("it ends at ${bar.right} dp of ${screen.right}", bar.right <= screen.right - 7.5f)
        assertEquals("the same room on both sides: $bar in $screen", bar.left - screen.left, screen.right - bar.right, 1.5f)
    }
}
