package io.github.artcraftandroid.pdfcraft.ui.scan

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where each photo Scan asks for is taken (#294): PdfCraft's camera screen or the phone's camera
 * app, after the camera permission when it is needed. Declaring CAMERA means the phone's camera
 * app, too, can only be started once the permission is granted (Android refuses IMAGE_CAPTURE to
 * an app that declares it without having it), so the permission is asked for here before either.
 * Made while the activity is created: it registers the activity results it needs.
 */
internal class ScanCameraRouter(private val activity: ComponentActivity, private val vm: () -> AppViewModel, private val model: () -> ScanCameraModel) {
    /** The photo waiting for the permission's answer. */
    private var pending: File? = null

    private val phoneCamera = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val v = vm()
        val f = v.shotFile
        if (ok && f != null && !v.editor.imageShot && scanCaptureUsable(f.isFile, f.length())) {
            val facts = ScanCapture(ScanCaptureRoute.PhoneApp.id)
            ScanCaptures.put(f.path, facts)
            Diag.event(DiagArea.Scan, "camera", *facts.diag())
        }
        v.onScanPhoto(ok)
    }

    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Diag.event(DiagArea.Scan, "camera_permission", "granted" to granted)
        val f = pending ?: return@registerForActivityResult
        pending = null
        route(f, refused = !granted)
    }

    private val importer = activity.registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris -> imported(uris) }

    /** Take the photo [file] the scan asked for (or the Add image photo, which always uses the phone's camera app). */
    fun route(file: File, refused: Boolean = false) {
        val v = vm()
        val m = model()
        val forImage = v.editor.imageShot
        val choice = if (forImage) ScanCameraChoice.PhoneApp else m.choice
        when (val r = scanCaptureRoute(m.hasCamera, m.granted, refused, choice)) {
            ScanCaptureRoute.PhoneApp -> {
                m.hide()
                launchPhoneCamera(file)
            }
            ScanCaptureRoute.AskPermission -> {
                pending = file
                // the scan explains first, on the camera screen; Add image asks straight away
                if (forImage) launchPermission() else show(file, r)
            }
            else -> if (forImage) {
                // no camera, or refused: the photo for Add image is given up on
                v.tell(activity.getString(R.string.scan_camera_image_unavailable))
                v.onScanPhoto(false)
            } else {
                show(file, r)
            }
        }
    }

    /** The camera screen for [file], logging the mode it opens in (#457). */
    private fun show(file: File, screen: ScanCaptureRoute) {
        val v = vm()
        val m = model()
        val bar = v.scanLab.options.cameraBar
        m.show(file, screen, v.scanRetaking, scanCameraModeId(bar, m.modeShown(v.scanBook)), bar)
    }

    /** The camera screen's actions. */
    val actions = ScanCameraActions(
        onCaptured = { file, facts, more, burst ->
            if (burst.size >= 2) {
                merged(file, facts, more, burst)
            } else {
                ScanCaptures.put(file.path, facts)
                Diag.event(DiagArea.Scan, "camera", *facts.diag())
                measureSharpness(file, facts)
                toScan(more)
            }
        },
        onClose = {
            val m = model()
            val merging = m.merging
            m.hide()
            // the photo not taken: with no page yet, the scan ends (a burst being merged still goes to the scan)
            if (!merging) vm().onScanPhoto(false)
        },
        onPhoneApp = {
            val f = model().target
            model().hide()
            Diag.event(DiagArea.Scan, "camera_phone_app", "from_screen" to true)
            if (f != null) launchPhoneCamera(f)
        },
        onImport = { launch { importer.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
        onAskPermission = { launchPermission() },
        onOpenSettings = {
            launch { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))) }
        },
    )

    /** The camera screen, over the app while a photo is wanted. */
    @Composable
    fun Screen() {
        val m = model()
        if (m.target != null) ScanCameraScreen(m, vm().scanPages.map { it.thumb }, actions, vm().scanBook, vm().scanLab.options.cameraBar)
    }

    private fun launchPermission() = launch { permission.launch(Manifest.permission.CAMERA) }

    private fun launchPhoneCamera(file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        launch(onFail = { vm().onScanPhoto(false) }) { phoneCamera.launch(uri) }
    }

    /** The photo (at the target file the scan asked for) goes to the scan, then the next photo or the camera closes. */
    private fun toScan(more: Boolean) {
        val v = vm()
        v.onScanPhoto(true)
        // the camera stays open for the next page, in a file of its own
        if (more) v.takeScanPhoto() else model().hide()
    }

    /**
     * A low-light burst (#297): the page is found on each photo with the still detector, the
     * photos are merged by `scan_merge_frames` (#389) into one straightened page, and that page
     * goes to the scan in place of the first photo, marked so its edges are not searched for again.
     * When too few pages are found or the merge fails, the first photo goes to the scan as usual.
     * The camera holds its shutter meanwhile, so the scan's next target is not asked for early.
     */
    private fun merged(file: File, facts: ScanCapture, more: Boolean, burst: List<File>) {
        val m = model()
        m.merging = true
        val lab = vm().scanLab.options
        activity.lifecycleScope.launch {
            val findStarted = android.os.SystemClock.elapsedRealtime()
            val corners = withContext(Dispatchers.Default) {
                burst.map { f ->
                    runCatching { io.github.artcraftandroid.pdfcraft.core.scanFindDocumentWith(f.path, lab.findOptions()) }.getOrNull()
                        ?.takeIf { it.found }?.corners?.takeIf { it.size == 8 }?.toFloatArray()
                }
            }
            val findMs = android.os.SystemClock.elapsedRealtime() - findStarted
            val frames = scanMergeFrames(burst, corners)
            val out = scanMergedFile(file)
            val mergeStarted = android.os.SystemClock.elapsedRealtime()
            val result = frames?.let { fr ->
                withContext(Dispatchers.Default) {
                    runCatching {
                        io.github.artcraftandroid.pdfcraft.core.scanMergeFrames(
                            io.github.artcraftandroid.pdfcraft.core.ScanMergeRequest(
                                fr.map { (f, c) -> io.github.artcraftandroid.pdfcraft.core.ScanMergeFrame(f.path, c.toList()) },
                                out.path, io.github.artcraftandroid.pdfcraft.core.ScanFormat.JPEG, null, lab.snapPaper,
                            ),
                        )
                    }.onFailure { e -> Diag.failure(DiagArea.Scan, "scan_merge_frames", e) }.getOrNull()
                }
            }
            val mergeMs = android.os.SystemClock.elapsedRealtime() - mergeStarted
            val written = result?.let { File(it.path) }?.takeIf { scanCaptureUsable(it.isFile, it.length()) }
            if (written == null) out.delete()
            val outcome = scanBurstOutcome(burst, written)
            val base = facts.merge ?: ScanMergeFacts("always")
            val mergeFacts = base.copy(
                found = corners.count { it != null }, merged = outcome.merged,
                used = result?.used?.toInt() ?: 0, rejectedBlur = result?.rejectedBlur?.toInt() ?: 0,
                rejectedAlign = result?.rejectedAlign?.toInt() ?: 0, psnrGainTenthsDb = result?.psnrGainTenthsDb?.toInt() ?: 0,
                findMs = findMs, mergeMs = mergeMs,
                fallback = when {
                    outcome.merged -> "none"
                    frames == null -> "few_found"
                    else -> "failed"
                },
            )
            val kept = facts.copy(merge = mergeFacts, previewQuad = if (outcome.merged) null else facts.previewQuad)
            Diag.event(DiagArea.Scan, "camera_merge", *mergeFacts.diag(), "setting" to (facts.mergeSetting ?: "none"), "chosen" to facts.mergeChosen, *(result?.timings.orEmpty().map { it.stage.removePrefix("scan_") + "_ms" to it.ms.toLong() }.toTypedArray()))
            outcome.delete.filter { it != outcome.keep }.forEach { it.delete() }
            ScanCaptures.put(outcome.keep.path, kept)
            Diag.event(DiagArea.Scan, "camera", *kept.diag())
            m.merging = false
            // the screen may have been closed meanwhile: the page still goes to the scan
            val v = vm()
            v.shotFile = outcome.keep
            if (!outcome.merged) measureSharpness(outcome.keep, kept)
            toScan(more && m.target != null)
        }
    }

    /**
     * The photo's sharpness (#296, #389), measured after the shot and only recorded: in the log
     * (`camera_sharpness`) and in a kept scan's page file. Never a reason to take it again, since
     * the measure does not see motion blur and its threshold comes from drawn pages.
     */
    private fun measureSharpness(file: File, facts: ScanCapture) {
        val corners = facts.previewQuad?.takeIf { it.size == 8 }?.toList() ?: listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        activity.lifecycleScope.launch {
            val r = withContext(Dispatchers.Default) { runCatching { io.github.artcraftandroid.pdfcraft.core.scanSharpness(file.path, corners) } }
            val s = r.getOrNull()
            if (s == null) {
                Diag.event(DiagArea.Scan, "camera_sharpness", "measured" to false)
                return@launch
            }
            val ms = s.timings.sumOf { it.ms.toLong() }
            Diag.event(
                DiagArea.Scan, "camera_sharpness", "measured" to true, "score" to s.score.toDouble(), "blurred_unverified" to s.blurredUnverified,
                "threshold" to s.threshold.toDouble(), "ms" to ms, "from_outline" to (facts.previewQuad != null), "auto_shot" to facts.autoShot,
            )
            ScanCaptures.of(file.path)?.let { ScanCaptures.put(file.path, it.copy(sharpness = s.score.toDouble(), sharpnessBlurredUnverified = s.blurredUnverified, sharpnessMs = ms)) }
        }
    }

    /** Photos picked to scan: copied next to the camera's, each through the same path as a photo taken. */
    private fun imported(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val v = vm()
        // a retake replaces one page
        val picked = if (v.scanRetaking) uris.take(1) else uris
        model().hide()
        activity.lifecycleScope.launch {
            val dir = File(activity.cacheDir, "camera").apply { mkdirs() }
            val files = withContext(Dispatchers.IO) {
                picked.mapNotNull { uri ->
                    runCatching {
                        val f = File(dir, "scan-${System.nanoTime()}.jpg")
                        activity.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                        f.takeIf { scanCaptureUsable(it.isFile, it.length()) } ?: run { f.delete(); null }
                    }.getOrNull()
                }
            }
            Diag.event(DiagArea.Scan, "camera_import", "picked" to picked.size, "copied" to files.size)
            if (files.isEmpty()) {
                v.onScanPhoto(false)
                return@launch
            }
            for (f in files) {
                ScanCaptures.put(f.path, ScanCapture("import"))
                v.shotFile = f
                v.onScanPhoto(true)
            }
        }
    }

    private inline fun launch(onFail: () -> Unit = {}, block: () -> Unit) {
        try {
            block()
        } catch (e: ActivityNotFoundException) {
            onFail()
            vm().tell(activity.getString(R.string.main_error_no_app))
        } catch (e: SecurityException) {
            onFail()
            vm().tell(activity.getString(R.string.main_error_not_allowed))
        }
    }
}
