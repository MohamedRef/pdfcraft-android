package io.github.artcraftandroid.pdfcraft.ui.viewer


import androidx.compose.foundation.selection.selectable
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect

import io.github.artcraftandroid.pdfcraft.engine.userMessage

import io.github.artcraftandroid.pdfcraft.engine.Engine

import io.github.artcraftandroid.pdfcraft.core.MobileException

import androidx.lifecycle.viewModelScope

import androidx.compose.runtime.snapshotFlow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.artcraftandroid.pdfcraft.ui.LocalUndo
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.core.TextRange
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.SheetHost
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlinx.coroutines.Job
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.minimumInteractiveComponentSize

// Moved from Tools.kt as is (#730): the Draw tool's stroke model and its pure helpers.

/**
 * A stroke being drawn: its page and points (in the page's points), and each point's width as a
 * multiple of the pen width ([scales], from [dynamics] as the stroke grows, #569). [pressureSensitive]
 * means the width varies along the stroke (it is drawn and saved with a width per point); [stylus]
 * that a pen drew it.
 */
internal class InkStroke(
    val page: Int,
    val appearance: InkAppearance,
    varies: Boolean = false,
    val shapeMode: InkShapeMode = InkShapeMode.Freehand,
    val dynamics: PenDynamics? = null,
    val stylus: Boolean = false,
    /** How many dp one page point takes on screen while this stroke is drawn (the pen's speed is measured on the glass). */
    dpPerPt: Float = 1f,
) {
    val points = mutableStateListOf<Offset>()
    val scales = mutableStateListOf<Float>()
    /** Dashed or dotted (#682): cut into its pieces when the pen lifts, shown through a mask until then. */
    var pattern = InkPattern.Solid
    /** The pieces of one dashed or dotted line share this token, so they are saved as one annotation. */
    var group: Any? = null
    /** A pencil's ink density per point (#569), from [dynamics]; empty for solid ink. */
    val densities = mutableStateListOf<Float>()
    var pressureSensitive by mutableStateOf(varies)
    /** Bumped when [scales] change in place (the end taper), so cached outlines are rebuilt. */
    var widthVersion by mutableStateOf(0)

    /** Takes the tremor out of a freehand stroke's points ([PenDials.smoothing]). */
    private val smoother = dynamics?.let { InkSmoother(it.smoothing, dpPerPt) }
    /** Keeps a freehand line's points [INK_POINT_SPACING_DP] apart on the glass (#609); a shape keeps every point. */
    private val thinner = if (dynamics != null) inkPointThinner(dpPerPt, appearance.width * inkStyleWidthFactor(appearance.style)) else InkPointThinner(0f)
    private var lastTimeMs = 0L
    private var lastPressure = 1f

    /**
     * Where the pen is now, in page points: smoothing keeps the line's last point a little behind
     * it, and the tail drawn while writing fills that gap so the ink always reaches the pen.
     */
    val penTip: Offset? get() = smoother?.tip

    /**
     * The pen's lean (radians) for the sample at [timeMs]: that sample's own, from the raw pen log;
     * the newest one when the log has no sample at that time; NaN for a finger.
     */
    private fun tiltAt(timeMs: Long): Float = if (stylus) StylusLog.pen.tiltAt(timeMs) ?: StylusLog.tiltRad else Float.NaN

    /** Adds a sample: [p] in page points at [timeMs] with the pen's [pressure]. */
    fun add(p: Offset, timeMs: Long, pressure: Float) {
        val q = smoother?.next(p, timeMs) ?: p
        lastTimeMs = timeMs
        lastPressure = pressure
        // the pen model sees every sample; the line keeps a point every so often
        val scale = dynamics?.next(q, timeMs, pressure, tiltAt(timeMs)) ?: 1f
        val density = dynamics?.density ?: 1f
        dynamics?.stats?.let { it.given++ }
        thinner.add(q, scale, density, ::addPoint)
    }

    private fun addPoint(q: Offset, scale: Float, density: Float) {
        points.add(q)
        scales.add(scale)
        if (grain) densities.add(density)
        dynamics?.stats?.let { it.kept++ }
    }

    /** Whether this stroke shows the paper through a pencil's grain. */
    val grain: Boolean = dynamics?.grain == true

    /** The pen has lifted: the stroke's end is its own from here on ([endPreview] is over). */
    var lifted by mutableStateOf(false)
        private set

    /**
     * The pointed end the line is drawn with while it is still being written (#611,
     * [PenDynamics.endPreview]); null once lifted, for a shape, and for a line of one width.
     */
    fun endPreview(): InkEndPreview? {
        if (lifted || !pressureSensitive || preview != null || shapeMode != InkShapeMode.Freehand) return null
        return dynamics?.endPreview(points, scales, thinner.pending, penTip)
    }

    /** On lift: a smoothed line that stopped short of the pen is taken to where the pen left the page. */
    fun catchUp() {
        lifted = true
        // the last sample, when thinning had not made it a point yet
        thinner.flush()?.let { (q, scale, density) -> addPoint(q, scale, density) }
        val end = smoother?.end() ?: return
        val scale = dynamics?.liftScale(end, lastTimeMs, lastPressure, tiltAt(lastTimeMs)) ?: 1f
        addPoint(end, scale, dynamics?.density ?: 1f)
    }

    /** Keeps [densities] one per point after [points] changed shape (a dot, a straightened or snapped line). */
    fun matchDensities() {
        if (densities.isEmpty()) return
        val fill = densities.firstOrNull() ?: 1f
        while (densities.size < points.size) densities.add(fill)
        while (densities.size > points.size) densities.removeAt(densities.lastIndex)
    }

    /** The clean shape shown while the finger is held still (press and hold to snap). */
    var preview by mutableStateOf<List<Offset>?>(null)

    /** Smoothed page-space paths, rebuilt only when this stroke's points change. */
    val paths = InkPathCache()

    private var boundsKey: Any? = null
    private var boundsBox: InkLassoBounds? = null

    /**
     * The box of this stroke's points (page points), kept until they change (#687): the eraser
     * leaves a stroke it cannot reach without copying or walking its points. Null without points.
     */
    fun bounds(): InkLassoBounds? {
        val key = Triple(points.size, points.firstOrNull(), points.lastOrNull())
        if (key != boundsKey) {
            boundsBox = inkLassoBounds(listOf(points.toList()))
            boundsKey = key
        }
        return boundsBox
    }
}

