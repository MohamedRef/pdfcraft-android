package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.artcraftandroid.pdfcraft.engine.Engine
import io.github.artcraftandroid.pdfcraft.ui.SheetRow
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import io.github.artcraftandroid.pdfcraft.ui.viewer.formatSize
import kotlinx.coroutines.Dispatchers
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

/** What a model's state is called on the Models list. */
internal fun scanModelStateText(state: ScanModelState): Int = when (state) {
    ScanModelState.READY -> R.string.scan_model_ready
    ScanModelState.MISSING -> R.string.scan_model_missing
    ScanModelState.CORRUPT -> R.string.scan_model_corrupt
}

/**
 * Settings, Scanner models (#289): each model the scanner uses, its size, licence and whether the
 * engine accepts it. Opening the list first brings the models folder up to date with the app
 * ([Engine.prepareModels]), so it shows what the next scan will find. While the diagnostics log is
 * recorded, Copy the models again is the testing option: every file is copied out of the app anew.
 */
@Composable
internal fun ScanModelsSection(engine: Engine?, testing: Boolean) {
    val c = pdfColors
    val app = LocalContext.current.applicationContext
    var models by remember { mutableStateOf<List<ScanModelInfo>?>(null) }
    var copied by remember { mutableStateOf<Int?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var force by remember { mutableStateOf(false) }
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
        val line = stringResource(R.string.scan_model_line, formatSize(m.bytes.toLong()), m.licence, stringResource(scanModelStateText(m.state)))
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(name, color = c.text, fontSize = 16.sp)
            Text(line, color = if (m.state == ScanModelState.READY) c.textSecondary else c.accent, fontSize = 13.sp)
        }
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
