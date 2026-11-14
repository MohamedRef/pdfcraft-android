package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/** "Remove the markup on N pages?" once before a borders pass (#515). Keep, the default, leaves those pages out. */
@Composable
internal fun ScanBatchMarksDialog(pages: Int, onKeep: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(pluralStringResource(R.plurals.scan_batch_marks_title, pages, pages)) },
        text = { Text(stringResource(R.string.scan_batch_marks_body)) },
        confirmButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.scan_markup_ask_keep)) } },
        dismissButton = { TextButton(onClick = onRemove) { Text(stringResource(R.string.scan_markup_ask_remove)) } },
    )
}

/** The document screen's line after an import of several photos (#515): "Check the borders of N pages", Check, and a close. */
@Composable
internal fun ScanBatchOfferRow(pages: Int, onCheck: () -> Unit, onDismiss: () -> Unit) {
    val c = pdfColors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceRaised).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(pluralStringResource(R.plurals.scan_batch_offer, pages, pages), color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onCheck) { Text(stringResource(R.string.scan_batch_check), color = c.accent) }
        BarIcon(LucideIcons.X, stringResource(R.string.scan_enhance_dismiss), onDismiss)
    }
}