/**
 * Whether the eraser moving from [a] to [b] cannot reach anything in [box] (#687): the box grown
 * by [reach] (the eraser's radius and the widest half of the line) does not meet the segment's box.
 */
internal fun inkEraserMisses(box: InkLassoBounds, a: Offset, b: Offset, reach: Float): Boolean =
    minOf(a.x, b.x) > box.right + reach || maxOf(a.x, b.x) < box.left - reach ||
        minOf(a.y, b.y) > box.bottom + reach || maxOf(a.y, b.y) < box.top - reach

/**
 * A new ink stroke (#569): a freehand stroke gets the pen model with [tuning]'s dials for its
 * style, pressure from the pen (stretched by the measured range) or, for a finger, from speed. A
 * shape keeps the pen's constant width. Its numbers go to the next `stroke` log line.
 *
 * [dpPerPt] is how many dp one page point takes on screen where the stroke is drawn (the page at
 * its zoom, the zoom pad, the Pen lab's strip): the pen's speed is measured on the glass, so the
 * same hand movement gives the same line wherever and however large the page is shown.
 */
internal fun newInkStroke(
    page: Int, appearance: InkAppearance, stylus: Boolean, mode: InkShapeMode, tuning: PenTuning, dpPerPt: Float,
    /** The pen's Pressure setting from its popover (#608); the Pen lab's strip shows the dials as they are. */
    pressureSetting: Float = PEN_PRESSURE_DEFAULT,
): InkStroke {
    if (mode != InkShapeMode.Freehand) {
        PenDiag.inkStroke(null)
        return InkStroke(page, appearance, false, mode, null, stylus)
    }
    val style = appearance.style
    val dials = tuning.dials(style)
    val source = if (stylus) PressureSource.Pen else PressureSource.Speed
    val onGlass = dpPerPt.takeIf { it.isFinite() && it > 0f } ?: 1f
    val stats = PenStrokeStats(style, source, appearance.width, dials, appearance.opacity, onGlass, penPressureSetting(pressureSetting))
    val range = if (stylus) tuning.calibration.range else PressureRange()
    val curve = if (stylus) tuning.pressureCurve else PressureCurve.LINEAR
    val dynamics = PenDynamics(dials, source, range, appearance.width.takeIf { it.isFinite() && it > 0f } ?: 1f, stats, curve, onGlass, pressureSetting)
    PenDiag.inkStroke(stats)
    return InkStroke(page, appearance, dials.varies && style != InkStyle.Highlighter, mode, dynamics, stylus, onGlass)
}

