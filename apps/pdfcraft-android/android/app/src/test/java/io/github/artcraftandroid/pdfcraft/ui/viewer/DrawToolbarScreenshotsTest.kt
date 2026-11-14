package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
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
 * Pictures of the viewer's Draw bar for UI review (#462, #522): the bar with 3 and 12 favourites,
 * with the Shapes and Guides sheets drawn above it, light and dark, in English and Arabic (right to
 * left) at 320, 360 and 384 dp wide, French (long words) at 360 dp. Nothing is compared. Off unless
 * Gradle gets -Ppdfcraft.screenshots=true (docs/ANDROID_SCREENSHOTS.md).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DrawToolbarScreenshotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyWhenAsked() = assumeTrue(System.getProperty("pdfcraft.screenshots") == "true")

    private val out = File(System.getProperty("pdfcraft.screenshots.dir") ?: "build/screenshots", "viewer").apply { mkdirs() }

    private fun drawAll(config: String, themes: List<ThemeChoice> = listOf(ThemeChoice.Light, ThemeChoice.Dark)) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = ToolPrefs(context)
        val ctl = ViewerController()
        var theme by mutableStateOf(themes.first())
        var count by mutableIntStateOf(3)
        rule.setContent {
            PdfcraftTheme(theme) {
                key(theme, count) {
                    Column(Modifier.fillMaxSize().background(pdfColors.desk), verticalArrangement = Arrangement.Bottom) {
                        Column(Modifier.fillMaxWidth().background(pdfColors.surface)) {
                            Spacer(Modifier.height(12.dp))
                            DrawShapesPicker(ctl, prefs)
                            DrawGuidesPicker(ctl)
                        }
                        Spacer(Modifier.height(8.dp))
                        DrawToolbarBar(ctl, prefs)
                    }
                }
            }
        }
        for (t in themes) for (n in listOf(3, 12)) {
            theme = t
            count = n
            prefs.useFavourites(n)
            // A shape and the ruler on, so the bar shows both as in use.
            ctl.inkShapeMode = InkShapeMode.Arrow
            ctl.rulerOn = true
            rule.waitForIdle()
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            File(out, "draw_${n}fav_${config}_${t.name.lowercase()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test @Config(qualifiers = "en-w320dp-h640dp-xhdpi") fun english320() = drawAll("en_320x640", listOf(ThemeChoice.Light))
    @Test @Config(qualifiers = "en-w360dp-h640dp-xhdpi") fun english360() = drawAll("en_360x640")
    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi") fun english384() = drawAll("en_384x832")
    @Test @Config(qualifiers = "ar-w384dp-h832dp-xhdpi") fun arabic384() = drawAll("ar_384x832")
    @Test @Config(qualifiers = "fr-w360dp-h640dp-xhdpi") fun french360() = drawAll("fr_360x640", listOf(ThemeChoice.Light))
}
