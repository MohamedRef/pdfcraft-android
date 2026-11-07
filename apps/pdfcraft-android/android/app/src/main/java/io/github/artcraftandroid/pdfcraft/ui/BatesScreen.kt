package io.github.artcraftandroid.pdfcraft.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.BatesSettings

/** Batch numbering acts on saved, system-picked PDFs and creates separate download copies. */
@Composable
fun BatesScreen(vm: AppViewModel) {
    val c = pdfColors
    var start by remember { mutableStateOf("1") }
    var digits by remember { mutableStateOf("6") }
    var prefix by remember { mutableStateOf("") }
    var suffix by remember { mutableStateOf("") }
    val settings = BatesSettings.parse(start, digits, prefix, suffix)
    val running = vm.batesRunning
    BackHandler { vm.closeBates() }
    Column(Modifier.fillMaxSize().background(c.surface).safeTopPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            BarIcon(LucideIcons.ArrowLeft, stringResource(R.string.common_back), vm::closeBates)
            Text(stringResource(R.string.bates_title), color = c.text, fontSize = 20.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.bates_about), color = c.label)
            Text(stringResource(R.string.bates_limits), color = c.label, fontSize = 13.sp)
            Text(stringResource(R.string.bates_passwords), color = c.label, fontSize = 13.sp)
            OutlinedTextField(start, { start = it }, enabled = !running, singleLine = true,
                label = { Text(stringResource(R.string.bates_start)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(digits, { digits = it }, enabled = !running, singleLine = true,
                label = { Text(stringResource(R.string.bates_digits)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(prefix, { prefix = it }, enabled = !running, singleLine = true,
                label = { Text(stringResource(R.string.bates_prefix)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(suffix, { suffix = it }, enabled = !running, singleLine = true,
                label = { Text(stringResource(R.string.bates_suffix)) }, modifier = Modifier.fillMaxWidth())
            if (settings != null) Text(stringResource(R.string.bates_preview, settings.preview), color = c.text)
            else Text(stringResource(R.string.bates_invalid), color = c.red)
            TextButton(onClick = vm::pickBatesFiles, enabled = !running) { Text(stringResource(R.string.tools_add_files)) }
            vm.batesFiles.forEachIndexed { i, file ->
                Column(Modifier.fillMaxWidth()) {
                    Text("${i + 1}. ${file.name}", color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row {
                        TextButton(onClick = { vm.moveBatesFile(i, -1) }, enabled = !running && i > 0) { Text(stringResource(R.string.bates_up)) }
                        TextButton(onClick = { vm.moveBatesFile(i, 1) }, enabled = !running && i + 1 < vm.batesFiles.size) { Text(stringResource(R.string.bates_down)) }
                        TextButton(onClick = { vm.removeBatesFile(i) }, enabled = !running) { Text(stringResource(R.string.bates_remove)) }
                    }
                }
            }
            if (vm.batesStatus.isNotEmpty()) Text(vm.batesStatus, color = c.text)
            vm.batesResults.forEach { result ->
                Text(result.name, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(result.message, color = if (result.uri != null) c.label else c.red, fontSize = 13.sp)
                result.uri?.let { uri ->
                    TextButton(onClick = { vm.closeBates(); vm.open(uri) }, enabled = !running) { Text(stringResource(R.string.common_open)) }
                }
            }
        }
        if (running) {
            TextButton(onClick = vm::closeBates, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_cancel)) }
        } else {
            Button(onClick = { vm.runBates(start, digits, prefix, suffix) }, enabled = settings != null && vm.batesFiles.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(stringResource(R.string.bates_run))
            }
        }
    }
}
