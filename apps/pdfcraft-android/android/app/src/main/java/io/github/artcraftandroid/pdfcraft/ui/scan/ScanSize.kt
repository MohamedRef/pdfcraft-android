package io.github.artcraftandroid.pdfcraft.ui.scan

import android.content.SharedPreferences
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.ScanEncoding
import io.github.artcraftandroid.pdfcraft.core.ScanSizeOption
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * The size choice at Save PDF and Share (#505): Best quality, Balanced, Small, Smallest, with this
 * scan's estimated size beside each, remembered for next time. Each page is stored as what is on
 * it asks at that size (the engine's `size::store`); the testing option "Store each page by what
 * is on it" (`autoEncode`) off stores every page as before, whatever the choice.
 */

/** The size choice remembered from the last save (#505). Balanced until one is picked. */
internal object ScanSizeSetting {
    private const val KEY = "scanSizeChoice"
    const val DEFAULT = "balanced"

    fun get(prefs: SharedPreferences): String = prefs.getString(KEY, null)?.takeIf { it in ScanLab.SIZES } ?: DEFAULT

    fun set(prefs: SharedPreferences, size: String) {
        if (size in ScanLab.SIZES) prefs.edit().putString(KEY, size).apply()
    }
}

/** The choice's name. */
internal fun scanSizeName(id: String): Int = when (id) {
    "best" -> R.string.scan_size_best
    "small" -> R.string.scan_size_small
    "smallest" -> R.string.scan_size_smallest
    else -> R.string.scan_size_balanced
}

/** What the choice is meant for. */
internal fun scanSizeUse(id: String): Int = when (id) {
    "best" -> R.string.scan_size_best_use
    "small" -> R.string.scan_size_small_use
    "smallest" -> R.string.scan_size_smallest_use
    else -> R.string.scan_size_balanced_use
}

/** The size a save uses: the testing option's when it sets one, else the one the person chose. */
internal fun scanSizeFor(lab: ScanLab, chosen: String): String = lab.size ?: chosen.takeIf { it in ScanLab.SIZES } ?: ScanSizeSetting.DEFAULT

/** Whether the sheet offers "See this page as it will be saved": at the choices that change how it looks most. */
internal fun scanSizeShowsPage(id: String): Boolean = id == "small" || id == "smallest"

/** The scan's estimated size from its first page's bytes at a choice: the same for every page. */
internal fun scanSizeEstimate(firstPage: Long, pages: Int): Long = firstPage * pages.coerceAtLeast(1)

/** The log's fields for how a page was stored (#505); a camera file kept as it was has none of its own. */
internal fun scanEncodingDiag(e: ScanEncoding?): Array<Pair<String, Any?>> = arrayOf(
    "size_choice" to (e?.sizeChoice ?: "none"),
    "auto_encode" to (e?.auto ?: false),
    "encoding" to (e?.encoding ?: "camera_file"),
    "px_out" to (e?.let { it.width.toLong() * it.height.toLong() } ?: 0L),
    "dpi_out" to (e?.dpi?.toLong() ?: 0L),
    "quality" to (e?.quality?.toInt() ?: 0),
    "half_chroma" to (e?.halfChroma ?: false),
    "bytes" to (e?.bytes?.toLong() ?: -1L),
    "encode_why" to (e?.why ?: "camera_file"),
)

/** The same for a kept scan's page file (#505); null for a camera file kept as it was. */
internal fun scanEncodingJson(e: ScanEncoding?): JSONObject? = e?.let {
    JSONObject()
        .put("size_choice", it.sizeChoice).put("auto_encode", it.auto).put("encoding", it.encoding)
        .put("px_out", it.width.toLong() * it.height.toLong()).put("width", it.width.toLong()).put("height", it.height.toLong())
        .put("dpi_out", it.dpi.toLong()).put("quality", it.quality.toInt()).put("half_chroma", it.halfChroma)
        .put("bytes", it.bytes.toLong()).put("encode_why", it.why)
}

/** The log's fields for a saved or shared scan's size (#505): the choice, the PDF's bytes and per page. */
internal fun scanSavedSizeDiag(size: String, bytes: Long, pages: Int, pagesBytes: Long): Array<Pair<String, Any?>> =
    arrayOf("size_choice" to size, "bytes_per_page" to bytes / pages.coerceAtLeast(1), "pages_bytes" to pagesBytes)

/** "1.2 MB" for [bytes]. */
private fun sizeText(context: android.content.Context, bytes: Long): String = android.text.format.Formatter.formatShortFileSize(context, bytes)

/**
 * The sheet before Save PDF ([share] false) or Share: the four choices with this scan's estimated
 * size, the last one chosen picked. The estimate makes the first page once at every choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScanSizeSheet(vm: AppViewModel, share: Boolean, onDismiss: () -> Unit) {
    val c = pdfColors
    val context = LocalContext.current
    val prefs = vm.settings.appPrefs
    var chosen by remember { mutableStateOf(ScanSizeSetting.get(prefs)) }
    var viewing by remember { mutableStateOf<ScanSizeOption?>(null) }
    val pages = vm.scanPages.size
    val options by produceState<Map<String, ScanSizeOption>?>(null) { value = vm.scanSizeOptions() }
    LaunchedEffect(Unit) { Diag.event(DiagArea.Scan, "size_sheet", "share" to share, "remembered" to chosen, "pages" to pages) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(stringResource(R.string.scan_size_title), color = c.text, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            for (id in ScanLab.SIZES) {
                val bytes = options?.get(id)?.encoding?.bytes?.toLong()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .selectable(selected = chosen == id, role = Role.RadioButton) { chosen = id }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = chosen == id, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = c.accent))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(scanSizeName(id)), color = c.text, fontSize = 16.sp)
                        Text(stringResource(scanSizeUse(id)), color = c.textSecondary, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            bytes != null -> stringResource(R.string.scan_size_about, sizeText(context, scanSizeEstimate(bytes, pages)))
                            options == null -> stringResource(R.string.scan_size_estimating)
                            else -> ""
                        },
                        color = c.textSecondary, fontSize = 13.sp,
                    )
                }
            }
            options?.takeIf { it.isNotEmpty() && pages > 1 }?.let {
                Text(stringResource(R.string.scan_size_estimate_note), color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
            }
            val seeable = options?.get(chosen)?.takeIf { scanSizeShowsPage(chosen) }
            if (seeable != null) {
                TextButton(onClick = { viewing = seeable }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.scan_size_see_page), color = c.accent)
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    ScanSizeSetting.set(prefs, chosen)
                    Diag.event(DiagArea.Scan, "size_pick", "share" to share, "size_choice" to chosen, "estimated" to (options?.get(chosen)?.encoding?.bytes?.toLong()?.let { scanSizeEstimate(it, pages) } ?: -1L))
                    onDismiss()
                    vm.saveScan(share = share, size = chosen)
                },
                colors = ButtonDefaults.buttonColors(containerColor = c.accent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                Text(stringResource(if (share) R.string.common_share else R.string.tools_save_pdf))
            }
        }
    }
    viewing?.let { option -> ScanSizePageView(option) { viewing = null } }
}

/** The first page as it will be stored at a choice, full screen; pinch to look closer. */
@Composable
private fun ScanSizePageView(option: ScanSizeOption, onClose: () -> Unit) {
    val c = pdfColors
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, option.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(option.path, bounds)
                // no more than 2048 pixels on its long side on screen: enough to see the print, little memory
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
                android.graphics.BitmapFactory.decodeFile(option.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            bitmap?.let { b ->
                Image(
                    b.asImageBitmap(), stringResource(R.string.scan_size_see_page),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTransformGestures { _, pan, z, _ ->
                            scale = (scale * z).coerceIn(1f, 8f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                )
            }
            Row(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.6f)).navigationBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.scan_size_page_caption, stringResource(scanSizeName(option.size)), sizeText(context, option.encoding.bytes.toLong())),
                    color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.viewer_close), color = c.accent, fontSize = 15.sp,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onClose).padding(8.dp),
                )
            }
        }
    }
}
