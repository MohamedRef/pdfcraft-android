package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.artcraftandroid.pdfcraft.ui.PdfcraftTheme
import io.github.artcraftandroid.pdfcraft.ui.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The contents at the side on every tablet (#659): the Contents button opens the side panel, never
 * the bottom sheet, on a tablet held upright; a phone keeps the sheet; the folded strip opens the
 * panel on the tab tapped, with full-size targets; the panel's state is kept for each document.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ContentsSidePanelTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun controller(widthDp: Int, swDp: Int) = ViewerController().apply { contentsPlace = contentsPlace(widthDp, swDp, false) }

    @Test fun onATabletUprightTheButtonOpensThePanelNotTheSheet() {
        val ctl = controller(800, 800)
        ctl.panelOpen = false
        assertEquals(ContentsPlace.Docked, ctl.toggleContents(hasOutline = true))
        assertTrue(ctl.panelOpen)
        assertEquals(ContentsTab.Contents, ctl.contentsTab)
        assertEquals(ViewerSheet.None, ctl.sheet)
        // a second tap folds it again
        assertNull(ctl.toggleContents(hasOutline = true))
        assertFalse(ctl.panelOpen)
        assertEquals(ViewerSheet.None, ctl.sheet)
    }

    @Test fun onANarrowTabletThePanelComesOverThePageAndTheButtonClosesIt() {
        val ctl = controller(600, 600)
        assertEquals(ContentsPlace.Overlay, ctl.toggleContents(hasOutline = false))
        assertTrue(ctl.overlayOpen)
        assertEquals(ContentsTab.Bookmarks, ctl.contentsTab)
        assertEquals(ViewerSheet.None, ctl.sheet)
        assertNull(ctl.toggleContents(hasOutline = false))
        assertFalse(ctl.overlayOpen)
    }

    @Test fun aPhoneKeepsTheSheetAlsoTurnedSideways() {
        for ((w, sw) in listOf(384 to 384, 900 to 393)) {
            val ctl = controller(w, sw)
            assertEquals(ContentsPlace.Sheet, ctl.toggleContents(hasOutline = true))
            assertEquals(ViewerSheet.Bookmarks, ctl.sheet)
            assertEquals(ViewerSheet.None, ctl.bookmarksBack)
        }
    }

    @Test fun theStripOpensThePanelOnTheTabTapped() {
        val ctl = controller(800, 800)
        ctl.panelOpen = false
        ctl.showContents(ContentsTab.Pages, "strip")
        assertTrue(ctl.panelOpen)
        assertEquals(ContentsTab.Pages, ctl.contentsTab)
        // from Pages, the Contents button goes to the contents rather than folding the panel
        assertEquals(ContentsPlace.Docked, ctl.toggleContents(hasOutline = true))
        assertTrue(ctl.panelOpen)
        assertEquals(ContentsTab.Contents, ctl.contentsTab)
    }

    @Test fun thePanelsStateIsKeptAsText() {
        for (open in listOf(true, false)) for (tab in ContentsTab.entries) {
            assertEquals(open to tab, decodeContentsPanel(encodeContentsPanel(open, tab)))
        }
        assertEquals(false to ContentsTab.Pages, decodeContentsPanel(encodeContentsPanel(false, null)))
        assertNull(decodeContentsPanel(null))
        assertNull(decodeContentsPanel("open"))
        assertNull(decodeContentsPanel("half:contents"))
        assertNull(decodeContentsPanel("open:chapters"))
    }

    @Test @Config(qualifiers = "en-w800dp-h1280dp-mdpi")
    fun theFoldedStripHasThreeFullSizeTargets() {
        val tapped = mutableListOf<ContentsTab>()
        rule.setContent { PdfcraftTheme(ThemeChoice.Light) { ContentsStrip(onOpen = { tapped += it }) } }
        rule.waitForIdle()
        val d = rule.density.density
        for (t in listOf(ContentsTab.Pages, ContentsTab.Contents, ContentsTab.Bookmarks)) {
            val r = rule.onNodeWithTag(contentsStripTag(t)).fetchSemanticsNode().boundsInRoot
            assertTrue("$t is ${r.width / d} × ${r.height / d} dp", r.width / d >= 48f && r.height / d >= 48f)
            rule.onNodeWithTag(contentsStripTag(t)).performClick()
        }
        rule.waitForIdle()
        assertEquals(listOf(ContentsTab.Pages, ContentsTab.Contents, ContentsTab.Bookmarks), tapped)
    }
}
