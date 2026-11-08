package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

/**
 * Distinct original silhouettes rather than five copies of a generic grey metal barrel.
 * This is a tool identity illustration and a live preview, not a promise of textured PDF ink.
 */
@Composable
private fun PenNibPreview(color: Color, width: Float, opacity: Float, style: InkStyle, modifier: Modifier = Modifier) {
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
        Text(stringResource(R.string.ink_presets_title), color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.ink_presets_help), color = c.label, fontSize = 13.sp)

        Text(stringResource(R.string.ink_styles_title), color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InkStyle.entries.forEach { style ->
                val active = prefs.inkStyle == style
                val label = stringResource(inkStyleLabel(style))
                Column(
                    Modifier.width(104.dp)
                        .border(if (active) 2.dp else 1.dp, if (active) c.accent else c.divider, RoundedCornerShape(12.dp))
                        .background(if (active) c.pressed else c.surface, RoundedCornerShape(12.dp))
                        .clickable(role = Role.RadioButton) { prefs.updateInkStyle(style); onApplied() }
                        .semantics { contentDescription = label }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PenNibPreview(prefs.color(StyledTool.Ink), prefs.inkWidth, prefs.opacity(StyledTool.Ink), style,
                        Modifier.size(width = 54.dp, height = 72.dp))
                    Text(label, color = c.text, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        // Samsung Notes-inspired presentation, original illustrations, no borrowed assets.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            prefs.inkPresets.forEachIndexed { index, preset ->
                val chosen = prefs.currentInkPreset() == preset
                val label = stringResource(R.string.ink_preset_slot, index + 1)
                Column(
                    Modifier.weight(1f)
                        .border(if (chosen) 2.dp else 1.dp, if (chosen) c.accent else c.divider, RoundedCornerShape(14.dp))
                        .background(if (chosen) c.pressed else c.surface, RoundedCornerShape(14.dp))
                        .clickable(role = Role.RadioButton) { if (prefs.applyInkPreset(index)) onApplied() }
                        .semantics { contentDescription = label }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PenNibPreview(Color(preset.argb), preset.width, preset.opacity, preset.style, Modifier.size(width = 46.dp, height = 72.dp))
                    Text(stringResource(inkStyleLabel(preset.style)), color = c.text, fontSize = 12.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
                    Text(stringResource(R.string.ink_preset_slot, index + 1), color = c.label, fontSize = 11.sp)
                    Text(stringResource(R.string.ink_preset_details, preset.width, (preset.opacity * 100).toInt()),
                        color = c.label, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
        // Large visual size preview and an adjustable slider instead of seven cramped bars.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.toolbar_line_thickness), color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.ink_width_value, prefs.inkWidth), color = c.text, fontSize = 14.sp)
        }
        Slider(
            value = prefs.inkWidth.coerceIn(1f, 10f),
            onValueChange = { prefs.updateInkWidth((it * 2f).toInt().coerceIn(2, 20) / 2f) },
            valueRange = 1f..10f,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
        )
        Canvas(Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 12.dp)) {
            val lineWidth = inkStyleWidthRuns(listOf(1f, 1f), prefs.inkWidth, false, prefs.inkStyle).firstOrNull()?.width ?: prefs.inkWidth
            drawLine(prefs.color(StyledTool.Ink).copy(alpha = inkStyleOpacity(prefs.opacity(StyledTool.Ink), prefs.inkStyle)),
                Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                strokeWidth = lineWidth.dp.toPx(), cap = StrokeCap.Round)
        }
        Text(stringResource(R.string.toolbar_color_drawing), color = c.text, fontSize = 14.sp)
        // Reuse the exact existing swatches and state; no second colour system.
        ColorGrid(prefs.color(StyledTool.Ink)) { prefs.setColor(StyledTool.Ink, it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.toolbar_opacity), color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("${(prefs.opacity(StyledTool.Ink) * 100).toInt()}%", color = c.text, fontSize = 13.sp)
        }
        OpacitySlider(prefs.color(StyledTool.Ink), prefs.opacity(StyledTool.Ink)) { prefs.setOpacity(StyledTool.Ink, it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            prefs.inkPresets.indices.forEach { index ->
                TextButton(modifier = Modifier.weight(1f), onClick = {
                    vm.tell(if (prefs.saveInkPreset(index)) text(R.string.ink_preset_saved, index + 1) else text(R.string.ink_preset_invalid))
                }) {
                    Text(stringResource(R.string.ink_preset_save_short, index + 1), fontSize = 12.sp)
                }
            }
        }
    }
}

private fun inkStyleLabel(style: InkStyle): Int = when (style) {
    InkStyle.Ballpoint -> R.string.ink_style_ballpoint
    InkStyle.Fountain -> R.string.ink_style_fountain
    InkStyle.Pencil -> R.string.ink_style_pencil
    InkStyle.Brush -> R.string.ink_style_brush
    InkStyle.Highlighter -> R.string.ink_style_highlighter
}
