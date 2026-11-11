package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/**
 * Two visible tiers of direct-access annotation controls, inspired by the discoverability of
 * pen-first note apps. Top: all five actual pen types, Eraser, Lasso. Bottom: three immediately
 * usable favourites, Color, Settings, Line, Rectangle, Oval.
 *
 * Both rows fit without scrolling on a typical 400dp phone. On narrower layouts each row can
 * scroll independently; a selected toggle never steals the position of the active row.
 */
internal val DrawToolbarHeight = 112.dp
private val DrawToolSlot = 48.dp
private val DrawRowHeight = 56.dp

internal fun activateDrawingPen(ctl: ViewerController, prefs: ToolPrefs, style: InkStyle) {
    prefs.updateInkStyle(style)
    ctl.eraserMode = false
    ctl.lassoMode = false
    ctl.inkShapeMode = InkShapeMode.Freehand
}

/** What tapping a pen button opens: its style popover when it is already the selected pen. */
internal fun penButtonPicker(penAlreadyActive: Boolean, current: Picker): Picker =
    if (penAlreadyActive && current != Picker.PenStyle) Picker.PenStyle else Picker.None

internal fun activateDrawingPreset(ctl: ViewerController, prefs: ToolPrefs, index: Int): Boolean {
    if (!prefs.applyInkPreset(index)) return false
    ctl.eraserMode = false
    ctl.lassoMode = false
    ctl.inkShapeMode = InkShapeMode.Freehand
    return true
}

