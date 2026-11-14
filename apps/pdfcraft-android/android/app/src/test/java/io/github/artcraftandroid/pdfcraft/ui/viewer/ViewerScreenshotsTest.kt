package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.pdfcraft.ui.PdfcraftTheme
import io.github.artcraftandroid.pdfcraft.ui.ThemeChoice
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rows of sub-tools with their captions (#526): Highlight's, Comment's (Stamp chosen, so its
 * choice shows too) and Text's, at 320 dp wide in the app's seven languages. Each row has to fit
 * without scrolling, so no caption is cut or pushed off the screen; that check always runs. With
 * -Ppdfcraft.screenshots=true the rows are also written as pictures for UI review
 * (docs/ANDROID_SCREENSHOTS.md). Robolectric's fonts are not a phone's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ViewerScreenshotsTest {
    @get:Rule val rule = createComposeRule()

    private val out = File(System.getProperty("pdfcraft.screenshots.dir") ?: "build/screenshots", "viewer")

    private fun rows(locale: String, theme: ThemeChoice = ThemeChoice.Light) {
        rule.setContent {
            PdfcraftTheme(theme) {
                Column(Modifier.fillMaxSize().background(pdfColors.desk)) {
                    Spacer(Modifier.height(8.dp))
                    MarkupSubTools(TextMarkup.Squiggly, {}, Color(0xFFFFD100), colorOpen = false) {}
                    Spacer(Modifier.height(8.dp))
                    CommentSubTools(CommentMode.Stamp, {}, stampOpen = false) {}
                    Spacer(Modifier.height(8.dp))
                    TextSubTools(callout = true, {}, sizeOpen = false, {}, Color(0xFFE53935), colorOpen = false) {}
                }
            }
        }
        rule.waitForIdle()
        // every row fits: none of the bars can scroll sideways
        val scrolling = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() }
        assertEquals("rows that scroll at 320 dp in $locale", 0, scrolling.count { it > 0f })
        if (System.getProperty("pdfcraft.screenshots") == "true") {
            out.mkdirs()
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            File(out, "subtools_${locale}_320x640_${theme.name.lowercase()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test @Config(qualifiers = "en-w320dp-h640dp-xhdpi") fun english() = rows("en")
    @Test @Config(qualifiers = "en-w320dp-h640dp-xhdpi") fun englishDark() = rows("en", ThemeChoice.Dark)
    @Test @Config(qualifiers = "ar-w320dp-h640dp-xhdpi") fun arabic() = rows("ar")
    @Test @Config(qualifiers = "de-w320dp-h640dp-xhdpi") fun german() = rows("de")
    @Test @Config(qualifiers = "es-w320dp-h640dp-xhdpi") fun spanish() = rows("es")
    @Test @Config(qualifiers = "fr-w320dp-h640dp-xhdpi") fun french() = rows("fr")
    @Test @Config(qualifiers = "it-w320dp-h640dp-xhdpi") fun italian() = rows("it")
    @Test @Config(qualifiers = "pt-rBR-w320dp-h640dp-xhdpi") fun portuguese() = rows("pt-BR")
}
