package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.BackupVersion
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.SheetRow
import io.github.artcraftandroid.pdfcraft.ui.SheetTitle
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/**
 * The versions of this document in the backup folder, newest first. A version opens as a new
 * copy (the document and the backup stay as they are), or only its comments are added to the
 * open document, which one undo takes back.
 */
@Composable
fun VersionHistorySheet(vm: AppViewModel, doc: OpenDocument, onDismiss: () -> Unit) {
    val c = pdfColors
    val context = LocalContext.current
    LaunchedEffect(doc) { vm.loadBackupVersions(doc) }
    PdfSheet(onDismiss = onDismiss) {
        SheetTitle(stringResource(R.string.backup_history))
        val versions = vm.backupVersions
        when {
            versions == null -> Text(stringResource(R.string.backup_loading), color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            versions.isEmpty() -> Text(stringResource(R.string.backup_no_versions), color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                for (v in versions) VersionRow(v, context) { action ->
                    onDismiss()
                    if (action == 0) vm.restoreBackupCopy(doc, v) else vm.restoreBackupComments(doc, v)
                }
            }
        }
        Text(stringResource(R.string.backup_history_body), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
}

@Composable
private fun VersionRow(v: BackupVersion, context: android.content.Context, onAction: (Int) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val label = DateUtils.formatDateTime(context, v.time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR) +
        " • " + Formatter.formatShortFileSize(context, v.size)
    Box {
        SheetRow(LucideIcons.Clock, label, { menu = true })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = pdfColors.surface) {
                DropdownMenuItem(text = { Text(stringResource(R.string.backup_open_copy)) }, onClick = { menu = false; onAction(0) })
                if (v.xfdf != null) DropdownMenuItem(text = { Text(stringResource(R.string.backup_restore_comments)) }, onClick = { menu = false; onAction(1) })
            }
        }
    }
}
