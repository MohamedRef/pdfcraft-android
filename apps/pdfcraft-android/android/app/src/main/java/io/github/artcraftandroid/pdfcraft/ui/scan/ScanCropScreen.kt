package io.github.artcraftandroid.pdfcraft.ui.scan

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.SystemClock
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.ScanPage
import io.github.artcraftandroid.pdfcraft.ui.ToolScaffold
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The camera file's size as stored and its EXIF orientation. */
internal data class ScanPhotoInfo(val storedW: Int, val storedH: Int, val exif: Int) {
    val turned get() = exif == 6 || exif == 8
    val uprightW get() = if (turned) storedH else storedW
    val uprightH get() = if (turned) storedW else storedH

    companion object {
        /** Blocking: reads the file's header and EXIF block, not its pixels. */
        fun read(file: File): ScanPhotoInfo? = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, o)
            val exif = runCatching {
                androidx.exifinterface.media.ExifInterface(file.absolutePath)
                    .getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)
            }.getOrDefault(1)
            ScanPhotoInfo(o.outWidth, o.outHeight, exif).takeIf { it.storedW > 0 && it.storedH > 0 }
        }.getOrNull()
    }
}

/** A region of the full photo for the magnifier, already turned upright. */
private class LoupeImage(val image: ImageBitmap, val rect: PixelRect, val cx: Float, val cy: Float, val side: Int)

/** Decodes regions of one camera file; one at a time, from a background thread. */
private class LoupeSource(file: File, private val info: ScanPhotoInfo) {
    @Suppress("DEPRECATION") // newInstance(String) needs API 31
    private val decoder: BitmapRegionDecoder? = runCatching { BitmapRegionDecoder.newInstance(file.absolutePath, false) }.getOrNull()

    /** The region of about [side] pixels around the upright share point ([x], [y]). Blocking. */
    @Synchronized
    fun region(x: Float, y: Float, side: Int, sample: Int): LoupeImage? {
        val d = decoder ?: return null
        val stored = loupeRegion(x, y, info.exif, info.storedW, info.storedH, side) ?: return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = runCatching { d.decodeRegion(Rect(stored.left, stored.top, stored.right, stored.bottom), opts) }.getOrNull() ?: return null
        val deg = exifDegrees(info.exif)
        val upright = if (deg == 0) {
            raw
        } else {
            android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true)
                .also { if (it !== raw) raw.recycle() }
        }
        return LoupeImage(upright.asImageBitmap(), storedToUprightRect(stored, info.exif, info.storedW, info.storedH), x * info.uprightW, y * info.uprightH, side)
    }

    @Synchronized
    fun close() {
        decoder?.recycle()
    }
}

/** The Snap switch, remembered between scans. */
private const val SNAP_PREF = "scanCropSnap"

private fun snapSetting(context: Context) = context.getSharedPreferences("app", Context.MODE_PRIVATE)

/**
 * Crop one scanned page (#292): drag a corner, or the middle of a side to move the whole side,
 * with a magnifier showing the photo's real pixels under the finger. When a handle is let go its
 * sides are moved onto the paper's edge, if one is found close by (`scan_refine_quad`, #291).
 * Auto-detect looks for the page again, No crop takes the whole photo and Reset goes back to the
 * corners the screen opened with. Every handle can be moved with TalkBack and with a keyboard.
 *
 * [onDone] gets the corners and what happened here, for the diagnostics log and kept scans.
 */