@Composable
internal fun DrawToolbar(vm: AppViewModel, doc: OpenDocument) {
    val ctl = doc.controller
    val prefs = vm.toolPrefs
    val c = pdfColors
    val color = prefs.color(StyledTool.Ink)
    val opacity = prefs.opacity(StyledTool.Ink)
    val picker = ctl.picker

    Column(Modifier.fillMaxWidth().height(DrawToolbarHeight).barTopLine().background(c.surface)) {
        // Seven primary tools. 7 * 48dp = 336dp; all are directly usable on normal phones.
        BoxWithConstraints(Modifier.fillMaxWidth().height(DrawRowHeight)) {
            val contentWidth = maxOf(maxWidth, DrawToolSlot * 7)
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                Row(
                    Modifier.width(contentWidth).height(DrawRowHeight),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    InkStyle.entries.forEach { style ->
                        val label = stringResource(inkStyleLabel(style))
                        val active = !ctl.eraserMode && !ctl.lassoMode &&
                            ctl.inkShapeMode == InkShapeMode.Freehand && prefs.inkStyle == style
                        val pen = prefs.penSettings(style)
                        DrawToolbarButton(label, active, onClick = {
                            // Like the eraser: a second tap on the selected pen opens its colour and width.
                            ctl.picker = penButtonPicker(active, picker)
                            if (!active) activateDrawingPen(ctl, prefs, style)
                        }, caption = stringResource(when (style) {
                            InkStyle.Ballpoint -> R.string.ink_toolbar_ballpoint
                            InkStyle.Fountain -> R.string.ink_toolbar_fountain
                            InkStyle.Pencil -> R.string.ink_toolbar_pencil
                            InkStyle.Brush -> R.string.ink_toolbar_brush
                            InkStyle.Highlighter -> R.string.ink_toolbar_marker
                        }), onClickLabel = if (active) stringResource(R.string.ink_pen_edit_action) else null) {
                            PenNibPreview(Color(pen.argb), pen.width, pen.opacity, style, Modifier.size(29.dp, 31.dp))
                        }
                    }
                    DrawToolbarButton(
                        label = stringResource(R.string.toolbar_eraser),
                        active = ctl.eraserMode,
                        caption = stringResource(R.string.ink_toolbar_eraser),
                        onClick = {
                            if (ctl.eraserMode) {
                                ctl.picker = if (picker == Picker.EraserSize) Picker.None else Picker.EraserSize
                            } else {
                                ctl.eraserMode = true
                                ctl.lassoMode = false
                                ctl.picker = Picker.None
                            }
                        },
                    ) { Icon(LucideIcons.Eraser, null, tint = if (ctl.eraserMode) c.toolAccent else c.icon, modifier = Modifier.size(24.dp)) }
                    DrawToolbarButton(
                        label = stringResource(R.string.toolbar_ink_lasso),
                        active = ctl.lassoMode,
                        caption = stringResource(R.string.ink_toolbar_lasso),
                        onClick = {
                            ctl.lassoMode = true
                            ctl.eraserMode = false
                            ctl.picker = Picker.None
                        },
                    ) { Icon(LucideIcons.Lasso, null, tint = if (ctl.lassoMode) c.toolAccent else c.icon, modifier = Modifier.size(24.dp)) }
                }
            }
        }

        // Direct favourites and optional controls. They never steal first-row pen space.
        BoxWithConstraints(Modifier.fillMaxWidth().height(DrawRowHeight)) {
            val contentWidth = maxOf(maxWidth, DrawToolSlot * 8)
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                Row(
                    Modifier.width(contentWidth).height(DrawRowHeight),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    prefs.inkPresets.forEachIndexed { index, preset ->
                        val slot = stringResource(R.string.ink_preset_slot, index + 1)
                        val active = !ctl.eraserMode && !ctl.lassoMode &&
                            ctl.inkShapeMode == InkShapeMode.Freehand && prefs.currentInkPreset() == preset
                        DrawToolbarButton(slot, active, onClick = {
                            if (activateDrawingPreset(ctl, prefs, index)) ctl.picker = Picker.None
                        }) {
                            Box(contentAlignment = Alignment.Center) {
                                PenNibPreview(
                                    Color(preset.argb), preset.width, preset.opacity, preset.style,
                                    Modifier.size(27.dp, 31.dp),
                                )
                            }
                        }
                    }
                    if (ctl.eraserMode) {
                        DrawToolbarButton(
                            label = stringResource(R.string.ink_eraser_size_title),
                            active = picker == Picker.EraserSize,
                            caption = stringResource(R.string.ink_eraser_size_short, (prefs.eraserRadius * 2f).toInt()),
                            onClick = { ctl.picker = if (picker == Picker.EraserSize) Picker.None else Picker.EraserSize },
                        ) {
                            Box(
                                Modifier.size((8f + prefs.eraserRadius * 0.4f).dp)
                                    .border(1.5.dp, if (picker == Picker.EraserSize) c.toolAccent else c.icon, RoundedCornerShape(50)),
                            )
                        }
                    } else {
                        DrawToolbarButton(
                            label = stringResource(R.string.toolbar_color_drawing),
                            active = picker == Picker.Color,
                            caption = stringResource(R.string.ink_toolbar_color),
                            onClick = { ctl.picker = if (picker == Picker.Color) Picker.None else Picker.Color },
                        ) {
                            Box(
                                Modifier.size(23.dp).border(1.5.dp, c.icon, RoundedCornerShape(50))
                                    .padding(3.dp).background(color, RoundedCornerShape(50)),
                            )
                        }
                    }
                    DrawToolbarButton(
                        label = stringResource(R.string.ink_pen_settings_title),
                        active = picker == Picker.Presets,
                        caption = stringResource(R.string.ink_toolbar_settings),
                        onClick = { ctl.picker = if (picker == Picker.Presets) Picker.None else Picker.Presets },
                    ) {
                        Icon(LucideIcons.SlidersHorizontal, null, tint = if (picker == Picker.Presets) c.toolAccent else c.icon, modifier = Modifier.size(24.dp))
                    }
                    DrawShapeButton(ctl, prefs, InkShapeMode.Line, LucideIcons.Minus, stringResource(R.string.toolbar_draw_line))
                    DrawShapeButton(ctl, prefs, InkShapeMode.Rectangle, LucideIcons.Square, stringResource(R.string.toolbar_draw_rectangle))
                    DrawShapeButton(ctl, prefs, InkShapeMode.Oval, LucideIcons.Circle, stringResource(R.string.toolbar_draw_oval))
                    DrawShapeButton(ctl, prefs, InkShapeMode.Arrow, LucideIcons.ArrowUpRight, stringResource(R.string.toolbar_draw_arrow))
                    DrawShapeButton(ctl, prefs, InkShapeMode.Cloud, LucideIcons.Cloud, stringResource(R.string.toolbar_draw_cloud))
                    DrawToolbarButton(
                        label = stringResource(R.string.ink_ruler),
                        active = ctl.rulerOn,
                        caption = stringResource(R.string.ink_ruler),
                        onClick = { ctl.rulerOn = !ctl.rulerOn },
                    ) { Icon(LucideIcons.Ruler, null, tint = if (ctl.rulerOn) c.toolAccent else c.icon, modifier = Modifier.size(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DrawShapeButton(
    ctl: ViewerController,
    prefs: ToolPrefs,
    shape: InkShapeMode,
    icon: ImageVector,
    label: String,
) {
    val c = pdfColors
    val selected = ctl.inkShapeMode == shape && !ctl.eraserMode && !ctl.lassoMode
    val shortLabel = stringResource(when (shape) {
        InkShapeMode.Line -> R.string.ink_toolbar_line
        InkShapeMode.Rectangle -> R.string.ink_toolbar_rectangle
        InkShapeMode.Oval -> R.string.ink_toolbar_oval
        InkShapeMode.Arrow -> R.string.ink_toolbar_arrow
        InkShapeMode.Cloud -> R.string.ink_toolbar_cloud
        InkShapeMode.Freehand -> R.string.toolbar_draw_freeform
    })
    DrawToolbarButton(label, selected, caption = shortLabel, onClick = {
        prefs.selectWritingPen()
        ctl.inkShapeMode = shape
        ctl.eraserMode = false
        ctl.lassoMode = false
        ctl.picker = Picker.None
    }) {
        Icon(icon, null, tint = if (selected) c.toolAccent else c.icon, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun DrawToolbarButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    caption: String = label,
    onClickLabel: String? = null,
    icon: @Composable () -> Unit,
) {
    val c = pdfColors
    val shape = RoundedCornerShape(10.dp)
    val tint = if (active) c.toolAccent else c.label
    Column(
        Modifier.width(DrawToolSlot).height(54.dp)
            .background(if (active) c.pressed else Color.Transparent, shape)
            .then(if (active) Modifier.border(1.dp, c.toolAccent.copy(alpha = 0.5f), shape) else Modifier)
            .clickable(role = Role.RadioButton, onClickLabel = onClickLabel, onClick = onClick)
            .semantics {
                contentDescription = label
                selected = active
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.height(33.dp), contentAlignment = Alignment.Center) { icon() }
        Text(
            caption,
            color = tint,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 11.sp,
        )
    }
}
