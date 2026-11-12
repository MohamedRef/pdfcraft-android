package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.MarginSide
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.home.ChoiceChip
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/**
 * Add room for notes beside the content of [pages] (0-based; the whole document when empty):
 * on the right, below, or both, a quarter, half or whole page wide. The content keeps its place,
 * and one undo takes the margin away again. Remove takes off margins added this way.
 */
@Composable
fun NoteMarginSheet(vm: AppViewModel, doc: OpenDocument, pages: List<Int>, onDismiss: () -> Unit) {
    val c = pdfColors
    var side by remember { mutableStateOf(MarginSide.Right) }
    var fraction by remember { mutableStateOf(0.5f) }
    var all by remember { mutableStateOf(pages.isEmpty()) }
    var hasMargins by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(doc.info.generation) { hasMargins = vm.pageMargins(doc).any { it } }
    PdfDialog(
        onDismiss = onDismiss, title = stringResource(R.string.margin_title), confirm = stringResource(R.string.margin_add),
        onConfirm = { onDismiss(); vm.addNoteMargin(doc, if (all) emptyList() else pages, side, fraction) },
    ) {
        Text(stringResource(R.string.margin_side), color = c.textSecondary, fontSize = 13.sp)
        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((s, label) in listOf(MarginSide.Right to R.string.margin_right, MarginSide.Bottom to R.string.margin_bottom, MarginSide.Both to R.string.margin_both)) {
                ChoiceChip(stringResource(label), side == s, { side = s })
            }
        }
        Text(stringResource(R.string.margin_size), color = c.textSecondary, fontSize = 13.sp)
        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (f in listOf(0.25f, 0.5f, 1f)) ChoiceChip("${(f * 100).toInt()} %", fraction == f, { fraction = f })
        }
        if (pages.isNotEmpty()) {
            Text(stringResource(R.string.margin_pages), color = c.textSecondary, fontSize = 13.sp)
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceChip(androidx.compose.ui.res.pluralStringResource(R.plurals.margin_selected_pages, pages.size, pages.size), !all, { all = false })
                ChoiceChip(stringResource(R.string.margin_all_pages), all, { all = true })
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.margin_body), color = c.textSecondary, fontSize = 12.sp)
        if (hasMargins) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.margin_remove), color = c.accent, fontSize = 15.sp,
                modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { confirmRemove = true }.padding(vertical = 8.dp),
            )
        }
    }
    if (confirmRemove) PdfDialog(
        onDismiss = { confirmRemove = false }, title = stringResource(R.string.margin_remove),
        confirm = stringResource(R.string.margin_remove_confirm), destructive = true,
        onConfirm = { confirmRemove = false; onDismiss(); vm.removeNoteMargins(doc, if (all) emptyList() else pages) },
    ) { Text(stringResource(R.string.margin_remove_body), color = c.text, fontSize = 14.sp) }
}
