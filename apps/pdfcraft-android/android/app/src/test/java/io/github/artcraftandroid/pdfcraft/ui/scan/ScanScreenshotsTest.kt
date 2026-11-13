package io.github.artcraftandroid.pdfcraft.ui.scan

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.PdfcraftTheme
import io.github.artcraftandroid.pdfcraft.ui.ScanScreen
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.ScanPage
import io.github.artcraftandroid.pdfcraft.ui.ThemeChoice
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
 * Pictures of the scanner's screens for UI review (#462): the camera's bars in each mode over a
 * drawn frame, the camera permission page, the crop screen on a drawn page, and the screens after
 * the camera with "Scan screens in steps" on: Adjust borders (#458), Enhance (#459) and the
 * document screen (#460), on a scan of three drawn pages. English, Arabic (right to left), French
 * and German (long words), at 360×640 dp and 384×832 dp; the crop screen and the steps light and dark. Off unless Gradle gets -Ppdfcraft.screenshots=true (docs/ANDROID_SCREENSHOTS.md).
 *
 * **Never a real photo**: every page and frame here is drawn in code. The camera preview itself
 * (CameraX) does not run in a JVM test, so the bars are laid out as ScanCameraScreen lays them,
 * around a drawn frame.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScanScreenshotsTest {
    @get:Rule val rule = createComposeRule()

    @Before
    fun onlyWhenAsked() = assumeTrue(System.getProperty("pdfcraft.screenshots") == "true")

    private val out = File(System.getProperty("pdfcraft.screenshots.dir") ?: "build/screenshots", "scan").apply { mkdirs() }

    private fun shoot(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val actions = ScanCameraActions({ _, _, _, _ -> }, {}, {}, {}, {}, {})

    /** Each camera mode, then the permission page, all in one composition. */
    private fun camera(config: String) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val model = ScanCameraModel(app)
        val frame = drawnPage(960, 1280, tilted = true, background = 0xFF3A3530.toInt()).asImageBitmap()
        val thumb = drawnPage(90, 120, tilted = false, background = 0xFFE0E0E0.toInt()).asImageBitmap()
        var mode by androidx.compose.runtime.mutableStateOf<ScanCameraMode?>(ScanCameraMode.Single)
        rule.setContent {
            val m = mode
            if (m != null) CameraBars(model, m, frame, thumb)
            else Box(Modifier.fillMaxSize().background(Color.Black)) { ScanCameraPermissionView(ScanCaptureRoute.AskPermission, actions) }
        }
        for (m in ScanCameraMode.entries) {
            mode = m
            shoot("camera_${m.id}_$config")
        }
        mode = null
        shoot("camera_permission_$config")
    }

    @androidx.compose.runtime.Composable
    private fun CameraBars(model: ScanCameraModel, mode: ScanCameraMode, frame: ImageBitmap, thumb: ImageBitmap) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            ScanCameraTopBar(model, actions, live = true, torchAvailable = true, torch = false, onTorch = {})
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Image(frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                ScanCameraGrid(Modifier.fillMaxSize())
                ScanZoomButton(1f, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {}
            }
            ScanCameraModeRow(mode, bookAvailable = true) {}
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { ScanImportButton {} }
                Box(Modifier.size(72.dp).clip(CircleShape).background(Color.White))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    ScanPagesButton(thumb, 3, showDone = mode != ScanCameraMode.Single) {}
                }
            }
        }
    }

    /** The crop screen on a drawn photo, with corners a little inside the page. */
    private fun crop(config: String, themes: List<ThemeChoice> = listOf(ThemeChoice.Light, ThemeChoice.Dark)) {
        val photo = drawnPage(1200, 1600, tilted = true, background = 0xFF6B5E50.toInt())
        val file = File.createTempFile("drawn-page", ".jpg").apply { deleteOnExit() }
        file.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val page = ScanPage(
            file = file,
            thumb = Bitmap.createScaledBitmap(photo, 300, 400, true).asImageBitmap(),
            corners = floatArrayOf(0.20f, 0.14f, 0.82f, 0.18f, 0.79f, 0.86f, 0.17f, 0.82f),
        )
        var theme by androidx.compose.runtime.mutableStateOf(themes.first())
        rule.setContent {
            PdfcraftTheme(theme) {
                androidx.compose.runtime.key(theme) {
                    ScanCropScreen(page, 0, ScanLab(), onDone = { _, _ -> }, onCancel = {})
                }
            }
        }
        for (t in themes) {
            theme = t
            shoot("crop_${config}_${t.name.lowercase()}")
        }
    }

    /**
     * Adjust borders, Enhance and the document screen, as the scan screen shows them with the steps
     * on (the default), on three drawn pages: Adjust borders open on page 2, then Next to Enhance on
     * it, then the document screen with all three. The engine does not run in a JVM test, so the
     * testing option "Show pages as they will be saved" is off here and Enhance shows the drawn
     * page where a phone shows the finished one.
     */
    private fun steps(config: String, themes: List<ThemeChoice> = listOf(ThemeChoice.Light, ThemeChoice.Dark)) {
        val quad = floatArrayOf(0.20f, 0.14f, 0.82f, 0.18f, 0.79f, 0.86f, 0.17f, 0.82f)
        val pages = (1..3).map { n ->
            val photo = drawnPage(1200, 1600, tilted = true, background = 0xFF6B5E50.toInt())
            val file = File.createTempFile("drawn-page-$n-", ".jpg").apply { deleteOnExit() }
            file.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            ScanPage(file = file, thumb = Bitmap.createScaledBitmap(photo, 300, 400, true).asImageBitmap(), corners = quad, found = quad)
        }
        var theme by androidx.compose.runtime.mutableStateOf(themes.first())
        lateinit var vm: AppViewModel
        rule.setContent {
            PdfcraftTheme(theme) {
                vm = viewModel()
                if (vm.scanPages.isEmpty()) {
                    vm.scanLab.update(vm.scanLab.options.copy(preview = false))
                    vm.scanPages.addAll(pages)
                }
                androidx.compose.runtime.key(theme) { ScanScreen(vm) }
            }
        }
        for (t in themes) {
            theme = t
            val name = "${config}_${t.name.lowercase()}"
            rule.runOnIdle { vm.scanSteps.openFromPage(pages[1].key, 2, "single") }
            shoot("borders_$name")
            rule.runOnIdle { vm.scanSteps.close("scan", 2) }
            shoot("enhance_$name")
            rule.runOnIdle { vm.scanSteps.showDocument("enhance", pages.size) }
            shoot("document_$name")
            // #494: pages 1 and 3 as made by the background queue, page 2 still waiting
            if (t == themes.first()) {
                rule.runOnIdle { madeTiles(vm, pages, listOf(0, 2)) }
                shoot("document_made_$name")
                rule.runOnIdle { noMadeTiles(vm) }
            }
            rule.runOnIdle { vm.scanSteps.openEnhance(1, pages.size) }
        }
    }

    /**
     * Pages [at] as if the background queue had made them (#494): a straight drawn page in place of
     * the engine's result, which a JVM test cannot make. The queue may try the others and fail;
     * they keep their photo with the waiting dot.
     */
    private fun madeTiles(vm: AppViewModel, pages: List<ScanPage>, at: List<Int>) {
        vm.scanLab.update(vm.scanLab.options.copy(preview = true, backgroundPages = true))
        for (i in at) {
            val made = drawnPage(600, 800, tilted = false, background = 0xFFF7F5F0.toInt())
            val file = File.createTempFile("made-page-$i-", ".jpg").apply { deleteOnExit() }
            file.outputStream().use { made.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val thumb = Bitmap.createScaledBitmap(made, 54, 72, true).asImageBitmap()
            vm.pageQueue.made[pages[i].key] = io.github.artcraftandroid.pdfcraft.ui.ScanMadePage(vm.pageQueue.keyOf(pages[i]), file, thumb, 0, 0, false, null, null)
        }
    }

    private fun noMadeTiles(vm: AppViewModel) {
        vm.pageQueue.made.clear()
        vm.scanLab.update(vm.scanLab.options.copy(preview = false))
    }

    @Test @Config(qualifiers = "en-w360dp-h640dp-xhdpi") fun cameraEnglish360() = camera("en_360x640")
    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi") fun cameraEnglish384() = camera("en_384x832")
    @Test @Config(qualifiers = "ar-w360dp-h640dp-xhdpi") fun cameraArabic360() = camera("ar_360x640")
    @Test @Config(qualifiers = "ar-w384dp-h832dp-xhdpi") fun cameraArabic384() = camera("ar_384x832")
    @Test @Config(qualifiers = "fr-w360dp-h640dp-xhdpi") fun cameraFrench360() = camera("fr_360x640")
    @Test @Config(qualifiers = "de-w360dp-h640dp-xhdpi") fun cameraGerman360() = camera("de_360x640")

    @Test @Config(qualifiers = "en-w360dp-h640dp-xhdpi") fun cropEnglish360() = crop("en_360x640")
    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi") fun cropEnglish384() = crop("en_384x832")
    @Test @Config(qualifiers = "ar-w360dp-h640dp-xhdpi") fun cropArabic360() = crop("ar_360x640")
    @Test @Config(qualifiers = "ar-w384dp-h832dp-xhdpi") fun cropArabic384() = crop("ar_384x832", listOf(ThemeChoice.Light))
    @Test @Config(qualifiers = "fr-w360dp-h640dp-xhdpi") fun cropFrench360() = crop("fr_360x640", listOf(ThemeChoice.Light))
    @Test @Config(qualifiers = "de-w360dp-h640dp-xhdpi") fun cropGerman360() = crop("de_360x640", listOf(ThemeChoice.Light))

    @Test @Config(qualifiers = "en-w360dp-h640dp-xhdpi") fun stepsEnglish360() = steps("en_360x640")
    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi") fun stepsEnglish384() = steps("en_384x832")
    @Test @Config(qualifiers = "ar-w360dp-h640dp-xhdpi") fun stepsArabic360() = steps("ar_360x640")
    @Test @Config(qualifiers = "ar-w384dp-h832dp-xhdpi") fun stepsArabic384() = steps("ar_384x832", listOf(ThemeChoice.Light))
    @Test @Config(qualifiers = "fr-w360dp-h640dp-xhdpi") fun stepsFrench360() = steps("fr_360x640", listOf(ThemeChoice.Light))
    @Test @Config(qualifiers = "de-w360dp-h640dp-xhdpi") fun stepsGerman360() = steps("de_360x640", listOf(ThemeChoice.Light))

    companion object {
        /** A page drawn in code: white paper with grey text lines, on [background], optionally tilted. */
        fun drawnPage(w: Int, h: Int, tilted: Boolean, background: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(background)
            val quad = if (tilted) floatArrayOf(0.20f, 0.14f, 0.82f, 0.18f, 0.79f, 0.86f, 0.17f, 0.82f)
            else floatArrayOf(0.08f, 0.06f, 0.92f, 0.06f, 0.92f, 0.94f, 0.08f, 0.94f)
            fun x(k: Int) = quad[2 * k] * w
            fun y(k: Int) = quad[2 * k + 1] * h
            val paper = Path().apply {
                moveTo(x(0), y(0)); lineTo(x(1), y(1)); lineTo(x(2), y(2)); lineTo(x(3), y(3)); close()
            }
            canvas.drawPath(paper, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF7F5F0.toInt() })
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF50505A.toInt(); strokeWidth = h / 160f }
            // text lines between the page's left and right sides
            for (i in 1..14) {
                val t = 0.08f + i * 0.058f
                if (t > 0.92f) break
                val lx = x(0) + (x(3) - x(0)) * t
                val ly = y(0) + (y(3) - y(0)) * t
                val rx = x(1) + (x(2) - x(1)) * t
                val ry = y(1) + (y(2) - y(1)) * t
                val end = if (i % 4 == 0) 0.55f else 0.88f
                canvas.drawLine(lx + (rx - lx) * 0.1f, ly + (ry - ly) * 0.1f, lx + (rx - lx) * end, ly + (ry - ly) * end, ink)
            }
            return bitmap
        }
    }
}
