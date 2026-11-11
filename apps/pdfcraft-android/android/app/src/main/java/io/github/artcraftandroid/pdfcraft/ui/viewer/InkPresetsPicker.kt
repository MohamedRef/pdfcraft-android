package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import io.github.artcraftandroid.pdfcraft.ui.PdfSwitch

/**
 * Distinct original silhouettes rather than five copies of a generic grey metal barrel.
 * This is a tool identity illustration and a live preview, not a promise of textured PDF ink.
 */
@Composable
internal fun PenNibPreview(color: Color, width: Float, opacity: Float, style: InkStyle, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.width / 64f
        val cx = size.width / 2f
        val top = size.height * 0.12f
        val bottom = size.height * 0.71f
        val dark = Color(0xFF343944)
        val light = Color(0xFF969FAE)
        when (style) {
            InkStyle.Ballpoint -> {
                drawRoundRect(dark, Offset(cx - 7f * u, top), Size(14f * u, bottom - top), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f * u))
                drawRect(light, Offset(cx - 3f * u, top + 5f * u), Size(3f * u, bottom - top - 15f * u))
                drawCircle(color.copy(alpha = 1f), radius = 4f * u, center = Offset(cx, bottom + 4f * u))
            }
            InkStyle.Fountain -> {
                drawRoundRect(dark, Offset(cx - 12f * u, top), Size(24f * u, bottom - top - 10f * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * u))
                val nib = Path().apply {
                    moveTo(cx - 13f * u, bottom - 13f * u)
                    lineTo(cx + 13f * u, bottom - 13f * u)
                    lineTo(cx, bottom + 12f * u)
                    close()
                }
                drawPath(nib, Color(0xFFC8A76B))
                drawLine(dark, Offset(cx, bottom - 11f * u), Offset(cx, bottom + 8f * u), 1.4f * u)
                drawCircle(dark, radius = 2.5f * u, center = Offset(cx, bottom - 3f * u))
            }
            InkStyle.Pencil -> {
                drawRect(Color(0xFFD6B079), Offset(cx - 8f * u, top), Size(16f * u, bottom - top - 11f * u))
                drawRect(Color(0xFFF0D3A6), Offset(cx - 8f * u, top), Size(4f * u, bottom - top - 11f * u))
                val wood = Path().apply {
                    moveTo(cx - 8f * u, bottom - 11f * u)
                    lineTo(cx + 8f * u, bottom - 11f * u)
                    lineTo(cx, bottom + 12f * u)
                    close()
                }
                drawPath(wood, Color(0xFFE8CAA1))
                val lead = Path().apply {
                    moveTo(cx - 3f * u, bottom + 4f * u)
                    lineTo(cx + 3f * u, bottom + 4f * u)
                    lineTo(cx, bottom + 12f * u)
                    close()
                }
                drawPath(lead, dark)
            }
            InkStyle.Brush -> {
                drawRoundRect(Color(0xFF966846), Offset(cx - 8f * u, top), Size(16f * u, bottom - top - 14f * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(7f * u))
                drawRect(light, Offset(cx - 10f * u, bottom - 22f * u), Size(20f * u, 12f * u))
                val bristles = Path().apply {
                    moveTo(cx - 9f * u, bottom - 10f * u)
                    lineTo(cx + 9f * u, bottom - 10f * u)
                    quadraticTo(cx + 5f * u, bottom + 7f * u, cx, bottom + 16f * u)
                    quadraticTo(cx - 5f * u, bottom + 7f * u, cx - 9f * u, bottom - 10f * u)
                    close()
                }
                drawPath(bristles, color.copy(alpha = 0.9f))
            }
            InkStyle.Highlighter -> {
                drawRoundRect(color.copy(alpha = 0.88f), Offset(cx - 15f * u, top), Size(30f * u, bottom - top), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * u))
                drawRect(dark, Offset(cx - 15f * u, bottom - 13f * u), Size(30f * u, 9f * u))
                drawRect(dark, Offset(cx - 11f * u, bottom - 4f * u), Size(22f * u, 12f * u))
            }
        }
        val visibleWidth = inkStyleWidthRuns(listOf(1f, 1f), width, false, style).firstOrNull()?.width ?: width
        val inkAlpha = inkStyleOpacity(opacity, style)
        val baseline = size.height * 0.93f
        drawLine(
            color.copy(alpha = inkAlpha),
            Offset(cx - 19f * u, baseline),
            Offset(cx + 19f * u, baseline - if (style == InkStyle.Fountain || style == InkStyle.Brush) 3f * u else 0f),
            strokeWidth = (visibleWidth.coerceIn(1f, 22f) * 0.45f + 1.2f) * u,
            cap = if (style == InkStyle.Highlighter) StrokeCap.Square else StrokeCap.Round,
        )
    }
}

