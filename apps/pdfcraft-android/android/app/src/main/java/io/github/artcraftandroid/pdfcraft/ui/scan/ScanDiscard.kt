package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/**
 * Whether the scan screen's Back (the arrow and Android's Back) asks before throwing the scan
 * away (#456): only with the testing option on and at least one page to lose. With no page,
 * Back leaves at once.
 */
internal fun scanBackAsks(pages: Int, lab: ScanLab): Boolean = lab.askDiscard && pages > 0

/** Whether Delete asks before removing a page from the scan (#456). */
internal fun scanDeleteAsks(pages: Int, lab: ScanLab): Boolean = lab.askDiscard && pages > 0

/** The `discard` log line's fields (#456): the pages the scan had, whether the person was asked, and whether it went. */
internal fun scanDiscardDiag(pages: Int, asked: Boolean, confirmed: Boolean): Array<Pair<String, Any?>> =
    arrayOf("pages" to pages, "asked" to asked, "confirmed" to confirmed)

/**
 * The `page_delete` log line's fields (#456): [index] counts from 0 here and from 1 in the log
 * (#445), [pages] is the scan's page count before the delete.
 */
internal fun scanPageDeleteDiag(index: Int, pages: Int, asked: Boolean, confirmed: Boolean): Array<Pair<String, Any?>> =
    arrayOf("index" to index + 1, "pages" to pages, "asked" to asked, "confirmed" to confirmed)

/** "Discard this scan?" (#456): Keep scanning is the safe way out, and tapping outside keeps the scan too. */
@Composable
internal fun ScanDiscardDialog(pages: Int, onKeep: () -> Unit, onDiscard: () -> Unit) {
    PdfDialog(
        onDismiss = onKeep,
        title = stringResource(R.string.scan_discard_title),
        confirm = stringResource(R.string.scan_discard),
        onConfirm = onDiscard,
        dismiss = stringResource(R.string.scan_keep_scanning),
        destructive = true,
    ) {
        Text(pluralStringResource(R.plurals.scan_discard_body, pages, pages), color = pdfColors.text, fontSize = 15.sp)
    }
}

/** "Delete page N?" (#456); [index] counts from 0. The photo is deleted only when the person confirms. */
@Composable
internal fun ScanDeletePageDialog(index: Int, onKeep: () -> Unit, onDelete: () -> Unit) {
    PdfDialog(
        onDismiss = onKeep,
        title = stringResource(R.string.scan_delete_page_title, index + 1),
        confirm = stringResource(R.string.common_delete),
        onConfirm = onDelete,
        destructive = true,
    ) {
        Text(stringResource(R.string.scan_delete_page_body), color = pdfColors.text, fontSize = 15.sp)
    }
}
