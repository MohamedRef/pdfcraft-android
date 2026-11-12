package io.github.artcraftandroid.pdfcraft.ui.scan

import android.content.Context
import android.media.AudioManager
import android.media.MediaActionSound
import android.os.SystemClock
import android.view.OrientationEventListener
import androidx.activity.compose.BackHandler
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the camera screen hands back to the activity. */
internal class ScanCameraActions(
    /**
     * A photo was written to the target file; [more] when the camera stays open for the next page.
     * [burst] holds every photo of a low-light burst (#297), the target first; empty for one photo.
     */
    val onCaptured: (file: File, capture: ScanCapture, more: Boolean, burst: List<File>) -> Unit,
    /** Done or Close: back to the scan (or out of it, when no page was taken). */
    val onClose: () -> Unit,
    /** Take this photo with the phone's camera app instead. */
    val onPhoneApp: () -> Unit,
    /** Pick photos from the gallery. */
    val onImport: () -> Unit,
    /** Ask for the camera permission (after the explanation). */
    val onAskPermission: () -> Unit,
    /** Open the app's page in the system settings, to allow the camera there. */
    val onOpenSettings: () -> Unit,
)

/**
 * The in-app scan camera (#294): a 4:3 viewfinder showing the whole frame the photo will have,
 * the shutter, the light, the last page taken with the page count, Done, Import and the phone's
 * camera app. Photos are taken at the camera's best still quality, focused first, a third of a
 * stop under so white paper keeps its faint marks, and written into the file the scan asked for,
 * so everything after the shot is as it was with the phone's camera app.
 */
@Composable
internal fun ScanCameraScreen(model: ScanCameraModel, pages: List<ImageBitmap>, actions: ScanCameraActions, book: ScanBookController? = null) {
    val file = model.target ?: return
    BackHandler { actions.onClose() }
    // the permission may have been granted in the system settings meanwhile
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) model.recheck() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        when (model.route) {
            ScanCaptureRoute.InApp -> CameraView(file, model.retaking, pages, actions, model.liveOutline, model, book)
            else -> NoCameraView(model.route, actions)
        }
    }
}

/** The screen without the camera: the explanation before the permission, a refusal, or a phone without a camera. */
@Composable
private fun NoCameraView(route: ScanCaptureRoute, actions: ScanCameraActions) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        val message = when (route) {
            ScanCaptureRoute.AskPermission -> R.string.scan_camera_rationale
            ScanCaptureRoute.ImportOnly -> R.string.scan_camera_no_camera
            else -> R.string.scan_camera_denied
        }
        Text(stringResource(message), color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        if (route == ScanCaptureRoute.AskPermission) {
            TextButton(onClick = actions.onAskPermission) { Text(stringResource(R.string.scan_camera_continue), color = Color.White, fontSize = 16.sp) }
        }
        TextButton(onClick = actions.onImport) { Text(stringResource(R.string.scan_camera_import), color = Color.White, fontSize = 16.sp) }
        if (route == ScanCaptureRoute.Denied) {
            TextButton(onClick = actions.onOpenSettings) { Text(stringResource(R.string.scan_camera_open_settings), color = Color.White, fontSize = 16.sp) }
        }
        TextButton(onClick = actions.onClose) { Text(stringResource(R.string.common_cancel), color = Color.White, fontSize = 16.sp) }
    }
}

