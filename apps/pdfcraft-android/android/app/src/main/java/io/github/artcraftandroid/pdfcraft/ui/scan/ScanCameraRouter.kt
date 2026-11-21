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
import io.github.artcraftandroid.pdfcraft.ui.deleteScanPage
import io.github.artcraftandroid.pdfcraft.ui.discardScan
import io.github.artcraftandroid.pdfcraft.ui.onScanPhoto
import io.github.artcraftandroid.pdfcraft.ui.takeScanPhoto

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
        onClose = { close("close") },
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
        onReview = { review() },
        onUndo = { undo() },
        onDone = { close("done") },
    )

    /**
     * Done, or Close (the ✕ and Android's Back): the camera closes. With pages, both lead to the
     * document screen (#460); Done is the button that says so, Close the way out that loses
     * nothing. [action] is `done` or `close`, for the log (#780).
     */
    private fun close(action: String) {
        val m = model()
        val merging = m.merging
        val pending = m.session.pending
        val undone = m.session.undone
        m.hide()
        val v = vm()
        val lab = v.scanLab.options
        if (lab.cameraButtons) Diag.event(DiagArea.Scan, "camera_action", *scanCameraActionDiag(action, "ok", ScanCameraBlock.None, v.scanPages.size, pending))
        // closed with pages taken: the document screen shows them (#460)
        if (!merging && v.scanPages.isNotEmpty() && scanDocumentAfterCamera(lab.steps, v.scanRetaking, null)) v.scanSteps.showDocument("camera", v.scanPages.size)
        // the photo not taken: with no page yet, the scan ends (a burst being merged still goes to the scan)
        if (!merging) v.onScanPhoto(false)
        // every photo was undone (#780): the scan's name and steps do not stay for the next scan
        if (!merging && scanCameraEndsEmpty(lab.cameraButtons && lab.cameraBar, v.scanPages.size, pending, undone)) v.discardScan()
    }

    /**
     * Review pages (#780): the camera steps aside without ending anything, and the scan shows its
     * newest page in Enhance, where a page is checked, retaken, turned or deleted and the others
     * are a swipe or one step back. Add page, there or on the pages, brings this camera back; the
     * photos Undo knows are kept for that.
     */
    private fun review() {
        val m = model()
        val v = vm()
        val pages = v.scanPages.size
        if (m.merging || pages == 0) {
            Diag.event(DiagArea.Scan, "camera_action", *scanCameraActionDiag("review", "blocked", if (pages == 0) ScanCameraBlock.NoPages else ScanCameraBlock.Busy, pages, m.session.pending))
            return
        }
        Diag.event(DiagArea.Scan, "camera_action", *scanCameraActionDiag("review", "ok", ScanCameraBlock.None, pages, m.session.pending))
        m.hide(keepSession = true)
        // the camera opened from the pages (Add page): the newest page, not the grid again
        if (v.scanLab.options.steps && v.scanSteps.document) v.scanSteps.openEnhance(pages, pages)
        // the photo not taken is let go of; the pages stay
        v.onScanPhoto(false)
    }

    /**
     * Undo (#780): the last photo this camera took comes out of the scan, by the same path as
     * Delete on a page, so its file goes too. A book photo's two pages go together. The camera
     * stays open, also when that was the only page: the scan is then empty until the next photo.
     */
    private fun undo() {
        val m = model()
        val v = vm()
        val paths = v.scanPages.map { it.file.path }
        val target = if (m.merging) null else m.session.undoTarget(paths)
        if (target == null) {
            val why = if (m.merging || m.session.pending > 0) ScanCameraBlock.Busy else if (paths.isEmpty()) ScanCameraBlock.NoPages else ScanCameraBlock.NothingToUndo
            Diag.event(DiagArea.Scan, "camera_action", *scanCameraActionDiag("undo", "blocked", why, paths.size, m.session.pending))
            return
        }
        val gone = scanUndoIndexes(paths, target)
        for (i in gone) v.deleteScanPage(i)
        m.sessionUndone(target)
        Diag.event(DiagArea.Scan, "camera_action", *scanCameraActionDiag("undo", "ok", ScanCameraBlock.None, v.scanPages.size, m.session.pending, removed = gone.size))
    }

    /** The camera screen, over the app while a photo is wanted. */
    @Composable
    fun Screen() {
        val m = model()
        // pages made at full size for Save wait while the camera is open (#504)
        val open = m.target != null
        androidx.compose.runtime.LaunchedEffect(open) { vm().fullPages.camera(open) }
        if (m.target != null) {
            ScanCameraScreen(
                m, vm().scanPages.map { scanCameraPageThumb(vm().scanLab.options.firstPicture, vm().pageQueue.thumbOf(it), it.thumb) }, actions, vm().scanBook,
                vm().scanLab.options.cameraBar, vm().scanLab.options.liveTurned, vm().scanLab.options.autoEased,
                // Review pages, Undo and Done each a button (#780); the pages' photos tell which are this camera's
                buttons = vm().scanLab.options.cameraButtons, pagePaths = vm().scanPages.map { it.file.path },
            )
        }
    }

    /** Import on the document screen (#460): the photo picker straight away, the camera not opened. */
    fun importPhotos() = launch { importer.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    private fun launchPermission() = launch { permission.launch(Manifest.permission.CAMERA) }

    private fun launchPhoneCamera(file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        launch(onFail = { vm().onScanPhoto(false) }) { phoneCamera.launch(uri) }
    }

    /** The photo (at the target file the scan asked for) goes to the scan, then the next photo or the camera closes. */
    private fun toScan(more: Boolean) {
        val v = vm()
        // the photo's file, for Undo and for what Done waits for (#780): a retake or the last photo before the camera closes is neither
        val handed = v.shotFile?.takeIf { more && !v.scanRetaking && v.scanLab.options.cameraButtons && v.scanLab.options.cameraBar }
        // One page mode: Adjust borders opens on the page once it is in the scan (#458)
        if (!more) {
            val lab = v.scanLab.options
            val mode = model().modeShown(v.scanBook)
            v.scanSteps.pending = scanBordersAfterCapture(lab.steps, lab.cameraBar, mode, v.scanRetaking, v.scanSteps.retakeFromBorders)
            v.scanSteps.pendingMode = scanCameraModeId(lab.cameraBar, mode)
            // Done in Many pages or Book: the document screen, with the pages in a grid (#460)
            if (scanDocumentAfterCamera(lab.steps, v.scanRetaking, v.scanSteps.pending)) v.scanSteps.showDocument("camera", v.scanPages.size + 1)
        }
        v.onScanPhoto(true)
        if (handed != null) model().sessionHanded(handed.path, android.os.SystemClock.elapsedRealtime())
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
    /** Pictures shared from another app (#617): added exactly as an import, as `shared`. */
    fun importShared(uris: List<Uri>) = imported(uris, ScanStart.Shared)

    private fun imported(uris: List<Uri>, how: ScanStart = ScanStart.Import) {
        val v = vm()
        if (uris.isEmpty()) {
            // the picker opened by Scan from photos was closed with nothing chosen: no empty scan stays behind
            if (v.scanSkipCamera && v.scanPages.isEmpty()) v.tools.toolScreen = null
            v.scanSkipCamera = false
            return
        }
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
                        // a shared file may be anything that says it is a picture (#617): only a readable one is kept
                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        if (f.isFile) android.graphics.BitmapFactory.decodeFile(f.path, bounds)
                        f.takeIf { scanCaptureUsable(it.isFile, it.length()) && scanSharedReadable(bounds.outWidth, bounds.outHeight) } ?: run { f.delete(); null }
                    }.getOrNull()
                }
            }
            Diag.event(DiagArea.Scan, "camera_import", "picked" to picked.size, "copied" to files.size, "how" to how.id)
            v.scanSkipCamera = false
            val refused = picked.size - files.size
            if (how == ScanStart.Shared && refused > 0) {
                v.tell(if (files.isEmpty()) io.github.artcraftandroid.pdfcraft.text(R.string.scan_shared_none) else io.github.artcraftandroid.pdfcraft.plural(R.plurals.scan_shared_left_out, refused, refused))
            }
            if (files.isEmpty()) {
                if (v.scanPages.isEmpty()) v.tools.toolScreen = null
                v.onScanPhoto(false)
                return@launch
            }
            // one photo behaves as One page: Adjust borders opens on it (#458)
            v.scanSteps.pending = scanBordersAfterImport(v.scanLab.options.steps, files.size, v.scanRetaking, v.scanSteps.retakeFromBorders)
            v.scanSteps.pendingMode = "import"
            // several photos: the document screen offers to check their borders in one pass (#515)
            val lab = v.scanLab.options
            if (!v.scanRetaking && scanBatchOffers(lab.batchBorders, lab.steps, files.size)) {
                v.scanBatch.offerBefore = v.scanPages.map { it.key }.toSet()
                v.scanBatch.offer = files.size
            }
            // several photos: the document screen shows them, as after Many pages (#460)
            if (scanDocumentAfterCamera(v.scanLab.options.steps, v.scanRetaking, v.scanSteps.pending)) v.scanSteps.showDocument("import", v.scanPages.size + files.size)
            for (f in files) {
                ScanCaptures.put(f.path, ScanCapture(if (how == ScanStart.Shared) "shared" else "import"))
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

/**
 * The picture of a page on the camera's pages button (#785): the page as made, or its first
 * picture, once there is one ([made]); until then, and with the testing option off, the photo as
 * taken ([photo]), as before.
 */
internal fun <T> scanCameraPageThumb(firstPicture: Boolean, made: T?, photo: T): T = if (firstPicture && made != null) made else photo
