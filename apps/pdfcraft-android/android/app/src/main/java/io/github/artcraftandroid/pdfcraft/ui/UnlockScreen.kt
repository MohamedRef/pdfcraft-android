package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R

/**
 * Unlock PDF (#517): the files to unlock (the open document first when it came from the viewer,
 * and any added from storage), Unlock, the progress "N of M" with Cancel between files, and what
 * came of each file and of all of them.
 */
@Composable
internal fun UnlockScreen(vm: AppViewModel) {
    val c = pdfColors
    val s = vm.unlock
    val running = s.running
    ToolPage(stringResource(R.string.unlock_title), { vm.closeUnlock() }, stringResource(R.string.unlock_run), !running && s.files.isNotEmpty(), { vm.runUnlock() }) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.unlock_intro), color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            TextButton(enabled = !running, onClick = { vm.addUnlockFiles() }) { Text(stringResource(R.string.unlock_add), color = c.accent) }
            s.progress?.let { (at, of) ->
                Text(stringResource(R.string.unlock_progress, at, of), color = c.text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(8.dp), color = c.accent)
                TextButton(onClick = { vm.cancelUnlock() }) { Text(stringResource(R.string.common_cancel), color = c.accent) }
            }
            s.summary?.takeIf { !running }?.let { sum ->
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                    if (s.cancelled) Text(stringResource(R.string.unlock_cancelled), color = c.text, fontSize = 14.sp)
                    Text(stringResource(R.string.unlock_summary, sum.unlocked, sum.skipped, sum.failed), color = c.text, fontSize = 14.sp)
                    if (sum.failedNames.isNotEmpty()) Text(stringResource(R.string.unlock_failed_names, sum.failedNames.joinToString(", ")), color = c.red, fontSize = 13.sp)
                }
            }
        }
        if (s.files.isEmpty()) Text(stringResource(R.string.unlock_empty), color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(s.files) { i, f ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = c.text, fontSize = 15.sp, maxLines = 2)
                        f.result?.let { r ->
                            val line = when (r) {
                                UnlockResult.Password -> stringResource(R.string.unlock_done_password)
                                UnlockResult.Restrictions -> stringResource(R.string.unlock_done_restrictions)
                                UnlockResult.NotProtected -> stringResource(R.string.unlock_not_protected)
                                UnlockResult.SkippedPassword -> stringResource(R.string.unlock_skipped_password)
                                UnlockResult.SkippedSigned -> stringResource(R.string.unlock_skipped_signed)
                                UnlockResult.Failed -> stringResource(R.string.unlock_failed, f.reason.orEmpty())
                            }
                            Text(line, color = if (r == UnlockResult.Failed) c.red else c.textSecondary, fontSize = 13.sp)
                        }
                    }
                    if (!running) {
                        Spacer(Modifier.width(8.dp))
                        BarIcon(LucideIcons.X, stringResource(R.string.ocr_batch_remove, f.name), { s.files.removeAt(i) })
                    }
                }
            }
        }
    }
    s.asking?.let { ask -> UnlockPasswordDialog(ask) }
    s.askingSigned?.let { ask ->
        PdfDialog(
            onDismiss = { ask.answer.complete(false) }, title = stringResource(R.string.unlock_signed_title),
            confirm = stringResource(R.string.unlock_signed_go), onConfirm = { ask.answer.complete(true) },
            dismiss = stringResource(R.string.unlock_skip), destructive = true,
        ) {
            Text(stringResource(R.string.unlock_signed_body, ask.name), color = c.text, fontSize = 14.sp)
        }
    }
}

/** A file's open password: Unlock, or Skip this file. "Use this password for the other files" saves typing it again. */
@Composable
private fun UnlockPasswordDialog(ask: UnlockAsk) {
    val c = pdfColors
    var pw by remember(ask) { mutableStateOf("") }
    var visible by remember(ask) { mutableStateOf(false) }
    var forOthers by remember(ask) { mutableStateOf(false) }
    val focus = remember(ask) { androidx.compose.ui.focus.FocusRequester() }
    PdfDialog(
        onDismiss = { ask.answer.complete(null) }, title = stringResource(R.string.unlock_password_title, ask.name),
        confirm = stringResource(R.string.unlock_run), confirmEnabled = pw.isNotEmpty(), onConfirm = { ask.answer.complete(UnlockAnswer(pw, forOthers)) },
        dismiss = stringResource(R.string.unlock_skip),
    ) {
        Text(
            stringResource(if (ask.wrong) R.string.unlock_password_wrong else R.string.unlock_password_body),
            color = if (ask.wrong) c.red else c.text, fontSize = 14.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        PasswordField(stringResource(R.string.unlock_password_field), pw, { pw = it }, visible, { visible = !visible }, ask.wrong && pw.isEmpty(), focus)
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { forOthers = !forOthers }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.unlock_password_all), color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            PdfSwitch(forOthers, onChange = { forOthers = it })
        }
    }
    androidx.compose.runtime.LaunchedEffect(ask) { runCatching { focus.requestFocus() } }
}
