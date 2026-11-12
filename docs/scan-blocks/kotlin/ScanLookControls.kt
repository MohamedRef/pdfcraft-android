package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlin.math.roundToInt

/**
 * The controls under the looks strip for the selected look (issue #301, spec S15 section 3): the
 * strength slider (not for Original), the paper choice (White or Keep color, for Auto, Color and
 * Grayscale), the flatten choice (when the engine can flatten), and Rotate, Apply to all and Reset
 * look. Every change goes out through [onChange] as a whole new [ScanPageLook]; the screen decides
 * what to render and when.
 */
@Composable
internal fun ScanLookControls(
    look: ScanPageLook,
    canFlatten: Boolean,
    onChange: (ScanPageLook) -> Unit,
    onRotate: () -> Unit,
    onApplyToAll: () -> Unit,
    onReset: () -> Unit,
) {
    val c = pdfColors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (look.look.hasStrength) {
            val percent = stringResource(R.string.scan_look_strength_percent, look.strength)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.scan_look_strength), color = c.text, fontSize = 14.sp)
                Text(percent, color = c.textSecondary, fontSize = 14.sp)
            }
            Slider(
                value = look.strength.toFloat(),
                onValueChange = { onChange(look.withStrength(it.roundToInt())) },
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth().semantics { stateDescription = percent },
            )
        }
        if (look.look == ScanLookKind.Auto || look.look == ScanLookKind.Colour || look.look == ScanLookKind.Greyscale) {
            Text(stringResource(R.string.scan_look_paper), color = c.text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(stringResource(R.string.scan_look_paper_white), !look.keepPaper) { onChange(look.copy(keepPaper = false)) }
                Pill(stringResource(R.string.scan_look_paper_keep), look.keepPaper) { onChange(look.copy(keepPaper = true)) }
            }
        }
        if (canFlatten) {
            Text(stringResource(R.string.tools_scan_flatten), color = c.text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(stringResource(R.string.scan_look_flatten_auto), look.flatten == ScanFlattenMode.Auto) { onChange(look.copy(flatten = ScanFlattenMode.Auto)) }
                Pill(stringResource(R.string.scan_look_flatten_on), look.flatten == ScanFlattenMode.On) { onChange(look.copy(flatten = ScanFlattenMode.On)) }
                Pill(stringResource(R.string.scan_look_flatten_off), look.flatten == ScanFlattenMode.Off) { onChange(look.copy(flatten = ScanFlattenMode.Off)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = onRotate) {
                Icon(LucideIcons.RotateCw, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.scan_look_rotate), color = c.accent)
            }
            TextButton(onClick = onApplyToAll) { Text(stringResource(R.string.scan_look_apply_all), color = c.accent) }
            TextButton(onClick = onReset) { Text(stringResource(R.string.scan_look_reset), color = c.accent) }
        }
    }
}

/**
 * The confirmation before Apply to all, shown when the pages do not all have the same look already
 * (the screen checks with looksDiffer before it shows this; issue #301).
 */
@Composable
internal fun ScanApplyToAllConfirm(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    PdfDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.scan_look_apply_all_title),
        confirm = stringResource(R.string.scan_look_apply_confirm),
        onConfirm = onConfirm,
    ) {
        Text(stringResource(R.string.scan_look_apply_all_body), color = pdfColors.text, fontSize = 14.sp)
    }
}

/** A choice shown as a pill: filled and in the accent colour when it is the one picked. */
@Composable
private fun Pill(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val c = pdfColors
    Text(
        label,
        color = if (isSelected) c.accent else c.text,
        fontSize = 14.sp,
        modifier = Modifier
            .semantics { selected = isSelected }
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) c.pressed else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
