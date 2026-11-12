package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** TalkBack names of the colour-blind-safe palette, in [ColorBlindSafeColors] order. */
private val ColorBlindSafeNames = listOf(
    R.string.prefs_color_black, R.string.prefs_color_orange, R.string.ink_color_sky_blue, R.string.ink_color_bluish_green,
    R.string.prefs_color_yellow, R.string.prefs_color_blue, R.string.ink_color_vermillion, R.string.ink_color_reddish_purple,
)

/**
 * The colour part of the pen style sheet (#149): a choice of palette (the standard 18 swatches,
 * a colour-blind-safe set, or the user's own named palettes), the shown palette's swatches, and
 * palette actions (new, add the current colour, export, import, delete).
 */
@Composable
internal fun InkPaletteSection(vm: AppViewModel, current: Int, onPick: (Int) -> Unit) {
    val prefs = vm.settings.toolPrefs
    val c = pdfColors
    var naming by remember { mutableStateOf(false) }
    val shown = prefs.inkPaletteShown.let { key ->
        if (key in RESERVED_PALETTE_KEYS || prefs.inkPalettes.any { it.name == key }) key else PALETTE_STANDARD
    }
    val keys = RESERVED_PALETTE_KEYS + prefs.inkPalettes.map { it.name }
    OptionChips(keys, shown, { prefs.showInkPalette(it) }) { key ->
        when (key) {
            PALETTE_STANDARD -> stringResource(R.string.ink_palette_standard)
            PALETTE_COLOR_BLIND -> stringResource(R.string.ink_palette_color_blind)
            else -> key
        }
    }
    when (shown) {
        PALETTE_STANDARD -> ColorGrid(Color(current)) { onPick(it.toArgbInt()) }
        PALETTE_COLOR_BLIND -> SwatchRow(
            ColorBlindSafeColors.zip(ColorBlindSafeNames).map { (argb, name) -> argb to stringResource(name) },
            current, onPick, onRemove = null,
        )
        else -> {
            val palette = prefs.inkPalettes.first { it.name == shown }
            val fallback = palette.colors.associate { it.argb to stringResource(R.string.ink_color_hex_name, "#%06X".format(it.argb and 0xFFFFFF)) }
            SwatchRow(
                palette.colors.map { it.argb to (it.name ?: fallback.getValue(it.argb)) },
                current, onPick,
                onRemove = { prefs.removeColorFromPalette(palette.name, it) },
            )
            if (palette.colors.isNotEmpty()) Text(stringResource(R.string.ink_palette_remove_hint), color = c.label, fontSize = 12.sp)
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (shown !in RESERVED_PALETTE_KEYS) {
            TextButton(onClick = {
                if (!prefs.addColorToPalette(shown, current)) vm.tell(text(R.string.ink_palette_color_not_added))
            }) { Text(stringResource(R.string.ink_palette_add_color), color = c.accent) }
            TextButton(onClick = { vm.exportInkPalette(prefs.inkPalettes.first { it.name == shown }) }) {
                Text(stringResource(R.string.ink_palette_export), color = c.accent)
            }
            TextButton(onClick = { prefs.deleteInkPalette(shown) }) { Text(stringResource(R.string.ink_palette_delete), color = c.accent) }
        }
        TextButton(onClick = { naming = true }) { Text(stringResource(R.string.ink_palette_new), color = c.accent) }
        TextButton(onClick = { vm.importInkPalette() }) { Text(stringResource(R.string.ink_palette_import), color = c.accent) }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.ink_palette_new)) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(InkPalette.MAX_NAME) }, singleLine = true,
                    label = { Text(stringResource(R.string.ink_palette_name)) },
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    naming = false
                    // A new palette starts with the pen's current colour.
                    if (!prefs.addInkPalette(InkPalette(name, listOf(InkPalette.PaletteColor(current))))) {
                        vm.tell(text(R.string.ink_palette_full))
                    }
                }) { Text(stringResource(R.string.ink_palette_create)) }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** Round swatches that wrap onto rows of eight; a long-press removes one when [onRemove] is given. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwatchRow(colors: List<Pair<Int, String>>, current: Int, onPick: (Int) -> Unit, onRemove: ((Int) -> Unit)?) {
    val c = pdfColors
    val removeLabel = stringResource(R.string.ink_palette_remove_color)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.chunked(8).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (argb, name) ->
                    val isSel = argb == current
                    Box(
                        Modifier.size(36.dp)
                            .border(if (isSel) 2.dp else 1.dp, if (isSel) c.accent else c.divider, RoundedCornerShape(50))
                            .padding(3.dp).background(Color(argb), RoundedCornerShape(50))
                            .combinedClickable(
                                onClick = { onPick(argb) },
                                onLongClick = onRemove?.let { { it(argb) } },
                                onLongClickLabel = if (onRemove != null) removeLabel else null,
                            )
                            .semantics {
                                contentDescription = name
                                selected = isSel
                                role = Role.RadioButton
                            },
                    )
                }
            }
        }
    }
}

