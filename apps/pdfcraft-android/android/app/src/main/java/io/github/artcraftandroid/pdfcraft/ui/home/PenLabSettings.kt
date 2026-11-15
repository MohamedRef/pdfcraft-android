package io.github.artcraftandroid.pdfcraft.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkAppearance
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkShapeMode
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStroke
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenDials
import io.github.artcraftandroid.pdfcraft.ui.viewer.drawInkStroke
import io.github.artcraftandroid.pdfcraft.ui.viewer.finishInkStroke
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkStyleOpacity
import io.github.artcraftandroid.pdfcraft.ui.viewer.newInkStroke

/** The strip's scale: one point of ink is this many dp, about a page at 150 %. */
private const val STRIP_DP_PER_PT = 1.5f

/**
 * Settings, Diagnostics, Pen lab (#569): each pen's dials as sliders, a strip to write on with the
 * pen as it is now, Reset, and "Write values to the log". The dials are the pens' own: a change
 * here is what the pen draws on the page at once, so the owner tunes on his phone without a build.
 */
@Composable
internal fun PenLabSettings(vm: AppViewModel) {
    val c = pdfColors
    val prefs = vm.settings.toolPrefs
    val tuning = prefs.penTuning
    var style by remember { mutableStateOf(prefs.inkStyle) }
    val d = tuning.dials(style)
    val strokes = remember { mutableStateListOf<InkStroke>() }

    SettingsNote(stringResource(R.string.pen_lab_body))
    SettingsChoice(
        stringResource(R.string.pen_lab_pen),
        InkStyle.entries.map { it to stringResource(styleName(it)) },
        style, { style = it; strokes.clear() },
    )

    // the strip: write with the pen (or a finger) exactly as on a page
    Text(stringResource(R.string.pen_lab_try), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
    val density = LocalDensity.current.density
    val pxPerPt = density * STRIP_DP_PER_PT
    Box(
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().height(180.dp)
            .clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, c.divider, RoundedCornerShape(8.dp))
            .pointerInput(style) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.type == PointerType.Eraser) return@awaitEachGesture
                    down.consume()
                    val pen = prefs.penSettings(style)
                    val appearance = InkAppearance(pen.argb, inkStyleOpacity(pen.opacity, style), pen.width, null, style)
                    val s = newInkStroke(0, appearance, down.type == PointerType.Stylus, InkShapeMode.Freehand, tuning)
                    s.add(down.position / pxPerPt, down.uptimeMillis, down.pressure)
                    strokes.add(s)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.historical.forEach { h -> s.add(h.position / pxPerPt, h.uptimeMillis, change.pressure) }
                        if (change.positionChanged()) s.add(change.position / pxPerPt, change.uptimeMillis, change.pressure)
                        event.changes.forEach { it.consume() }
                        if (!change.pressed) break
                    }
                    finishInkStroke(s, tuning)
                    if (s.points.size == 1) {
                        s.points.add(s.points[0] + Offset(0.01f, 0f))
                        s.scales.add(s.scales[0])
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val bounds = Rect(Offset.Zero, size)
            for (s in strokes) {
                if (s.points.size < 2) continue
                // reading the points and widths here redraws the strip as the stroke grows
                s.points.size
                s.widthVersion
                drawInkStroke(s.paths.runs(s), Color(s.appearance.argb), s.appearance.opacity, s.appearance.style == InkStyle.Highlighter, Offset.Zero, pxPerPt, bounds)
            }
        }
    }
    SettingsRow(stringResource(R.string.pen_lab_clear_strip), { strokes.clear() }, icon = LucideIcons.Eraser)

    @Composable
    fun Dial(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, digits: Int, set: (Float) -> PenDials) {
        val shown = if (digits == 0) value.toInt().toString() else "%.${digits}f".format(java.util.Locale.ROOT, value)
        Text("${stringResource(label)}: $shown", color = c.text, fontSize = 15.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
        Slider(value = value.coerceIn(range), onValueChange = { tuning.update(style, set(it)) }, valueRange = range, modifier = Modifier.padding(horizontal = 20.dp))
    }

    SettingsHeader(stringResource(R.string.pen_lab_group_pressure))
    Dial(R.string.pen_lab_min, d.minScale, 0.05f..3f, 2) { d.copy(minScale = it, maxScale = maxOf(d.maxScale, it)) }
    Dial(R.string.pen_lab_max, d.maxScale, 0.05f..3f, 2) { d.copy(maxScale = it, minScale = minOf(d.minScale, it)) }
    Dial(R.string.pen_lab_curve, d.curve, 0.3f..3f, 2) { d.copy(curve = it) }
    SettingsHeader(stringResource(R.string.pen_lab_group_speed))
    Dial(R.string.pen_lab_speed_thin, d.speedThin, 0f..0.9f, 2) { d.copy(speedThin = it) }
    Dial(R.string.pen_lab_speed_ref, d.speedRef, 100f..3000f, 0) { d.copy(speedRef = it) }
    SettingsHeader(stringResource(R.string.pen_lab_group_tip))
    Dial(R.string.pen_lab_nib, d.nib, 0f..0.9f, 2) { d.copy(nib = it) }
    Dial(R.string.pen_lab_nib_angle, d.nibAngleDeg, -90f..90f, 0) { d.copy(nibAngleDeg = it) }
    Dial(R.string.pen_lab_taper_start, d.taperStart, 0f..10f, 1) { d.copy(taperStart = it) }
    Dial(R.string.pen_lab_taper_end, d.taperEnd, 0f..10f, 1) { d.copy(taperEnd = it) }
    Dial(R.string.pen_lab_damping, d.dampingMs, 0f..200f, 0) { d.copy(dampingMs = it) }

    SettingsHeader(stringResource(R.string.pen_lab_group_measured))
    val cal = tuning.calibration
    val range = cal.range
    SettingsNote(
        if (range.lo > 0f || range.hi < 1f) {
            stringResource(R.string.pen_lab_pressure, "%.2f".format(java.util.Locale.ROOT, range.lo), "%.2f".format(java.util.Locale.ROOT, range.hi), cal.strokes)
        } else stringResource(R.string.pen_lab_pressure_none),
    )
    SettingsRow(stringResource(R.string.pen_lab_pressure_reset), { tuning.resetPressure() }, icon = LucideIcons.RotateCw)

    SettingsHeader(stringResource(R.string.pen_lab_group_save))
    SettingsRow(stringResource(R.string.pen_lab_reset), { tuning.reset(style); strokes.clear() }, icon = LucideIcons.RotateCcw, enabled = tuning.tuned(style))
    SettingsRow(stringResource(R.string.pen_lab_write_log), { tuning.logValues(); vm.tell(text(R.string.pen_lab_written)) }, icon = LucideIcons.FileText)
}

private fun styleName(style: InkStyle): Int = when (style) {
    InkStyle.Ballpoint -> R.string.ink_style_ballpoint
    InkStyle.Fountain -> R.string.ink_style_fountain
    InkStyle.Pencil -> R.string.ink_style_pencil
    InkStyle.Brush -> R.string.ink_style_brush
    InkStyle.Highlighter -> R.string.ink_style_highlighter
}