/** The camera, once allowed. */
@Composable
private fun CameraView(file: File, retaking: Boolean, pages: List<ImageBitmap>, actions: ScanCameraActions, live: Boolean, model: ScanCameraModel, book: ScanBookController?) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    // the screen stays on while the camera shows
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var failed by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(ScanCameraState()) }
    var turn by remember { mutableIntStateOf(0) }
    var exposure by remember { mutableStateOf(0 to 0) }
    var shutterAt by remember { mutableStateOf(0L) }
    val transformer = remember { MutableCoordinateTransformer() }
    // the live outline (#295): the analyzer's answers, smoothed, and the advice under the viewfinder
    val tracker = remember { ScanLiveTracker() }
    val holder = remember { ScanLiveHintHolder() }
    var outline by remember { mutableStateOf<ScanLiveOutline?>(null) }
    var hint by remember { mutableStateOf<ScanLiveHint?>(null) }
    var lastLuma by remember { mutableStateOf(255f) }
    var lumaSeen by remember { mutableStateOf(false) }
    var lastCutOff by remember { mutableStateOf(false) }
    // auto-capture (#296): needs the live outline; the gyroscope says whether the phone is still
    val autoOn = live && model.autoCapture
    var auto by remember { mutableStateOf(ScanAutoState()) }
    var lastSignature by remember { mutableStateOf<FloatArray?>(null) }
    var tick by remember { mutableStateOf(0L) }
    val stillness = remember { ScanStillness() }
    // turned off mid-countdown: no ring left behind (a photo in progress ends as usual)
    LaunchedEffect(autoOn) { if (!autoOn && auto.phase != ScanAutoPhase.Capturing) auto = ScanAutoState() }
    DisposableEffect(autoOn) {
        val sensors = context.getSystemService(android.hardware.SensorManager::class.java)
        val gyro = sensors?.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                stillness.add(SystemClock.elapsedRealtime(), e.values[0], e.values[1], e.values[2])
            }

            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
        }
        if (autoOn && gyro != null) sensors.registerListener(listener, gyro, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        if (autoOn) Diag.event(DiagArea.Scan, "camera_auto", "gyroscope" to (gyro != null))
        onDispose { sensors?.unregisterListener(listener) }
    }
    val openedAt = remember { SystemClock.elapsedRealtime() }
    val analysisExecutor = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }
    val analyzer = remember {
        val main = ContextCompat.getMainExecutor(context)
        ScanLiveAnalyzer { d ->
            main.execute {
                outline = tracker.update(d)
                lastLuma = d.meanLuma
                lumaSeen = true
                lastCutOff = d.found && d.cutOff
                if (d.found) lastSignature = d.signature
            }
        }
    }
    val analysis = remember { scanLiveAnalysis() }
    DisposableEffect(analyzer) {
        onDispose {
            analysis.clearAnalyzer()
            analysisExecutor.shutdown()
            analyzer.logSummary(SystemClock.elapsedRealtime() - openedAt)
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    DisposableEffect(sound) { onDispose { sound.release() } }

    val ratio = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    // the sensor's sensitivity from the preview's capture results, for low-light merge (#297); -1 when not reported
    val iso = remember { java.util.concurrent.atomic.AtomicInteger(-1) }
    val preview = remember { scanPreviewWithIso(ratio.build(), iso).apply { setSurfaceProvider { surfaceRequest = it } } }
    var bursting by remember { mutableStateOf(false) }
    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(JPEG_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setResolutionSelector(ratio.setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build())
            .build()
    }

    // the back main camera, the preview and the still sharing one field of view
    LaunchedEffect(owner) {
        val provider = runCatching { ProcessCameraProvider.awaitInstance(context) }.getOrNull()
        if (provider == null) {
            failed = true
            Diag.event(DiagArea.Scan, "camera_failed", "stage" to "provider")
            return@LaunchedEffect
        }
        val group = UseCaseGroup.Builder()
            .setViewPort(ViewPort.Builder(android.util.Rational(3, 4), android.view.Surface.ROTATION_0).setScaleType(ViewPort.FIT).build())
            .addUseCase(preview).addUseCase(capture)
            .apply {
                if (live) {
                    analysis.setAnalyzer(analysisExecutor, analyzer)
                    addUseCase(analysis)
                }
            }
            .build()
        try {
            val bound = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
            camera = bound
            // no digital zoom and no ultra-wide: its distortion bends a page's edges
            bound.cameraControl.setZoomRatio(1f)
            val es = bound.cameraInfo.exposureState
            if (es.isExposureCompensationSupported) {
                val step = es.exposureCompensationStep.toDouble()
                val index = scanExposureIndex(step, es.exposureCompensationRange.lower..es.exposureCompensationRange.upper)
                bound.cameraControl.setExposureCompensationIndex(index)
                exposure = index to (step * 1000).toInt()
            }
            val size = capture.resolutionInfo?.resolution
            Diag.event(
                DiagArea.Scan, "camera_ready", "mp10" to scanMegapixelsTenths(size?.width ?: 0, size?.height ?: 0),
                "exposure_index" to exposure.first, "exposure_step_milli" to exposure.second, "torch_available" to bound.cameraInfo.hasFlashUnit(),
            )
        } catch (e: Exception) {
            failed = true
            Diag.failure(DiagArea.Scan, "camera_bind", e)
            return@LaunchedEffect
        }
        try {
            awaitCancellation()
        } finally {
            provider.unbind(preview, capture, analysis)
            camera = null
        }
    }

    // photos taken with the phone turned are stored upright
    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                val t = scanDeviceTurn(degrees) ?: return
                if (t != turn) {
                    turn = t
                    capture.targetRotation = scanTargetRotation(t)
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    fun focus(point: MeteringPoint, onEnd: (Boolean) -> Unit) {
        val control = camera?.cameraControl ?: return onEnd(false)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(ScanCameraTiming.TAP_FOCUS_HOLD_MS, TimeUnit.MILLISECONDS).build()
        val future = control.startFocusAndMetering(action)
        future.addListener({ onEnd(runCatching { future.get().isFocusSuccessful }.getOrDefault(false)) }, ContextCompat.getMainExecutor(context))
    }

    fun centre(): MeteringPoint? = surfaceRequest?.resolution?.let { SurfaceOrientedMeteringPointFactory(it.width.toFloat(), it.height.toFloat()).createPoint(it.width / 2f, it.height / 2f) }

    lateinit var dispatch: (ScanCameraEvent) -> Unit

    /** One photo, or a burst of [ScanMergeRules.FRAMES] in a row when the scene is dark (#297); the burst counts as one capture. */
    fun takePicture() {
        val started = SystemClock.elapsedRealtime()
        val why = scanBurstWhy(model.merge, model.memoryClassMb, if (live && lumaSeen) lastLuma else null, iso.get().takeIf { it > 0 })
        val files = if (why.burst) scanBurstFiles(file) else listOf(file)
        // the focus and metering of the shutter's focus step hold for the whole burst (4 s auto-cancel)
        bursting = why.burst
        val saved = mutableListOf<File>()

        fun ended() {
            bursting = false
            val ok = saved.isNotEmpty() && saved.first() == file && scanCaptureUsable(file.isFile, file.length())
            if (!ok) {
                files.forEach { it.delete() }
                auto = auto.reduce(ScanAutoEvent.CaptureEnded(false, null, SystemClock.elapsedRealtime())).first
                dispatch(ScanCameraEvent.CaptureEnded(false, SystemClock.elapsedRealtime()))
                return
            }
            (files - saved.toSet()).forEach { it.delete() }
            val size = capture.resolutionInfo?.resolution
            val w = size?.width ?: 0
            val h = size?.height ?: 0
            val now = SystemClock.elapsedRealtime()
            val facts = ScanCapture(
                source = ScanCaptureRoute.InApp.id, megapixelsTenths = scanMegapixelsTenths(w, h), width = w, height = h,
                exposureIndex = exposure.first, exposureStepMilli = exposure.second, torch = torch, focused = state.lastFocusOk,
                rotation = turn, captureMs = now - (if (shutterAt > 0) shutterAt else started),
                live = live, liveFound = outline?.points != null, liveStable = outline?.stable == true,
                liveHint = hint?.id ?: "none", previewQuad = outline?.points,
                autoOn = autoOn, autoShot = auto.phase == ScanAutoPhase.Capturing && auto.auto,
                merge = if (why.burst) ScanMergeFacts(why.id, frames = saved.size, burstMs = now - started) else null,
            )
            if (!why.burst) Diag.event(DiagArea.Scan, "camera_merge", "why" to why.id, "iso" to iso.get(), "luma" to (if (lumaSeen) lastLuma.toDouble() else -1.0))
            auto = auto.reduce(ScanAutoEvent.CaptureEnded(true, lastSignature, now)).first
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            // the photo goes to the scan before Done (if pressed meanwhile) closes the screen
            actions.onCaptured(file, facts, !retaking && !state.doneQueued, if (why.burst) saved.toList() else emptyList())
            dispatch(ScanCameraEvent.CaptureEnded(true, SystemClock.elapsedRealtime()))
        }

        fun shoot(i: Int) {
            val target = files[i]
            capture.takePicture(
                ImageCapture.OutputFileOptions.Builder(target).build(), ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (scanCaptureUsable(target.isFile, target.length())) saved += target else target.delete()
                        if (i == 0 && saved.isEmpty()) return ended()
                        if (i + 1 < files.size) shoot(i + 1) else ended()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        target.delete()
                        Diag.event(DiagArea.Scan, "camera_capture_failed", "code" to exception.imageCaptureError, "frame" to i)
                        // a later frame of a burst failing keeps the frames already taken
                        ended()
                    }
                },
            )
            if (i == 0) playShutter(context, sound)
        }
        shoot(0)
    }

    dispatch = { event ->
        val (next, effects) = state.reduce(event)
        state = next
        for (effect in effects) when (effect) {
            ScanCameraEffect.FocusCentre -> centre()?.let { p -> focus(p) { ok -> dispatch(ScanCameraEvent.FocusEnded(ok, SystemClock.elapsedRealtime())) } }
                ?: dispatch(ScanCameraEvent.FocusEnded(false, SystemClock.elapsedRealtime()))
            ScanCameraEffect.Capture -> takePicture()
            ScanCameraEffect.ShowError -> error = true
            ScanCameraEffect.Finish -> actions.onClose()
        }
    }

    // a capture waiting for focus goes ahead after FOCUS_WAIT_MS
    LaunchedEffect(state.phase, state.captureQueued) {
        if (state.phase == ScanCameraPhase.Focusing && state.captureQueued) {
            withTimeoutOrNull(ScanCameraTiming.FOCUS_WAIT_MS + 200) {
                while (true) {
                    delay(50)
                    dispatch(ScanCameraEvent.Tick(SystemClock.elapsedRealtime()))
                }
            }
        }
    }
    // the advice, re-chosen ten times a second (the outline itself follows the frames)
    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        while (true) {
            val now = SystemClock.elapsedRealtime()
            tick = now
            val shown = tracker.at(now).also { if (it.points == null) outline = null }
            val missing = if (shown.points != null) 0L else now - (tracker.lastFoundAt ?: openedAt)
            val wanted = scanLiveHintFor(ScanLiveScene(shown.points, outline?.stable == true, lastLuma, lastCutOff, missing))
            hint = holder.update(wanted, now)
            if (autoOn) {
                val before = auto.phase
                val (next, shoot) = auto.reduce(ScanAutoEvent.Seen(ScanAutoInputs(now, hint == ScanLiveHint.Ready, stillness.still(now), shown.points != null, lastSignature)))
                auto = next
                if (next.phase != before) Diag.event(DiagArea.Scan, "camera_auto_phase", "phase" to next.phase.id)
                if (shoot && state.shutterReady && camera != null && !model.merging) {
                    shutterAt = now
                    dispatch(ScanCameraEvent.Shutter(now))
                } else if (shoot) {
                    auto = ScanAutoState()
                }
            }
            delay(if (auto.phase == ScanAutoPhase.Arming) 33 else 100)
        }
    }
    LaunchedEffect(error) {
        if (error) {
            delay(3_000)
            error = false
        }
    }
    // a new target is a new page: the screen is ready for it
    LaunchedEffect(file) { if (state.phase == ScanCameraPhase.Finished) state = ScanCameraState() }

    Column(Modifier.fillMaxSize()) {
        // top: close, the phone's camera app, the light
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BarIcon(LucideIcons.X, stringResource(R.string.common_cancel), actions.onClose, tint = Color.White)
            Spacer(Modifier.weight(1f))
            if (live) {
                // auto-capture on or off, remembered (#296)
                TextButton(onClick = { model.updateAutoCapture(!model.autoCapture) }, modifier = Modifier.semantics { this.selected = model.autoCapture }) {
                    Text(stringResource(R.string.scan_auto_setting), color = if (model.autoCapture) Color(0xFF81C784) else Color.White, fontSize = 14.sp)
                }
            }
            TextButton(onClick = actions.onPhoneApp) { Text(stringResource(R.string.scan_camera_phone_app), color = Color.White, fontSize = 14.sp) }
            // book mode (#303): the same switch as on the scan screen
            if (book != null) ScanBookCameraToggle(book)
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                BarIcon(
                    LucideIcons.Sun, stringResource(if (torch) R.string.scan_camera_torch_off else R.string.scan_camera_torch_on),
                    {
                        torch = !torch
                        camera?.cameraControl?.enableTorch(torch)
                    },
                    tint = if (torch) Color(0xFFFFD54F) else Color.White, selected = torch,
                )
            }
        }
        // the viewfinder: the whole 4:3 frame, letterboxed
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val request = surfaceRequest
            val viewfinderLabel = stringResource(R.string.scan_camera_viewfinder)
            Box(Modifier.aspectRatio(3f / 4f).semantics { contentDescription = viewfinderLabel }) {
                if (request != null && !failed) {
                    var ring by remember { mutableStateOf<Offset?>(null) }
                    CameraXViewfinder(
                        request,
                        Modifier.fillMaxSize().pointerInput(request) {
                            detectTapGestures { tap ->
                                val point = with(transformer) { tap.transform() }
                                val p = SurfaceOrientedMeteringPointFactory(request.resolution.width.toFloat(), request.resolution.height.toFloat()).createPoint(point.x, point.y)
                                ring = tap
                                dispatch(ScanCameraEvent.TapFocus(SystemClock.elapsedRealtime()))
                                focus(p) { ok -> dispatch(ScanCameraEvent.FocusEnded(ok, SystemClock.elapsedRealtime())) }
                                scope.launch {
                                    delay(900)
                                    ring = null
                                }
                            }
                        },
                        coordinateTransformer = transformer,
                        contentScale = ContentScale.Fit,
                    )
                    if (live) ScanLiveOverlay(outline, androidx.compose.ui.graphics.Color(0xFF64B5F6), Modifier.fillMaxSize())
                    ring?.let { at ->
                        Box(
                            Modifier.padding(start = with(density) { (at.x - 28.dp.toPx()).coerceAtLeast(0f).toDp() }, top = with(density) { (at.y - 28.dp.toPx()).coerceAtLeast(0f).toDp() })
                                .size(56.dp).border(2.dp, Color.White, CircleShape),
                        )
                    }
                }
                // book mode (#303): a line down the middle of the frame for the book's spine
                if (book?.on == true && !failed) ScanBookSpineGuide()
                if (failed) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.scan_camera_unavailable), color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
                        TextButton(onClick = actions.onPhoneApp) { Text(stringResource(R.string.scan_camera_phone_app), color = Color.White, fontSize = 16.sp) }
                    }
                }
            }
        }
        // the page outline's advice (#295), or the error of a failed capture
        Box(Modifier.fillMaxWidth().height(32.dp).semantics { liveRegion = LiveRegionMode.Polite }, contentAlignment = Alignment.Center) {
            val h = hint
            if (error) {
                Text(stringResource(R.string.scan_camera_failed), color = Color.White, fontSize = 14.sp)
            } else if (bursting || model.merging) {
                Text(stringResource(R.string.scan_merge_working), color = Color.White, fontSize = 14.sp)
            } else if (autoOn && auto.phase == ScanAutoPhase.Arming) {
                // the countdown can be skipped: this page is then left until a new one is in view
                Text(
                    stringResource(R.string.scan_auto_skip), color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.clickable(role = Role.Button) {
                        auto = auto.reduce(ScanAutoEvent.Cancel(lastSignature, SystemClock.elapsedRealtime())).first
                        Diag.event(DiagArea.Scan, "camera_auto_phase", "phase" to auto.phase.id, "skipped" to true)
                    }.padding(vertical = 8.dp),
                )
            } else if (autoOn && auto.phase == ScanAutoPhase.WaitingForNewPage) {
                Text(stringResource(R.string.scan_auto_next), color = Color.White, fontSize = 14.sp)
            } else if (autoOn && !model.autoTipShown) {
                Text(stringResource(R.string.scan_auto_tip), color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
                LaunchedEffect(Unit) {
                    delay(4_000)
                    model.markAutoTipShown()
                }
            } else if (live && h != null) {
                Text(stringResource(scanLiveHintText(h)), color = if (h == ScanLiveHint.Ready) Color(0xFF81C784) else Color.White, fontSize = 14.sp)
            }
        }
        // bottom: the last page and Import, the shutter, Done
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                val last = pages.lastOrNull()
                if (last != null) {
                    val label = stringResource(R.string.scan_camera_last_page, pages.size)
                    Image(last, label, Modifier.size(48.dp, 64.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, Color.White, RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
                } else {
                    TextButton(onClick = actions.onImport) { Text(stringResource(R.string.scan_camera_import), color = Color.White, fontSize = 14.sp) }
                }
            }
            val shutterLabel = stringResource(R.string.scan_camera_shutter)
            val ready = state.shutterReady && camera != null && !failed && !bursting && !model.merging
            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                // the countdown before an automatic shot, around the shutter
                if (autoOn && auto.phase == ScanAutoPhase.Arming) {
                    CircularProgressIndicator(progress = { auto.armProgress(tick) }, modifier = Modifier.size(84.dp), color = Color(0xFF81C784), strokeWidth = 4.dp)
                }
                Box(
                    Modifier.size(72.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(6.dp).clip(CircleShape)
                        .background(if (ready) Color.White else Color.Gray)
                        .clickable(enabled = ready, role = Role.Button) {
                            shutterAt = SystemClock.elapsedRealtime()
                            // by hand: one photo, and auto-capture waits for the next page afterwards
                            auto = auto.reduce(ScanAutoEvent.Shutter(shutterAt)).first
                            dispatch(ScanCameraEvent.Shutter(shutterAt))
                        }
                        .semantics { contentDescription = shutterLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.phase == ScanCameraPhase.Capturing || state.captureQueued || bursting || model.merging) CircularProgressIndicator(Modifier.size(32.dp), color = Color.Black, strokeWidth = 3.dp)
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (!retaking && pages.isNotEmpty()) {
                    TextButton(onClick = { dispatch(ScanCameraEvent.Done(SystemClock.elapsedRealtime())) }) {
                        Text(stringResource(R.string.scan_camera_done, pages.size), color = Color.White, fontSize = 16.sp)
                    }
                } else if (pages.isNotEmpty()) {
                    TextButton(onClick = actions.onImport) { Text(stringResource(R.string.scan_camera_import), color = Color.White, fontSize = 14.sp) }
                }
            }
        }
    }
}

/** The camera's shutter sound, unless the phone is silent or on vibrate. */
private fun playShutter(context: Context, sound: MediaActionSound) {
    val audio = context.getSystemService(AudioManager::class.java) ?: return
    if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
    runCatching { sound.play(MediaActionSound.SHUTTER_CLICK) }
}

/** The still's JPEG quality: high enough that the scan's own encode, not this one, sets the file size. */
private const val JPEG_QUALITY = 97

/** The preview, reporting the sensor's sensitivity of each frame into [iso] through Camera2 interop (#297). */
@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
private fun scanPreviewWithIso(selector: ResolutionSelector, iso: java.util.concurrent.atomic.AtomicInteger): Preview {
    val builder = Preview.Builder().setResolutionSelector(selector)
    androidx.camera.camera2.interop.Camera2Interop.Extender(builder).setSessionCaptureCallback(
        object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: android.hardware.camera2.CameraCaptureSession,
                request: android.hardware.camera2.CaptureRequest,
                result: android.hardware.camera2.TotalCaptureResult,
            ) {
                result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)?.let { iso.set(it) }
            }
        },
    )
    return builder.build()
}