@Composable
internal fun InkPresetsPicker(vm: AppViewModel, onApplied: () -> Unit) {
    val prefs = vm.toolPrefs
    val c = pdfColors
    Column(
        Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.ink_pen_settings_title), color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.ink_presets_help), color = c.label, fontSize = 13.sp)

        // An enabled Pen-only setting makes every finger gesture navigate instead of ink.
        // Keep the state and the escape switch next to the actual pen controls.
        Row(
            Modifier.fillMaxWidth().toggleable(value = prefs.stylusOnly, role = Role.Switch) { prefs.updateStylusOnly(it) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.toolbar_stylus_only), color = c.text, fontSize = 14.sp)
                Text(stringResource(R.string.ink_pen_only_explanation), color = c.label, fontSize = 12.sp)
            }
            PdfSwitch(prefs.stylusOnly, null)
        }

        Row(
            Modifier.fillMaxWidth().toggleable(value = prefs.snapToShape, role = Role.Switch) { prefs.updateSnapToShape(it) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.toolbar_snap_to_shape), color = c.text, fontSize = 14.sp)
                Text(stringResource(R.string.ink_snap_explanation), color = c.label, fontSize = 12.sp)
            }
            PdfSwitch(prefs.snapToShape, null)
        }

        Row(
            Modifier.fillMaxWidth().toggleable(value = prefs.multiFingerUndo, role = Role.Switch) { prefs.updateMultiFingerUndo(it) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ink_multi_finger_undo), color = c.text, fontSize = 14.sp)
                Text(stringResource(R.string.ink_multi_finger_undo_explanation), color = c.label, fontSize = 12.sp)
            }
            PdfSwitch(prefs.multiFingerUndo, null)
        }

        Row(
            Modifier.fillMaxWidth().toggleable(value = prefs.penGestures, role = Role.Switch) { prefs.updatePenGestures(it) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pen_gestures), color = c.text, fontSize = 14.sp)
                Text(stringResource(R.string.pen_gestures_explanation), color = c.label, fontSize = 12.sp)
            }
            PdfSwitch(prefs.penGestures, null)
        }
        // Writing hand and palm guard (#156).
        Text(stringResource(R.string.hand_title), color = c.text, fontSize = 14.sp)
        OptionChips(listOf(false, true), prefs.leftHanded, { left ->
            prefs.updateLeftHanded(left)
            // The quick rail moves away from the writing hand; it can still be dragged anywhere.
            vm.quickToolbar.updateRailRight(left)
        }) { stringResource(if (it) R.string.hand_left else R.string.hand_right) }
        Row(
            Modifier.fillMaxWidth().toggleable(value = prefs.palmGuard, role = Role.Switch) { prefs.updatePalmGuard(it) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.palm_guard), color = c.text, fontSize = 14.sp)
                Text(stringResource(R.string.palm_guard_explanation), color = c.label, fontSize = 12.sp)
            }
            PdfSwitch(prefs.palmGuard, null)
        }
        if (prefs.palmGuard) {
            Text(stringResource(R.string.palm_guard_size, prefs.palmGuardSizeDp.toInt()), color = c.label, fontSize = 12.sp)
            Slider(
                value = prefs.palmGuardSizeDp,
                onValueChange = { prefs.updatePalmGuardSizeDp(Math.round(it / 10f) * 10f) },
                valueRange = 60f..240f,
                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
            )
        }
        // S Pen side button (#144): what holding it while writing does, and what a click while
        // hovering does. The eraser end of a pen always erases.
        Text(stringResource(R.string.pen_button_hold_title), color = c.text, fontSize = 14.sp)
        OptionChips(
            PenButtonHold.entries, prefs.penButtonHold, { prefs.updatePenButtonHold(it) },
        ) { stringResource(when (it) {
            PenButtonHold.Eraser -> R.string.pen_button_hold_eraser
            PenButtonHold.Highlighter -> R.string.pen_button_hold_highlighter
            PenButtonHold.None -> R.string.pen_button_none
        }) }
        Text(stringResource(R.string.pen_button_click_title), color = c.text, fontSize = 14.sp)
        OptionChips(
            PenButtonClick.entries, prefs.penButtonClick, { prefs.updatePenButtonClick(it) },
        ) { stringResource(when (it) {
            PenButtonClick.None -> R.string.pen_button_none
            PenButtonClick.Undo -> R.string.common_undo
            PenButtonClick.TogglePens -> R.string.pen_button_click_toggle
        }) }

        // Any number of favorites (#149): tap uses one, long press replaces, moves or deletes it.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            prefs.inkPresets.forEachIndexed { index, preset ->
                FavoritePenCard(vm, index, preset, onApplied)
            }
            val add = stringResource(R.string.ink_preset_add)
            Column(
                Modifier.width(96.dp).height(150.dp)
                    .border(1.dp, c.divider, RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button, enabled = prefs.inkPresets.size < InkPreset.MAX_PRESETS) {
                        vm.tell(if (prefs.addInkPreset()) text(R.string.ink_preset_saved, prefs.inkPresets.size) else text(R.string.ink_preset_invalid))
                    }
                    .semantics { contentDescription = add }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(LucideIcons.Plus, null, tint = c.icon, modifier = Modifier.size(24.dp))
                Text(add, color = c.text, fontSize = 11.sp, maxLines = 2)
            }
        }
        Text(stringResource(R.string.ink_preset_save_help, stringResource(inkStyleLabel(prefs.inkStyle))), color = c.label, fontSize = 12.sp)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FavoritePenCard(vm: AppViewModel, index: Int, preset: InkPreset, onApplied: () -> Unit) {
    val prefs = vm.toolPrefs
    val c = pdfColors
    val chosen = prefs.currentInkPreset() == preset
    val label = stringResource(R.string.ink_preset_slot, index + 1)
    val actions = stringResource(R.string.ink_preset_actions)
    var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        Column(
            Modifier.width(96.dp)
                .border(if (chosen) 2.dp else 1.dp, if (chosen) c.accent else c.divider, RoundedCornerShape(14.dp))
                .background(if (chosen) c.pressed else c.surface, RoundedCornerShape(14.dp))
                .combinedClickable(
                    role = Role.RadioButton,
                    onLongClickLabel = actions,
                    onLongClick = { menu = true },
                    onClick = { if (prefs.applyInkPreset(index)) onApplied() },
                )
                .semantics {
                    contentDescription = label
                    selected = chosen
                }
                .padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PenNibPreview(Color(preset.argb), preset.width, preset.opacity, preset.style, Modifier.size(width = 46.dp, height = 72.dp))
            Text(stringResource(inkStyleLabel(preset.style)), color = c.text, fontSize = 12.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            Text(label, color = c.label, fontSize = 11.sp)
            Text(stringResource(R.string.ink_preset_details, preset.width, (preset.opacity * 100).toInt()),
                color = c.label, fontSize = 10.sp, maxLines = 1)
        }
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.menu) {
            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.ink_preset_save), color = c.text) }, onClick = {
                menu = false
                vm.tell(if (prefs.saveInkPreset(index)) text(R.string.ink_preset_saved, index + 1) else text(R.string.ink_preset_invalid))
            })
            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.ink_preset_move_earlier), color = c.text) },
                enabled = index > 0, onClick = { menu = false; prefs.moveInkPreset(index, -1) })
            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.ink_preset_move_later), color = c.text) },
                enabled = index < prefs.inkPresets.lastIndex, onClick = { menu = false; prefs.moveInkPreset(index, 1) })
            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.ink_preset_delete), color = c.text) }, onClick = {
                menu = false
                prefs.deleteInkPreset(index)
            })
        }
    }
}

