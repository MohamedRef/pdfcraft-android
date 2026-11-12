package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.ScanModelInfo
import io.github.artcraftandroid.pdfcraft.core.ScanModelState
import io.github.artcraftandroid.pdfcraft.core.scanModelStates
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.engine.Engine
import io.github.artcraftandroid.pdfcraft.engine.ModelDownload
import io.github.artcraftandroid.pdfcraft.engine.ModelPack
import io.github.artcraftandroid.pdfcraft.engine.PackOutcome
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import io.github.artcraftandroid.pdfcraft.ui.SheetRow
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import io.github.artcraftandroid.pdfcraft.ui.viewer.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The name shown for a scanner model (#289); null for one this build has no name for (its file name is shown). */
internal fun scanModelName(id: String): Int? = when (id) {
    "edges" -> R.string.scan_model_edges
    "flatten" -> R.string.scan_model_flatten
    "upright" -> R.string.scan_model_upright
    else -> null
}

/** The models' states for a kept scan's page file: `{"edges": "ready", …}`; null when they could not be read. */
internal fun scanModelsJson(states: List<ScanModelInfo>?): org.json.JSONObject? = states?.let { list ->
    org.json.JSONObject().also { out -> for (m in list) out.put(m.id, m.state.name.lowercase()) }
}

/** What a model's state is called on the Models list; an optional model that is missing is "Not downloaded". */
internal fun scanModelStateText(state: ScanModelState, bundled: Boolean = true): Int = when (state) {
    ScanModelState.READY -> R.string.scan_model_ready
    ScanModelState.MISSING -> if (bundled) R.string.scan_model_missing else R.string.scan_model_not_downloaded
    ScanModelState.CORRUPT -> R.string.scan_model_corrupt
}

/** An optional model as [ModelDownload] fetches it; null for a bundled one. */
internal fun scanModelPack(m: ScanModelInfo): ModelPack? =
    if (m.bundled || m.url.isEmpty()) null else ModelPack(m.id, m.file, m.url, m.bytes.toLong(), m.sha256)

/**
 * Settings, Scanner models (#289): each model the scanner uses, its size, licence and whether the
 * engine accepts it. Opening the list first brings the models folder up to date with the app
 * ([Engine.prepareModels]), so it shows what the next scan will find. While the diagnostics log is
 * recorded, Copy the models again is the testing option: every file is copied out of the app anew.
 * An optional model (none yet) gets Download with its size, a progress bar and Cancel while it
 * downloads, and Remove once it is in ([ModelDownload]). The download belongs to this screen:
 * leaving it cancels, and what was saved is resumed next time.
 */
@Composable
internal fun ScanModelsSection(engine: Engine?, testing: Boolean) {
    val c = pdfColors
    val app = LocalContext.current.applicationContext
    var models by remember { mutableStateOf<List<ScanModelInfo>?>(null) }
    var copied by remember { mutableStateOf<Int?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var force by remember { mutableStateOf(false) }
    // optional models (#289 section 4): the one downloading, its percent, and the last one that failed
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf<String?>(null) }
    var percent by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf<String?>(null) }
    val cancel = remember { AtomicBoolean(false) }
    // leaving the screen stops a download; its saved bytes stay for next time
    DisposableEffect(Unit) { onDispose { cancel.set(true) } }
    val dir = File(app.filesDir, "models")
    fun fetch(pack: ModelPack) {
        if (downloading != null) return
        downloading = pack.id
        failed = null
        percent = 0
        cancel.set(false)
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                // the engine reads this folder only once it has been pointed at it
                runCatching { engine?.prepareModels(app) }
                ModelDownload.download(dir, pack, cancelled = { cancel.get() }, progress = { p -> scope.launch { percent = p } })
            }
            Diag.event(DiagArea.Scan, "model_download", *r.diag(pack.id))
            if (r.outcome == PackOutcome.Failed) failed = pack.id
            downloading = null
            refresh++
        }
    }
    fun remove(pack: ModelPack) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                // a loaded copy is dropped too, so the feature stops using it now
                runCatching { engine?.trimMemory() }
                ModelDownload.remove(dir, pack)
            }
            Diag.event(DiagArea.Scan, "model_removed", "id" to pack.id, "ok" to ok)
            refresh++
        }
    }
    LaunchedEffect(engine, refresh) {
        val eng = engine ?: return@LaunchedEffect
        val again = force
        val result = withContext(Dispatchers.IO) {
            val prepared = runCatching { eng.prepareModels(app, force = again) }.getOrNull()
            prepared to runCatching { scanModelStates() }.getOrNull()
        }
        if (again) copied = result.first?.report?.copied?.size ?: 0
        force = false
        models = result.second
    }

    Text(stringResource(R.string.scan_models_section), color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
    for (m in models.orEmpty()) {
        val name = scanModelName(m.id)?.let { stringResource(it) } ?: m.file
        val line = stringResource(R.string.scan_model_line, formatSize(m.bytes.toLong()), m.licence, stringResource(scanModelStateText(m.state, m.bundled)))
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(name, color = c.text, fontSize = 16.sp)
            Text(line, color = if (m.state == ScanModelState.READY || !m.bundled) c.textSecondary else c.accent, fontSize = 13.sp)
        }
        val pack = scanModelPack(m)
        if (pack != null) {
            when {
                downloading == m.id -> {
                    LinearProgressIndicator(progress = { percent / 100f }, color = c.accent, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                    Text(stringResource(R.string.scan_model_downloading, percent), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
                    SheetRow(null, stringResource(R.string.scan_model_cancel), { cancel.set(true) })
                }
                m.state == ScanModelState.READY -> SheetRow(null, stringResource(R.string.scan_model_remove), { remove(pack) })
                else -> SheetRow(null, stringResource(R.string.scan_model_download, formatSize(m.bytes.toLong())), { fetch(pack) }, enabled = downloading == null)
            }
        }
        if (failed == m.id) Text(stringResource(R.string.scan_model_download_failed), color = c.accent, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
    }
    Text(stringResource(R.string.scan_models_body), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
    if (testing && engine != null) {
        SheetRow(null, stringResource(R.string.scan_models_copy_again), {
            force = true
            refresh++
        })
        copied?.let { Text(stringResource(R.string.scan_models_copied, it), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
    }
}
