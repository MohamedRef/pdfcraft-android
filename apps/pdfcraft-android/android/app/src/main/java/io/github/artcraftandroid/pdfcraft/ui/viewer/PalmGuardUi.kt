package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/**
 * The writing-hand guard for toolbars (#156): with the guard on in Draw, a finger touch that
 * lands on this toolbar inside the guard area of a recently active pen is swallowed before any
 * button sees it, so a resting palm cannot press tools. The pen itself always gets through.
 */
@Composable
internal fun Modifier.palmGuardedTaps(ctl: ViewerController, prefs: ToolPrefs): Modifier {
    if (!prefs.palmGuard || ctl.tool != ViewerTool.Draw) return this
    val size = with(LocalDensity.current) { prefs.palmGuardSizeDp.dp.toPx() }
    val left = prefs.leftHanded
    var origin by remember { mutableStateOf(Offset.Zero) }
    return this.onGloballyPositioned { origin = it.positionInRoot() }.pointerInput(size, left) {
        awaitPointerEventScope {
            val blocked = HashSet<PointerId>()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                for (change in event.changes) {
                    if (change.changedToDown() && change.type == PointerType.Touch &&
                        palmGuardRejects(ctl.penInRoot.position, ctl.penInRoot.timeMs, origin + change.position, change.uptimeMillis, left, size)
                    ) blocked += change.id
                    if (change.id in blocked) {
                        change.consume()
                        if (!change.pressed) blocked -= change.id
                    }
                }
            }
        }
    }
}

/**
 * A full-screen check of the palm guard (#156): hover or write with the pen to see the guard
 * area follow it, rest the hand as when writing, and adjust the size and writing hand until the
 * resting touches land inside it. Touches inside show green (ignored), outside red (they would act).
 */
@Composable
internal fun PalmGuardCalibration(prefs: ToolPrefs, onDone: () -> Unit) {
    val c = pdfColors
    val size = with(LocalDensity.current) { prefs.palmGuardSizeDp.dp.toPx() }
    val pen = remember { PenTracker() }
    var penAt by remember { mutableStateOf<Offset?>(null) }
    val touches = remember { mutableStateListOf<Pair<Offset, Boolean>>() }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(c.surface)
                .pointerInput(size, prefs.leftHanded) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            for (change in event.changes) {
                                if (change.type == PointerType.Stylus || change.type == PointerType.Eraser) {
                                    pen.seen(change.position, change.uptimeMillis)
                                    penAt = change.position
                                } else if (change.changedToDown() && change.type == PointerType.Touch) {
                                    val ignored = palmGuardRejects(pen.position, pen.timeMs, change.position, change.uptimeMillis, prefs.leftHanded, size)
                                    touches.add(change.position to ignored)
                                    while (touches.size > 24) touches.removeAt(0)
                                }
                            }
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                penAt?.let { p ->
                    val r = palmGuardRegion(p, prefs.leftHanded, size)
                    drawRect(Color(0xFF2E7DFF).copy(alpha = 0.10f), r.topLeft, r.size)
                    drawRect(Color(0xFF2E7DFF).copy(alpha = 0.6f), r.topLeft, r.size, style = Stroke(width = 1.5.dp.toPx()))
                    drawCircle(Color(0xFF2E7DFF), radius = 5.dp.toPx(), center = p)
                }
                for ((t, ignored) in touches) {
                    drawCircle((if (ignored) Color(0xFF1E9E5A) else Color(0xFFD93A3A)).copy(alpha = 0.75f), radius = 14.dp.toPx(), center = t)
                }
            }
            Column(
                Modifier.align(Alignment.TopCenter).safeDrawingPadding().fillMaxWidth().background(c.surface.copy(alpha = 0.92f)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.palm_guard_calibrate), color = c.text, fontSize = 18.sp)
                Text(stringResource(R.string.palm_guard_calibrate_help), color = c.label, fontSize = 13.sp)
                OptionChips(listOf(false, true), prefs.leftHanded, { prefs.updateLeftHanded(it) }) {
                    stringResource(if (it) R.string.hand_left else R.string.hand_right)
                }
                Text(stringResource(R.string.palm_guard_size, prefs.palmGuardSizeDp.toInt()), color = c.label, fontSize = 12.sp)
                Slider(
                    value = prefs.palmGuardSizeDp,
                    onValueChange = { prefs.updatePalmGuardSizeDp(Math.round(it / 10f) * 10f) },
                    valueRange = 60f..240f,
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { touches.clear() }) { Text(stringResource(R.string.palm_guard_calibrate_clear), color = c.accent) }
                    TextButton(onClick = onDone) { Text(stringResource(R.string.common_done), color = c.accent) }
                }
            }
        }
    }
}
