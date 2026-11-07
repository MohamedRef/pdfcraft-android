package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

@Composable
internal fun InkPresetsPicker(vm: AppViewModel, onApplied: () -> Unit) {
    val prefs = vm.toolPrefs
    val c = pdfColors
    Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ink_presets_title), color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.ink_presets_help), color = c.label, fontSize = 13.sp)
        prefs.inkPresets.forEachIndexed { index, preset ->
            val selected = prefs.currentInkPreset() == preset
            Text(stringResource(if (selected) R.string.ink_preset_selected else R.string.ink_preset_slot, index + 1), color = c.text, fontWeight = FontWeight.SemiBold)
            Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                drawLine(Color(preset.argb).copy(alpha = preset.opacity), Offset(4.dp.toPx(), size.height / 2), Offset(size.width - 4.dp.toPx(), size.height / 2), strokeWidth = preset.width.dp.toPx(), cap = StrokeCap.Round)
            }
            Text(stringResource(R.string.ink_preset_details, preset.width, (preset.opacity * 100).toInt()), color = c.label, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { if (prefs.applyInkPreset(index)) onApplied() }) { Text(stringResource(R.string.ink_preset_use)) }
                TextButton(onClick = {
                    vm.tell(if (prefs.saveInkPreset(index)) text(R.string.ink_preset_saved, index + 1) else text(R.string.ink_preset_invalid))
                }) { Text(stringResource(R.string.ink_preset_save)) }
            }
        }
    }
}