/**
 * A freehand stroke is lifted: its end taper goes on, and a pen stroke's pressures measure the
 * pen's range (#569) when it is ordinary writing ([inkStrokeMeasuresPressure]; [trial] is true for
 * the Pen lab's strip).
 */
internal fun finishInkStroke(s: InkStroke, tuning: PenTuning, trial: Boolean = false) {
    val d = s.dynamics ?: return
    val measures = inkStrokeMeasuresPressure(s.stylus, s.appearance.style, trial)
    s.catchUp()
    if (s.pressureSensitive) {
        d.finish(s.points, s.scales)
        s.widthVersion++
    }
    if (measures) d.stats?.let { tuning.learn(it.raw.toList()) }
}

/**
 * Whether a stroke's pressures count towards the band ordinary handwriting uses (#607): a pen's,
 * written on a page. Not the marker's, whose width does not answer pressure and which is held
 * differently, and not a stroke on the Pen lab's strip ([trial]), where the pen is pressed hard and
 * light on purpose to try it: five such strokes among thirty moved the band's top from 0.15 to 0.21.
 */
internal fun inkStrokeMeasuresPressure(stylus: Boolean, style: InkStyle, trial: Boolean): Boolean =
    stylus && !trial && style != InkStyle.Highlighter

/**
 * The tail of the stroke being written: where it starts, the pen's tip when the tail has to reach
 * it, and its width as a multiple of the pen width. A line drawn with a pointed end
 * ([InkStroke.endPreview], #611) already reaches the pen, so its tail is only the piece ahead of
 * the pen, as thin as the point; any other line's tail runs from its last point to the pen.
 */
internal fun inkTailStart(s: InkStroke): Triple<Offset, Offset?, Float>? {
    val last = s.points.lastOrNull() ?: return null
    val end = s.endPreview() ?: return Triple(last, s.penTip, s.scales.lastOrNull() ?: 1f)
    return Triple(end.extra.lastOrNull() ?: last, null, end.scales.lastOrNull() ?: 1f)
}

/**
 * The opacity of the tail drawn from [s]'s last point to the pen: a pencil's is lightened by the
 * ink density it is laying down, so a light pencil line is not led by a solid dark tip (#605).
 */
internal fun inkTailOpacity(s: InkStroke): Float = inkTailOpacity(s.appearance.opacity, s.densities.lastOrNull())

internal fun inkTailOpacity(opacity: Float, density: Float?): Float {
    val o = opacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    return o * (density?.takeIf { it.isFinite() }?.coerceIn(0.05f, 1f) ?: 1f)
}

/** Whether [s] is a highlighter stroke that "Straighten lines" ([on]) draws straight (#569). */
internal fun inkStraightened(s: InkStroke, on: Boolean): Boolean =
    on && s.shapeMode == InkShapeMode.Freehand && s.appearance.style == InkStyle.Highlighter && s.points.size >= 2

/**
 * The event time of the sample [toolGestures] is handing to `onDown` or `onMove` right now, so the
 * pen model (#569) can measure speed without changing every tool's callback.
 */
internal var inkSampleTimeMs = 0L

/** How long the finger stays still to snap a stroke to a shape, and how far it may wander. */
internal const val SNAP_HOLD_MS = 500L
internal val SNAP_SLOP = 8.dp