@Composable
internal fun ScanCropScreen(page: ScanPage, index: Int, lab: ScanLab, onDone: (FloatArray, ScanCropStats) -> Unit, onCancel: () -> Unit) {
    val c = pdfColors
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val opened = remember(page.key) { page.corners.copyOf() }
    val openedAt = remember(page.key) { SystemClock.elapsedRealtime() }
    var corners by remember(page.key) { mutableStateOf(page.corners) }
    // sides placed by hand without a snap are drawn dashed
    var byHand by remember(page.key) { mutableStateOf(List(4) { false }) }
    var stats by remember(page.key) { mutableStateOf(ScanCropStats()) }
    var snapOn by remember { mutableStateOf(snapSetting(context).getBoolean(SNAP_PREF, true)) }
    var held by remember { mutableStateOf<QuadHandle?>(null) }
    // what the view shows stays put while a finger is down, so the photo does not slide under it
    var heldSpan by remember { mutableStateOf<FloatArray?>(null) }
    var selected by remember { mutableStateOf<QuadHandle?>(null) }
    var detecting by remember { mutableStateOf(false) }
    // what the last Auto-detect press came to, said on screen and to TalkBack (#443)
    var detectNote by remember(page.key) { mutableStateOf<ScanCropDetectNote?>(null) }
    // searches that found nothing on this photo, starting with the import's own (#443)
    var detectEmpty by remember(page.key) { mutableStateOf(scanCropDetectEmptyAtOpen(page.found, page.finding?.asked)) }
    // a drag started after a snap was asked for makes that snap's answer stale
    var dragSerial by remember { mutableStateOf(0) }
    var info by remember(page.file) { mutableStateOf<ScanPhotoInfo?>(null) }
    var loupe by remember(page.file) { mutableStateOf<LoupeImage?>(null) }
    val loupeWanted = remember(page.file) { MutableStateFlow<Triple<Float, Float, Int>?>(null) }
    var source by remember(page.file) { mutableStateOf<LoupeSource?>(null) }

    LaunchedEffect(page.file) {
        val i = withContext(Dispatchers.IO) { ScanPhotoInfo.read(page.file) }
        info = i
        if (i != null) source = withContext(Dispatchers.IO) { LoupeSource(page.file, i) }
    }
    DisposableEffect(page.file) { onDispose { source?.close() } }
    LaunchedEffect(source) {
        val s = source ?: return@LaunchedEffect
        // the latest request wins; one already decoding finishes and is dropped if no longer wanted
        loupeWanted.collectLatest { want ->
            val (x, y, side) = want ?: return@collectLatest
            val zoomedOut = side / LOUPE_MIN_SIDE
            val sample = Integer.highestOneBit(max(1, zoomedOut / 2))
            val made = withContext(Dispatchers.IO) { s.region(x, y, side, sample) }
            if (made != null && loupeWanted.value == want) {
                loupe = made
                stats = stats.copy(loupeDecodes = stats.loupeDecodes + 1)
            }
        }
    }

    val aspect = info?.let { it.uprightW.toFloat() / it.uprightH } ?: (page.thumb.width.toFloat() / page.thumb.height)

    fun animateTo(target: FloatArray) {
        val from = corners.copyOf()
        scope.launch {
            animate(0f, 1f, animationSpec = tween(120)) { v, _ ->
                corners = FloatArray(8) { k -> from[k] + (target[k] - from[k]) * v }
            }
            corners = target
        }
    }

    fun snap(handle: QuadHandle, stillMs: Long) {
        if (!snapOnRelease(snapOn, stillMs)) {
            if (snapOn) stats = stats.copy(snapsHeld = stats.snapsHeld + 1)
            return
        }
        val asked = sidesToSnap(handle)
        val serial = dragSerial
        val before = corners.copyOf()
        stats = stats.copy(snapsAsked = stats.snapsAsked + 1)
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            val quad = withContext(Dispatchers.Default) {
                runCatching { io.github.artcraftandroid.pdfcraft.core.scanRefineQuad(page.file.absolutePath, before.toList(), asked, SNAP_SEARCH_SHARE) }
                    .onFailure { e -> Diag.failure(DiagArea.Scan, "scan_refine_quad", e) }.getOrNull()
            }
            val ms = SystemClock.elapsedRealtime() - started
            val result = quad?.let { snapped(asked, it.refined, it.corners.toFloatArray()) }
            val stale = serial != dragSerial
            Diag.event(
                DiagArea.Scan, "crop_snap", "index" to index, "handle" to if (handle is QuadHandle.Corner) "corner" else "side",
                "sides" to asked.count { it }, "refined" to (quad?.refined?.withIndex()?.count { (k, r) -> r && asked[k] } ?: 0),
                "curved" to (quad?.curved?.withIndex()?.count { (k, r) -> r && asked[k] } ?: 0),
                "taken" to (result != null && !stale), "stale" to stale, "failed" to (quad == null), "ms" to ms,
            )
            stats = stats.copy(
                snapsTaken = stats.snapsTaken + if (result != null && !stale) 1 else 0,
                snapsFailed = stats.snapsFailed + if (quad == null) 1 else 0,
                snapMsMax = max(stats.snapMsMax, ms),
            )
            if (result == null || stale) return@launch
            val refined = quad?.refined.orEmpty()
            byHand = List(4) { k -> if (asked[k] && refined.getOrElse(k) { false }) false else byHand[k] }
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            animateTo(result)
        }
    }

    fun nudgeBy(h: QuadHandle, sx: Int, sy: Int, large: Boolean) {
        corners = nudge(corners, h, sx, sy, if (large) NUDGE_SHARE_LARGE else NUDGE_SHARE, aspect)
        byHand = List(4) { k -> byHand[k] || sidesToSnap(h)[k] }
        stats = stats.copy(nudges = stats.nudges + 1)
    }

    fun finish(done: Boolean) {
        val s = stats.copy(
            visits = if (done) 1 else 0, snapOn = snapOn, ms = SystemClock.elapsedRealtime() - openedAt,
            movedPct = if (done) maxCornerMovePct(opened, corners, aspect) else 0.0,
            cornersOff = if (done) cornersOffPhoto(corners) else 0,
        )
        Diag.event(DiagArea.Scan, "crop", "index" to index, "done" to done, *s.diag())
        if (done) onDone(corners, s) else onCancel()
    }

    ToolScaffold(stringResource(R.string.tools_crop), { finish(false) }, stringResource(R.string.common_done), true, { finish(true) }) {
        val focus = remember { FocusRequester() }
        val names = QuadHandle.ALL.associateWith { handleName(it) }
        val moveLabels = listOf(R.string.comments_move_up, R.string.comments_move_down, R.string.comments_move_left, R.string.comments_move_right).map { stringResource(it) }
        // a half of a book spread (#303): one of its sides is the cut along the gutter, not the paper's edge
        page.book?.let { half ->
            Text(
                stringResource(if (half.left) R.string.scan_crop_book_left else R.string.scan_crop_book_right), color = c.textSecondary, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().background(c.desk).focusRequester(focus).focusable().onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.Tab -> {
                        val all = QuadHandle.ALL
                        val at = all.indexOf(selected)
                        selected = all[((if (at < 0) -1 else at) + (if (e.isShiftPressed) -1 else 1) + all.size) % all.size]
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> {
                        val h = selected ?: return@onKeyEvent false
                        val (sx, sy) = when (e.key) {
                            Key.DirectionUp -> 0 to -1
                            Key.DirectionDown -> 0 to 1
                            Key.DirectionLeft -> -1 to 0
                            else -> 1 to 0
                        }
                        nudgeBy(h, sx, sy, e.isShiftPressed)
                        true
                    }
                    Key.Escape -> {
                        val had = selected != null
                        selected = null
                        had
                    }
                    else -> false
                }
            },
        ) {
            val thumb = page.thumb
            // room around the photo for the handles
            val pad = with(density) { 24.dp.toPx() }
            val viewW = constraints.maxWidth.toFloat()
            val viewH = constraints.maxHeight.toFloat()
            // a corner off the photo (#291) widens the view to show it
            val fit = fitSpan(heldSpan ?: quadSpan(corners), thumb.width.toFloat(), thumb.height.toFloat(), viewW, viewH, pad)
            val (iw, ih) = fit.width to fit.height
            val latest = rememberUpdatedState(corners)
            val latestFit = rememberUpdatedState(fit)
            val latestAspect = rememberUpdatedState(aspect)
            val loupePx = with(density) { LOUPE_SIZE.toPx() }
            // the full photo's pixels per view pixel, and so the region a magnifier at 3x needs
            val photoPerView = (info?.uprightW?.toFloat() ?: thumb.width.toFloat()) / iw
            val regionSide = ceil(loupePx * photoPerView / LOUPE_ZOOM * 1.5f).toInt().coerceIn(LOUPE_MIN_SIDE, LOUPE_MAX_SIDE)

            fun wantLoupe(h: QuadHandle) {
                val (x, y) = handlePoint(latest.value, h)
                val i = info ?: return
                val l = loupe
                if (l == null || l.side != regionSide || !loupeRegionFresh(l.cx, l.cy, x * i.uprightW, y * i.uprightH, regionSide)) {
                    loupeWanted.value = Triple(x, y, regionSide)
                }
            }

            val gestures = Modifier.pointerInput(page.file, iw, ih) {
                var lastMove = 0L
                detectDragGestures(
                    onDragStart = { p ->
                        val h = hitTest(p.x, p.y, latest.value, latestFit.value, 48.dp.toPx(), 40.dp.toPx())
                        held = h
                        if (h != null) {
                            heldSpan = quadSpan(latest.value)
                            dragSerial++
                            selected = h
                            lastMove = SystemClock.uptimeMillis()
                            stats = if (h is QuadHandle.Corner) stats.copy(cornerDrags = stats.cornerDrags + 1) else stats.copy(sideDrags = stats.sideDrags + 1)
                            detectNote = null
                            wantLoupe(h)
                        }
                    },
                    onDragEnd = {
                        val h = held
                        held = null
                        heldSpan = null
                        if (h != null) snap(h, SystemClock.uptimeMillis() - lastMove)
                    },
                    onDragCancel = {
                        held = null
                        heldSpan = null
                    },
                ) { change, d ->
                    val h = held ?: return@detectDragGestures
                    change.consume()
                    val f = latestFit.value
                    val moved = moveTowards(latest.value, h, d.x / f.width, d.y / f.height, latestAspect.value)
                    if (!moved.contentEquals(latest.value)) {
                        // a finger resting on the glass still reports tiny moves
                        if (d.getDistance() > 0.5f) lastMove = SystemClock.uptimeMillis()
                        corners = moved
                        byHand = List(4) { s -> byHand[s] || sidesToSnap(h)[s] }
                    }
                    wantLoupe(h)
                }
            }
            Box(Modifier.fillMaxSize().then(gestures)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawImage(thumb, dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()), dstSize = IntSize(iw.roundToInt().coerceAtLeast(1), ih.roundToInt().coerceAtLeast(1)))
                    val q = corners
                    val pt = { i: Int -> Offset(fit.x(q[2 * i]), fit.y(q[2 * i + 1])) }
                    val quad = Path().apply {
                        (0..3).forEach { i -> pt(i).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                        close()
                    }
                    clipPath(quad, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.5f), Offset(fit.left, fit.top), Size(iw, ih)) }
                    val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
                    for (s in 0..3) {
                        drawLine(c.accent, pt(s), pt((s + 1) % 4), 2.dp.toPx(), pathEffect = if (byHand[s]) dash else null)
                    }
                    for (h in QuadHandle.ALL) {
                        val (sx, sy) = handlePoint(q, h)
                        val p = Offset(fit.x(sx), fit.y(sy))
                        val r = if (h is QuadHandle.Corner) 11.dp.toPx() else 8.dp.toPx()
                        drawCircle(Color.White, r, p)
                        drawCircle(c.accent, r, p, style = Stroke(if (h == selected) 4.dp.toPx() else 2.5.dp.toPx()))
                    }
                }
                // one node per handle for TalkBack, at the handle, 48 dp across
                val half = with(density) { 24.dp.toPx() }
                for (h in QuadHandle.ALL) {
                    val (sx, sy) = handlePoint(corners, h)
                    val name = names.getValue(h)
                    Box(
                        Modifier.offset { IntOffset((fit.x(sx) - half).roundToInt(), (fit.y(sy) - half).roundToInt()) }.size(48.dp).semantics {
                            contentDescription = name
                            customActions = listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0).mapIndexed { i, (dx, dy) ->
                                CustomAccessibilityAction(moveLabels[i]) {
                                    nudgeBy(h, dx, dy, large = false)
                                    true
                                }
                            }
                        },
                    )
                }
                val h = held
                if (h != null) {
                    val (sx, sy) = handlePoint(corners, h)
                    val hx = fit.x(sx)
                    val hy = fit.y(sy)
                    val (lx, ly) = loupePlacement(hx, hy, viewW, viewH, loupePx, with(density) { 36.dp.toPx() })
                    Canvas(Modifier.offset { IntOffset(lx.roundToInt(), ly.roundToInt()) }.size(LOUPE_SIZE).clip(androidx.compose.foundation.shape.CircleShape)) {
                        drawLoupe(thumb, loupe, info, corners, h, sx, sy, fit, c.desk, c.accent)
                    }
                }
            }
        }
        // what Auto-detect came to (#443): always in place and a polite live region, so TalkBack reads each new line
        val noteText = detectNote?.let { stringResource(it.text) }.orEmpty()
        Text(
            noteText, color = c.textSecondary, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (noteText.isEmpty()) 0.dp else 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    detecting = true
                    stats = stats.copy(detects = stats.detects + 1)
                    // the line is emptied first, so the same words said again are announced again
                    detectNote = null
                    val asked = lab.findOptions()
                    val plan = scanCropDetectPlan(asked, detectEmpty, lab.cropDetectNote)
                    scope.launch {
                        if (plan !is ScanCropDetectPlan.Search) {
                            Diag.event(
                                DiagArea.Scan, "crop_detect", "index" to index, "found" to false, "book_half" to (page.book?.side ?: "none"),
                                "source" to "skipped", "ms" to 0L, "finder" to "none", "other" to false, "skipped" to true,
                                "note" to ScanCropDetectNote.AlreadySearched.id, "detect_note" to lab.cropDetectNote,
                            )
                            stats = stats.copy(detectsSkipped = stats.detectsSkipped + 1)
                            withFrameNanos { }
                            detectNote = ScanCropDetectNote.AlreadySearched
                            detecting = false
                            return@launch
                        }
                        val started = SystemClock.elapsedRealtime()
                        val doc = withContext(Dispatchers.Default) {
                            runCatching { io.github.artcraftandroid.pdfcraft.core.scanFindDocumentWith(page.file.absolutePath, plan.options) }
                                .onFailure { e -> Diag.failure(DiagArea.Scan, "scan_find_document", e) }.getOrNull()
                        }
                        val spread = doc?.takeIf { it.found }?.corners?.takeIf { it.size == 8 }?.toFloatArray()?.takeIf { convexQuad(it) }
                        // a half of a book spread (#303): the spread found is cut again and this half kept
                        val half = page.book
                        val found = if (half == null || spread == null) spread else withContext(Dispatchers.Default) {
                            runCatching { io.github.artcraftandroid.pdfcraft.core.scanSplitSpread(page.file.absolutePath, spread.toList(), lab.bookGutter) }
                                .onFailure { e -> Diag.failure(DiagArea.Scan, "scan_split_spread", e) }.getOrNull()
                        }?.let { ScanBookCut(it.split, it.left.toFloatArray(), it.right.toFloatArray(), it.gutterFound, it.gutterTop, it.gutterBottom, it.confidence) }
                            .let { cut -> scanBookRecut(half, cut)?.takeIf { convexQuad(it) } }
                        val note = if (lab.cropDetectNote) scanCropDetectNote(found != null, doc == null, plan.other) else null
                        Diag.event(
                            DiagArea.Scan, "crop_detect", "index" to index, "found" to (found != null), "book_half" to (half?.side ?: "none"),
                            "source" to (doc?.source?.name?.lowercase() ?: "failed"), "ms" to SystemClock.elapsedRealtime() - started,
                            "finder" to ScanLab.finderId(plan.options.finder), "other" to plan.other, "skipped" to false,
                            "note" to (note?.id ?: "none_shown"), "detect_note" to lab.cropDetectNote,
                        )
                        // a search that ran and found nothing would find nothing again (a failed call might not)
                        if (found == null && doc != null) detectEmpty = detectEmpty + plan.options
                        if (plan.other) stats = stats.copy(detectsOther = stats.detectsOther + 1)
                        detectNote = note
                        detecting = false
                        if (found != null) {
                            stats = stats.copy(detectsFound = stats.detectsFound + 1)
                            byHand = List(4) { false }
                            animateTo(found)
                        }
                    }
                },
                enabled = !detecting,
            ) { Text(stringResource(R.string.tools_auto_detect), color = if (detecting) c.textSecondary else c.accent) }
            TextButton(onClick = {
                stats = stats.copy(wholePhoto = stats.wholePhoto + 1)
                detectNote = null
                byHand = List(4) { false }
                corners = ScanPage.WHOLE
            }) { Text(stringResource(R.string.tools_no_crop), color = c.accent) }
            TextButton(onClick = {
                stats = stats.copy(resets = stats.resets + 1)
                detectNote = null
                byHand = List(4) { false }
                corners = opened.copyOf()
            }) { Text(stringResource(R.string.tools_reset), color = c.accent) }
            val snapLabel = stringResource(R.string.tools_crop_snap)
            Row(Modifier.wrapContentWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(snapLabel, color = c.text, modifier = Modifier.padding(end = 6.dp))
                io.github.artcraftandroid.pdfcraft.ui.PdfSwitch(
                    snapOn,
                    { on ->
                        snapOn = on
                        snapSetting(context).edit().putBoolean(SNAP_PREF, on).apply()
                    },
                    Modifier.semantics { contentDescription = snapLabel },
                )
            }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