/**
 * The selected pen's own colour, width and opacity (#188), opened by tapping the already selected
 * pen. Changes apply only to that pen and only to strokes drawn afterwards.
 */
@Composable
internal fun PenStylePicker(vm: AppViewModel) {
    val prefs = vm.toolPrefs
    val c = pdfColors
    val style = prefs.inkStyle
    val pen = prefs.penSettings(style)
    val color = Color(pen.argb)
    Column(
        Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(inkStyleLabel(style)), color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.toolbar_line_thickness), color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.ink_width_value, pen.width), color = c.text, fontSize = 14.sp)
        }
        // Quick width chips, then a fine slider in half-point steps.
        InkWidthRow(color, pen.width) { prefs.updateInkWidth(it) }
        Slider(
            value = pen.width.coerceIn(InkPenSettings.MIN_WIDTH, InkPenSettings.MAX_WIDTH),
            // 0.1 pt steps from 0.1 to 20 pt (#149).
            onValueChange = { prefs.updateInkWidth(Math.round(it * 10f).coerceIn(1, 200) / 10f) },
            valueRange = InkPenSettings.MIN_WIDTH..InkPenSettings.MAX_WIDTH,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
        )
        Canvas(Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 12.dp)) {
            val lineWidth = inkStyleWidthRuns(listOf(1f, 1f), pen.width, false, style).firstOrNull()?.width ?: pen.width
            drawLine(color.copy(alpha = inkStyleOpacity(pen.opacity, style)),
                Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                strokeWidth = lineWidth.dp.toPx(), cap = if (style == InkStyle.Highlighter) StrokeCap.Square else StrokeCap.Round)
        }
        Text(stringResource(R.string.toolbar_color_drawing), color = c.text, fontSize = 14.sp)
        if (prefs.recentInkColors.isNotEmpty()) {
            Text(stringResource(R.string.ink_recent_colors), color = c.label, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                prefs.recentInkColors.forEach { argb ->
                    val name = stringResource(R.string.ink_color_hex_name, "#%06X".format(argb and 0xFFFFFF))
                    val isSel = argb == pen.argb
                    Box(
                        Modifier.size(32.dp)
                            .border(if (isSel) 2.dp else 1.dp, if (isSel) c.accent else c.divider, RoundedCornerShape(50))
                            .padding(3.dp).background(Color(argb), RoundedCornerShape(50))
                            .selectable(selected = isSel, role = Role.RadioButton) { prefs.setColor(StyledTool.Ink, Color(argb)) }
                            .semantics { contentDescription = name },
                    )
                }
            }
        }
        ColorGrid(color) { prefs.setColor(StyledTool.Ink, it) }
        HexColorField(pen.argb) { prefs.setColor(StyledTool.Ink, Color(it)) }
        TextButton(onClick = {
            // Close the sheet; the next tap on the page samples a stroke's colour.
            vm.document?.controller?.let { it.inkEyedropper = true; it.picker = Picker.None }
            vm.tell(text(R.string.ink_eyedropper_hint))
        }) { Text(stringResource(R.string.ink_eyedropper), color = c.accent) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.toolbar_opacity), color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("${(pen.opacity * 100).toInt()}%", color = c.text, fontSize = 13.sp)
        }
        OpacitySlider(color, pen.opacity) { prefs.setOpacity(StyledTool.Ink, it) }
        Spacer(Modifier.height(8.dp))
    }
}

