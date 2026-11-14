package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.ui.PdfcraftTheme
import io.github.artcraftandroid.pdfcraft.ui.ThemeChoice
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The viewer's top bar for UI review (#525, #462): a short name, a long name with "Unsaved
 * changes", Saving…, a tool active, and reading aloud (the only time the Read aloud switch shows).
 * In the seven languages at 320 dp and 360 dp. Nothing is compared; the files are for a person
 * to look at (docs/ANDROID_SCREENSHOTS.md). Off unless Gradle gets -Ppdfcraft.screenshots=true.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ViewerTopBarScreenshotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyWhenAsked() = assumeTrue(System.getProperty("pdfcraft.screenshots") == "true")

    private val out = File(System.getProperty("pdfcraft.screenshots.dir") ?: "build/screenshots", "viewer").apply { mkdirs() }

    @Composable
    private fun Caption(text: String) =
        Text(text, color = pdfColors.textSecondary, fontSize = 10.sp, modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp))

    @Composable
    private fun Bar(
        name: String,
        state: SaveState? = null,
        toolActive: Boolean = false,
        readingAloud: Boolean = false,
        canUndo: Boolean = false,
    ) = ViewerTopBar(
        toolActive = toolActive, doneIsCheck = true, canUndo = canUndo, canRedo = false,
        onBack = {}, onUndo = {}, onRedo = {}, readingAloud = readingAloud, onReadAloud = {},
        onSearch = {}, onShare = {}, onMore = {}, moreOpen = false,
        saveState = state, docName = name,
    )

    private fun draw(config: String, theme: ThemeChoice = ThemeChoice.Light) {
        val long = "Quarterly report for the regional planning committee 2026 final version"
        rule.setContent {
            PdfcraftTheme(theme) {
                Column(Modifier.fillMaxWidth().background(pdfColors.desk), verticalArrangement = Arrangement.Top) {
                    Caption("Short name, nothing edited")
                    Bar("Invoice 42")
                    Caption("Long name, Unsaved changes")
                    Bar(long, SaveState.Unsaved, canUndo = true)
                    Caption("Saving")
                    Bar(long, SaveState.Saving, canUndo = true)
                    Caption("A tool active (Draw)")
                    Bar(long, SaveState.Unsaved, toolActive = true, canUndo = true)
                    Caption("Reading aloud")
                    Bar(long, SaveState.Saved, readingAloud = true)
                }
            }
        }
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(out, "top_bar_${config}_${theme.name.lowercase()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(qualifiers = "en-w320dp-h640dp-xhdpi") fun english320() = draw("en_320")
    @Test @Config(qualifiers = "en-w360dp-h640dp-xhdpi") fun english360() = draw("en_360")
    @Test @Config(qualifiers = "en-w360dp-h640dp-xhdpi") fun english360Dark() = draw("en_360", ThemeChoice.Dark)
    @Test @Config(qualifiers = "ar-w320dp-h640dp-xhdpi") fun arabic320() = draw("ar_320")
    @Test @Config(qualifiers = "ar-w360dp-h640dp-xhdpi") fun arabic360() = draw("ar_360")
    @Test @Config(qualifiers = "de-w320dp-h640dp-xhdpi") fun german320() = draw("de_320")
    @Test @Config(qualifiers = "fr-w320dp-h640dp-xhdpi") fun french320() = draw("fr_320")
    @Test @Config(qualifiers = "es-w320dp-h640dp-xhdpi") fun spanish320() = draw("es_320")
    @Test @Config(qualifiers = "it-w320dp-h640dp-xhdpi") fun italian320() = draw("it_320")
    @Test @Config(qualifiers = "pt-rBR-w320dp-h640dp-xhdpi") fun portuguese320() = draw("pt_320")
}
