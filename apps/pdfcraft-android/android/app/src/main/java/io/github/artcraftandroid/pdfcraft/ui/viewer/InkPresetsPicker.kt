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

/** Original nib illustration: ink-coloured tip, two-tone barrel, live sample stroke. */
@Composable
private fun PenNibPreview(color: Color, width: Float, opacity: Float, style: InkStyle, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.width / 64f
        val center = size.width / 2f
        val tipY = size.height * (if (style == InkStyle.Highlighter) 0.24f else 0.15f)
        val bodyTop = size.height * 0.43f
        val bodyBottom = size.height * 0.78f
        val nibHalfWidth = when (style) {
            InkStyle.Pencil -> 6f
            InkStyle.Fountain -> 12f
            InkStyle.Brush -> 11f
            InkStyle.Highlighter -> 15f
            InkStyle.Ballpoint -> 8f
        }
        val nib = Path().apply {
            moveTo(center, tipY)
            lineTo(center - nibHalfWidth * u, bodyTop)
            lineTo(center + nibHalfWidth * u, bodyTop)
            close()
        }
        drawPath(nib, Color(0xFF484A50))
        drawLine(Color(0xFF9DA3AD), Offset(center, tipY + 3f * u), Offset(center, bodyTop - 2f * u), 1.1f * u)
        drawRect(Color(0xFF6E747E), Offset(center - 12f * u, bodyTop), androidx.compose.ui.geometry.Size(24f * u, bodyBottom - bodyTop))
        drawRect(Color(0xFFB1B5BD), Offset(center - 12f * u, bodyTop), androidx.compose.ui.geometry.Size(8f * u, bodyBottom - bodyTop))
        drawRect(Color(0xFF363A41), Offset(center - 13f * u, bodyTop), androidx.compose.ui.geometry.Size(26f * u, 4f * u))
        val visibleWidth = inkStyleWidthRuns(listOf(1f, 1f), width, false, style).firstOrNull()?.width ?: width
        drawLine(color.copy(alpha = inkStyleOpacity(opacity, style)), Offset(center - 15f * u, size.height * 0.91f),
            Offset(center + 15f * u, size.height * 0.91f),
            strokeWidth = (visibleWidth.coerceIn(1f, 22f) * 0.4f + 1.8f) * u, cap = StrokeCap.Round)
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
                    Modifier.width(92.dp)
                        .border(if (active) 2.dp else 1.dp, if (active) c.accent else c.divider, RoundedCornerShape(12.dp))
                        .background(if (active) c.pressed else c.surface, RoundedCornerShape(12.dp))
                        .clickable(role = Role.RadioButton) { prefs.updateInkStyle(style) }
                        .semantics { contentDescription = label }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PenNibPreview(prefs.color(StyledTool.Ink), prefs.inkWidth, prefs.opacity(StyledTool.Ink), style,
                        Modifier.size(width = 38.dp, height = 55.dp))
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
                    PenNibPreview(Color(preset.argb), preset.width, preset.opacity, preset.style, Modifier.size(width = 56.dp, height = 86.dp))
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
            Text("${prefs.inkWidth.toInt()} pt", color = c.text, fontSize = 14.sp)
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