private val LOUPE_SIZE = 120.dp

/** The magnifier shows the photo at three times the scale it has on screen. */
private const val LOUPE_ZOOM = 3f

/** The smallest and largest region decoded for it, in photo pixels. */
private const val LOUPE_MIN_SIDE = 512
private const val LOUPE_MAX_SIDE = 2048

@Composable
private fun handleName(h: QuadHandle): String = stringResource(
    when (h) {
        is QuadHandle.Corner -> listOf(R.string.tools_crop_top_left, R.string.tools_crop_top_right, R.string.tools_crop_bottom_right, R.string.tools_crop_bottom_left)[h.index]
        is QuadHandle.Side -> listOf(R.string.tools_crop_top_side, R.string.tools_crop_right_side, R.string.tools_crop_bottom_side, R.string.tools_crop_left_side)[h.index]
    },
)

/**
 * The magnifier: the photo around the handle at ([sx], [sy]) shares, at three times its on-screen
 * scale, with a crosshair and the page's outline. The thumbnail first, and the full photo's
 * pixels over it once its region is decoded.
 */
private fun DrawScope.drawLoupe(
    thumb: ImageBitmap,
    loupe: LoupeImage?,
    info: ScanPhotoInfo?,
    q: FloatArray,
    h: QuadHandle,
    sx: Float,
    sy: Float,
    fit: QuadFit,
    desk: Color,
    accent: Color,
) {
    val centre = Offset(size.width / 2, size.height / 2)
    drawRect(desk)
    // view pixels in the loupe per share of the photo
    val zw = fit.width * LOUPE_ZOOM
    val zh = fit.height * LOUPE_ZOOM
    val origin = Offset(centre.x - sx * zw, centre.y - sy * zh)
    drawImage(thumb, dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()), dstSize = IntSize(zw.roundToInt().coerceAtLeast(1), zh.roundToInt().coerceAtLeast(1)))
    if (loupe != null && info != null) {
        val px = zw / info.uprightW
        val py = zh / info.uprightH
        drawImage(
            loupe.image,
            dstOffset = IntOffset((origin.x + loupe.rect.left * px).roundToInt(), (origin.y + loupe.rect.top * py).roundToInt()),
            dstSize = IntSize((loupe.rect.width * px).roundToInt().coerceAtLeast(1), (loupe.rect.height * py).roundToInt().coerceAtLeast(1)),
        )
    }
    val at = { i: Int -> Offset(origin.x + q[2 * i] * zw, origin.y + q[2 * i + 1] * zh) }
    val sides = when (h) {
        is QuadHandle.Corner -> listOf((h.index + 3) % 4, h.index)
        is QuadHandle.Side -> listOf(h.index)
    }
    for (s in sides) drawLine(accent, at(s), at((s + 1) % 4), 1.5.dp.toPx())
    val arm = 10.dp.toPx()
    drawLine(Color.White, Offset(centre.x - arm, centre.y), Offset(centre.x + arm, centre.y), 1.dp.toPx())
    drawLine(Color.White, Offset(centre.x, centre.y - arm), Offset(centre.x, centre.y + arm), 1.dp.toPx())
    drawCircle(accent, min(size.width, size.height) / 2 - 1.dp.toPx(), centre, style = Stroke(2.dp.toPx()))
}
