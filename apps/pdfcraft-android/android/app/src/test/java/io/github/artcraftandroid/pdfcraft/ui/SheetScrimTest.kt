package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #719: behind a sheet on a tablet, where the sheet is narrower than the screen, the bars beside it
 * are dimmed lightly so they don't look ready to use (a tap there only closes the sheet); on a phone
 * the page stays undimmed, and a sheet that asks for the scrim gets all of it.
 */
class SheetScrimTest {
    private val scrim = Color(0x99000000)

    @Test
    fun aPhoneKeepsThePageUndimmedBehindASheet() {
        assertEquals(Color.Transparent, sheetScrim(dim = false, screenWidth = 384.dp, scrim = scrim))
        assertEquals(Color.Transparent, sheetScrim(dim = false, screenWidth = 320.dp, scrim = scrim))
    }

    @Test
    fun aTabletDimsBesideANarrowerSheetLightly() {
        val beside = sheetScrim(dim = false, screenWidth = 753.dp, scrim = scrim)
        assertEquals(scrim.alpha * 0.5f, beside.alpha, 0.001f)
        assertEquals(scrim.red, beside.red, 0.001f)
        // a screen only a little wider than the sheet's 560 dp keeps the page undimmed
        assertEquals(Color.Transparent, sheetScrim(dim = false, screenWidth = 580.dp, scrim = scrim))
    }

    @Test
    fun aSheetThatAsksForTheScrimGetsAllOfIt() {
        assertEquals(scrim, sheetScrim(dim = true, screenWidth = 384.dp, scrim = scrim))
        assertEquals(scrim, sheetScrim(dim = true, screenWidth = 753.dp, scrim = scrim))
    }
}