/** A single-choice row of small chips, announced as radio buttons. */
@Composable
private fun <T> OptionChips(options: List<T>, selected: T, onPick: (T) -> Unit, label: @Composable (T) -> String) {
    val c = pdfColors
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val isSel = option == selected
            Box(
                Modifier.heightIn(min = 48.dp)
                    .border(if (isSel) 2.dp else 1.dp, if (isSel) c.accent else c.divider, RoundedCornerShape(18.dp))
                    .background(if (isSel) c.pressed else c.surface, RoundedCornerShape(18.dp))
                    .selectable(selected = isSel, role = Role.RadioButton) { onPick(option) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label(option), color = c.text, fontSize = 13.sp, fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}

/** `#RRGGBB` / `#AARRGGBB` entry; invalid text is rejected with a message (#149). */
@Composable
private fun HexColorField(current: Int, onColor: (Int) -> Unit) {
    val c = pdfColors
    var text by androidx.compose.runtime.remember(current) { androidx.compose.runtime.mutableStateOf("#%06X".format(current and 0xFFFFFF)) }
    var error by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    fun apply() {
        val v = parseHexColor(text)
        error = v == null
        if (v != null) onColor(v)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(9); error = false },
            singleLine = true,
            label = { Text(stringResource(R.string.ink_hex_label)) },
            isError = error,
            supportingText = if (error) ({ Text(stringResource(R.string.ink_hex_invalid)) }) else null,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { apply() }),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { apply() }) { Text(stringResource(R.string.ink_hex_apply), color = c.accent) }
    }
}

internal fun inkStyleLabel(style: InkStyle): Int = when (style) {
    InkStyle.Ballpoint -> R.string.ink_style_ballpoint
    InkStyle.Fountain -> R.string.ink_style_fountain
    InkStyle.Pencil -> R.string.ink_style_pencil
    InkStyle.Brush -> R.string.ink_style_brush
    InkStyle.Highlighter -> R.string.ink_style_highlighter
}