/**
 * A custom colour (#149): a saturation/brightness square and a hue slider. The pen takes the new
 * colour when a tap or drag ends, so dragging does not fill the recent colours with steps.
 */
@Composable
internal fun HsvColorPicker(current: Int, onColor: (Int) -> Unit) {
    val c = pdfColors
    val start = remember(current) { argbToHsv(current) }
    var hue by remember(current) { mutableFloatStateOf(start[0]) }
    var sat by remember(current) { mutableFloatStateOf(start[1]) }
    var value by remember(current) { mutableFloatStateOf(start[2]) }
    val preview = hsvToArgb(hue, sat, value)
    val squareLabel = stringResource(R.string.ink_hsv_square, (sat * 100).toInt(), (value * 100).toInt())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).background(Color(preview), RoundedCornerShape(50)).border(1.dp, c.divider, RoundedCornerShape(50)))
            Text("#%06X".format(preview and 0xFFFFFF), color = c.text, fontSize = 14.sp)
        }
        fun at(o: Offset, w: Float, h: Float) {
            sat = (o.x / w).coerceIn(0f, 1f)
            value = 1f - (o.y / h).coerceIn(0f, 1f)
        }
        Canvas(
            Modifier.fillMaxWidth().height(150.dp)
                .semantics { contentDescription = squareLabel }
                .pointerInput(Unit) {
                    detectTapGestures { o -> at(o, size.width.toFloat(), size.height.toFloat()); onColor(hsvToArgb(hue, sat, value)) }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o -> at(o, size.width.toFloat(), size.height.toFloat()) },
                        onDragEnd = { onColor(hsvToArgb(hue, sat, value)) },
                    ) { change, _ -> change.consume(); at(change.position, size.width.toFloat(), size.height.toFloat()) }
                },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color(hsvToArgb(hue, 1f, 1f)))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val p = Offset(sat * size.width, (1f - value) * size.height)
            drawCircle(Color.White, radius = 9.dp.toPx(), center = p, style = Stroke(2.dp.toPx()))
            drawCircle(Color.Black, radius = 11.dp.toPx(), center = p, style = Stroke(1.dp.toPx()))
        }
        Text(stringResource(R.string.ink_hsv_hue), color = c.label, fontSize = 12.sp)
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().height(12.dp).padding(horizontal = 10.dp)) {
                drawRect(Brush.horizontalGradient((0..6).map { Color(hsvToArgb(it * 60f, 1f, 1f)) }))
            }
            Slider(
                value = hue, valueRange = 0f..360f,
                onValueChange = { hue = it }, onValueChangeFinished = { onColor(hsvToArgb(hue, sat, value)) },
                colors = SliderDefaults.colors(thumbColor = Color(hsvToArgb(hue, 1f, 1f)), activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
            )
        }
    }
}

private fun AppViewModel.exportInkPalette(palette: InkPalette) {
    val ctx = getApplication<Application>()
    requests.create(paletteFileName(palette.name), "application/json") { uri ->
        if (uri == null) return@create
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(palette.toJson().toString(2).toByteArray()) }
                }
                PenDiag.palette("export", "saved", palette.colors.size)
                tell(text(R.string.ink_palette_exported, palette.name))
            } catch (e: Exception) {
                PenDiag.palette("export", "failed")
                tell(e.userMessage)
            }
        }
    }
}

private fun AppViewModel.importInkPalette() {
    val ctx = getApplication<Application>()
    requests.open(arrayOf("application/json", "text/plain", "application/octet-stream"), many = false) { uris ->
        val uri = uris.firstOrNull() ?: return@open
        viewModelScope.launch {
            val read = runCatching {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)!!.use { input ->
                        val buf = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (buf.size() <= InkPalette.MAX_FILE_BYTES) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            buf.write(chunk, 0, n)
                        }
                        if (buf.size() > InkPalette.MAX_FILE_BYTES) null else buf.toString(Charsets.UTF_8.name())
                    }
                }
            }
            val textRead = read.getOrNull()
            if (read.isFailure) {
                PenDiag.palette("import", "read_failed")
                tell(read.exceptionOrNull()!!.userMessage)
                return@launch
            }
            val parsed = textRead?.let { parseInkPalette(it) }
            val palette = parsed?.getOrNull()
            when {
                palette == null -> {
                    val why = (parsed?.exceptionOrNull() as? PaletteImport)?.reason?.diagWord() ?: "too_large"
                    PenDiag.palette("import", why)
                    tell(text(R.string.ink_palette_import_invalid))
                }
                !settings.toolPrefs.addInkPalette(palette) -> {
                    PenDiag.palette("import", "full", palette.colors.size)
                    tell(text(R.string.ink_palette_full))
                }
                else -> {
                    PenDiag.palette("import", "imported", palette.colors.size)
                    tell(text(R.string.ink_palette_imported, settings.toolPrefs.inkPaletteShown))
                }
            }
        }
    }
}
