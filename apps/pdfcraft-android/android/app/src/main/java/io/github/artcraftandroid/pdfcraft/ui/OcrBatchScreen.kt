package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.viewer.OcrOptions
import io.github.artcraftandroid.pdfcraft.ui.viewer.OcrOptionsDialog
import io.github.artcraftandroid.pdfcraft.ui.viewer.addOcrBatchFiles
import io.github.artcraftandroid.pdfcraft.ui.viewer.cancelOcrBatch
import io.github.artcraftandroid.pdfcraft.ui.viewer.chooseOcrBatchFolder
import io.github.artcraftandroid.pdfcraft.ui.viewer.runOcrBatch

/**
 * Recognize text in multiple files (#166): add PDFs, choose the folder the searchable copies go
 * to, then Recognize (with the same options as for one document). Each file is read in turn, with
 * the progress shown and Cancel between files, and its row then says what came of it.
 */
@Composable
fun OcrBatchScreen(vm: AppViewModel) {
    val c = pdfColors
    val s = vm.ocr
    val running = s.batchRunning
    val close = { if (s.batchRunning) vm.cancelOcrBatch() else vm.toolScreen = null }
    ToolPage(
        stringResource(R.string.ocr_batch_title), close,
        stringResource(R.string.ocr_run), !running && s.batch.isNotEmpty() && s.batchFolder != null, { s.batchOptions = true },
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !running, onClick = { vm.addOcrBatchFiles() }) { Text(stringResource(R.string.ocr_batch_add), color = c.accent) }
                TextButton(enabled = !running, onClick = { vm.chooseOcrBatchFolder() }) { Text(stringResource(R.string.ocr_batch_folder), color = c.accent) }
            }
            Text(
                s.batchFolderName?.let { stringResource(R.string.ocr_batch_folder_chosen, it) } ?: stringResource(R.string.ocr_batch_folder_none),
                color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp),
            )
            s.batchStatus?.let { status ->
                Spacer(Modifier.padding(4.dp))
                Text(status, color = c.text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp))
                if (s.batchRunning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(8.dp), color = c.accent)
                    TextButton(onClick = { vm.cancelOcrBatch() }) { Text(stringResource(R.string.common_cancel), color = c.accent) }
                }
            }
        }
        if (s.batch.isEmpty()) {
            Text(stringResource(R.string.ocr_batch_empty), color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(s.batch) { i, f ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = c.text, fontSize = 15.sp, maxLines = 2)
                        f.result?.let { Text(it, color = if (f.failed) c.red else c.textSecondary, fontSize = 13.sp) }
                    }
                    if (!running) {
                        Spacer(Modifier.width(8.dp))
                        BarIcon(LucideIcons.X, stringResource(R.string.ocr_batch_remove, f.name), { s.batch.removeAt(i) })
                    }
                }
            }
        }
    }
    if (s.batchOptions) {
        val context = LocalContext.current
        OcrOptionsDialog(
            initial = OcrOptions.load(context), languages = s.languages, pageCount = 1, batch = true,
            onDismiss = { s.batchOptions = false }, onRun = { vm.runOcrBatch(it) },
        )
    }
}
