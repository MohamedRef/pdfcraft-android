package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.PdfcraftTheme
import io.github.artcraftandroid.pdfcraft.ui.TabPolicy
import io.github.artcraftandroid.pdfcraft.ui.ThemeChoice
import io.github.artcraftandroid.pdfcraft.ui.home.HomeRoot
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
 * Every open document as a card (#669): the layer lists them all, two to a row on a phone and
 * more on a tablet, ✕ closes one document only, a tap on a card or Back takes the layer away,
 * closing the last document closes it, and the count on Home's bar opens it. The documents here
 * are tabs a restart brought back (closed to save memory), which need no engine: a card's
 * picture of an open document's page and the strip in the viewer are not drawn here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TabsOverviewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var vm: AppViewModel

    /** The cards over [under] (nothing, or Home), as the app's root lays them: [titles] are the open documents. */
    private fun show(vararg titles: String, open: Boolean = true, under: @Composable (AppViewModel) -> Unit = {}) {
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
            .putString("openTabs", TabPolicy.encode(titles.mapIndexed { i, t -> "content://t/doc$i" to t }))
            .commit()
        rule.setContent {
            PdfcraftTheme(ThemeChoice.Light) {
                vm = viewModel()
                Box(Modifier.fillMaxSize()) {
                    under(vm)
                    if (vm.tabs.overviewOpen) TabsOverview(vm)
                }
            }
        }
        rule.waitForIdle()
        assertEquals(titles.size, vm.tabs.tabs.size)
        if (open) {
            rule.runOnUiThread { vm.tabs.showOverview("home") }
            rule.waitForIdle()
        }
    }

    private fun cards(): List<Rect> {
        val d = rule.density.density
        return rule.onAllNodesWithTag(TAB_CARD_TAG).fetchSemanticsNodes().map { it.boundsInRoot }.map { Rect(it.left / d, it.top / d, it.right / d, it.bottom / d) }
    }

    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi")
    fun onAPhoneEveryDocumentIsACardTwoToARow() {
        show("Anatomy.pdf", "Invoice.pdf", "Novel.pdf")
        rule.onNodeWithTag(TABS_OVERVIEW_TAG).assertExists()
        rule.onNodeWithText("3 open documents").assertExists()
        for (title in listOf("Anatomy.pdf", "Invoice.pdf", "Novel.pdf")) rule.onNodeWithText(title, useUnmergedTree = true).assertExists()
        val cards = cards()
        assertEquals(3, cards.size)
        assertEquals("the first two share a row", cards[0].top, cards[1].top, 0.5f)
        assertTrue("the third starts the next row: $cards", cards[2].top >= cards[0].bottom)
        assertTrue("a card is ${cards[0].width} dp wide", cards[0].width >= TabCardMinWidth.value)
        assertEquals(2, tabOverviewColumns(384))
        // from Home nothing is in front: all of them can be closed, after a question
        rule.onNodeWithText("Close all").assertExists()
    }

    @Test @Config(qualifiers = "en-w1280dp-h800dp-mdpi")
    fun onATabletMoreCardsShareARow() {
        show("One.pdf", "Two.pdf", "Three.pdf", "Four.pdf", "Five.pdf")
        val cards = cards()
        assertEquals(5, cards.size)
        assertTrue("all five on one row: $cards", cards.all { kotlin.math.abs(it.top - cards[0].top) < 0.5f })
        assertEquals(7, tabOverviewColumns(1280))
        assertEquals(4, tabOverviewColumns(800))
        assertEquals(1, tabOverviewColumns(200))
    }

    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi")
    fun closingACardClosesThatDocumentOnlyAndTheLastOneClosesTheLayer() {
        show("Anatomy.pdf", "Invoice.pdf")
        rule.onNodeWithContentDescription("Close Invoice.pdf").performClick()
        rule.waitForIdle()
        assertEquals(listOf("Anatomy.pdf"), vm.tabs.tabs.map { it.title })
        assertTrue(vm.tabs.overviewOpen)
        rule.onNodeWithText("1 open document").assertExists()
        rule.onAllNodesWithTag(TAB_CARD_TAG).assertCountEquals(1)
        rule.onNodeWithContentDescription("Close Anatomy.pdf").performClick()
        rule.waitForIdle()
        assertTrue(vm.tabs.tabs.isEmpty())
        assertFalse("nothing is left to choose", vm.tabs.overviewOpen)
        rule.onAllNodesWithTag(TABS_OVERVIEW_TAG).assertCountEquals(0)
    }

    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi")
    fun backAndACardBothTakeTheLayerAway() {
        show("Anatomy.pdf", "Invoice.pdf")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        assertFalse(vm.tabs.overviewOpen)
        assertEquals("Back closes nothing", 2, vm.tabs.tabs.size)
        rule.runOnUiThread { vm.tabs.showOverview("strip") }
        rule.waitForIdle()
        rule.onNodeWithTag(TABS_OVERVIEW_TAG).assertExists()
        rule.onAllNodesWithTag(TAB_CARD_TAG)[1].performClick()
        rule.waitForIdle()
        assertFalse("a chosen card takes the layer away", vm.tabs.overviewOpen)
        rule.onAllNodesWithTag(TABS_OVERVIEW_TAG).assertCountEquals(0)
    }

    /** Nothing closes until the person says so (#636), and the button says what it will do. */
    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi")
    fun closeAllAsksFirst() {
        show("Anatomy.pdf", "Invoice.pdf", "Novel.pdf")
        rule.onNodeWithText("Close all").performClick()
        rule.waitForIdle()
        assertEquals("nothing closed by the tap", 3, vm.tabs.tabs.size)
        rule.onNodeWithText("Close 3 open documents?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals("Cancel keeps them open", 3, vm.tabs.tabs.size)
        assertTrue(vm.tabs.overviewOpen)
    }

    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi")
    fun theCountOnHomesBarShowsTheCards() {
        show("Anatomy.pdf", "Invoice.pdf", open = false) { HomeRoot(it) {} }
        rule.onAllNodesWithTag(TABS_OVERVIEW_TAG).assertCountEquals(0)
        rule.onNodeWithText("2 open documents").performClick()
        rule.waitForIdle()
        assertTrue(vm.tabs.overviewOpen)
        rule.onNodeWithTag(TABS_OVERVIEW_TAG).assertExists()
        rule.onAllNodesWithTag(TAB_CARD_TAG).assertCountEquals(2)
    }

    /** The strip's button: a 40 dp target named for TalkBack, with the number on it. */
    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi")
    fun theCountButtonIsATargetWithAName() {
        var taps = 0
        rule.setContent {
            PdfcraftTheme(ThemeChoice.Light) { TabCountButton(3, "Show all open documents", Modifier.testTag(TAB_COUNT_TAG), size = 40.dp) { taps++ } }
        }
        rule.waitForIdle()
        val d = rule.density.density
        val bounds = rule.onNodeWithContentDescription("Show all open documents").fetchSemanticsNode().boundsInRoot
        assertEquals(40f, bounds.width / d, 0.5f)
        assertEquals(40f, bounds.height / d, 0.5f)
        rule.onNodeWithText("3", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(TAB_COUNT_TAG).performClick()
        rule.waitForIdle()
        assertEquals(1, taps)
    }

    @Test
    fun theCountButtonSaysHowManyAndStaysInItsFrame() {
        assertEquals("2", tabCountText(2))
        assertEquals("12", tabCountText(12))
        assertEquals("99", tabCountText(99))
        assertEquals("99+", tabCountText(100))
        assertEquals("0", tabCountText(-3))
    }
}
