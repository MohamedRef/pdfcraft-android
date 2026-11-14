package io.github.artcraftandroid.pdfcraft.ui.scan

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.ToolScaffold
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import io.github.artcraftandroid.pdfcraft.ui.viewer.formatSize
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A kept scan's name in the list: the name it was saved with, or "Scan" and when it was saved. */
@Composable
internal fun scanRecentName(entry: ScanRecentEntry): String =
    entry.name.ifBlank { stringResource(R.string.scan_recent_untitled, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.savedAt))) }

/**
 * Recent scans (#461): the last scans saved, newest first, each with its first page, its pages,
 * the space its photos take and when it was saved. A tap opens it on the document screen; the
 * bin deletes its photos (the PDF saved from it stays).
 */
@Composable
internal fun ScanRecentScreen(vm: AppViewModel) {
    val c = pdfColors
    val recent = vm.scanRecent
    LaunchedEffect(Unit) { recent.refresh() }
    var deleting by remember { mutableStateOf<ScanRecentEntry?>(null) }
    ToolScaffold(stringResource(R.string.scan_recent_title), { vm.tools.toolScreen = null }, null, false, {}) {
        if (!recent.on()) {
            Text(stringResource(R.string.scan_recent_off), color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (recent.list.isEmpty()) {
            Text(stringResource(R.string.scan_recent_empty), color = c.textSecondary, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(recent.list, key = { it.id }) { entry ->
                Row(
                    Modifier.fillMaxWidth().clickable { recent.open(entry.id) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScanRecentThumb(entry, Modifier.size(56.dp, 72.dp).clip(RoundedCornerShape(4.dp)).background(c.surfaceRaised))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(scanRecentName(entry), color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val when_ = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.savedAt))
                        Text(
                            listOf(pluralStringResource(R.plurals.scan_recent_pages, entry.pages, entry.pages), formatSize(entry.bytes), when_).joinToString("  •  "),
                            color = c.textSecondary, fontSize = 13.sp, maxLines = 2,
                        )
                    }
                    BarIcon(LucideIcons.Trash2, stringResource(R.string.common_delete), { deleting = entry })
                }
            }
        }
    }
    deleting?.let { entry ->
        PdfDialog(
            onDismiss = { deleting = null },
            title = stringResource(R.string.scan_recent_delete_title),
            confirm = stringResource(R.string.common_delete),
            onConfirm = { deleting = null; recent.delete(entry.id) },
            destructive = true,
        ) {
            Text(stringResource(R.string.scan_recent_delete_body), color = c.text, fontSize = 15.sp)
        }
    }
}

/** How many of the last scans Home shows (#516). */
internal const val SCAN_RECENT_HOME = 8

/**
 * The last scans on Home (#516), with every tool in reach: a row of first pages with their names,
 * one tap to the document screen, and See all for the Recent scans list. Nothing when no scan is
 * kept or keeping scans is off.
 */
@Composable
internal fun ScanRecentHomeRow(vm: AppViewModel) {
    val c = pdfColors
    val recent = vm.scanRecent
    if (!vm.scanLab.options.toolsInReach || !recent.on()) return
    LaunchedEffect(Unit) { recent.refresh() }
    val list = recent.list.take(SCAN_RECENT_HOME)
    if (list.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.scan_recent_title), color = c.text, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = { recent.show() }) { Text(stringResource(R.string.scan_recent_see_all), color = c.accent, fontSize = 14.sp) }
        }
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            items(list, key = { it.id }) { entry ->
                val name = scanRecentName(entry)
                Column(
                    Modifier.width(88.dp).clip(RoundedCornerShape(6.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button) {
                        vm.scanToolOpened(ScanReachTool.RecentScan, "home")
                        recent.open(entry.id)
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ScanRecentThumb(entry, Modifier.size(88.dp, 112.dp).clip(RoundedCornerShape(6.dp)).background(c.surfaceRaised))
                    Text(name, color = c.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** The first page of a kept scan, cut to its borders as the document screen shows it. */
@Composable
private fun ScanRecentThumb(entry: ScanRecentEntry, modifier: Modifier) {
    // the first page as made when one was kept (#494): drawn whole, as it is in the PDF
    val shown by produceState<Pair<ImageBitmap, Boolean>?>(null, entry.firstPhoto, entry.cover) {
        value = withContext(Dispatchers.IO) {
            entry.cover?.let { f -> runCatching { BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }.getOrNull() }?.let { it to true }
                ?: entry.firstPhoto?.let { f -> runCatching { scanRecentThumb(f) }.getOrNull() }?.let { it to false }
        }
    }
    Box(modifier) {
        val (t, whole) = shown ?: return@Box
        val region = remember(t, entry.firstCorners, whole) { if (whole) intArrayOf(0, 0, t.width, t.height) else scanThumbRegion(entry.firstCorners ?: floatArrayOf(), t.width, t.height) }
        Canvas(Modifier.fillMaxSize().padding(4.dp)) {
            val (sw, sh) = region[2].toFloat() to region[3].toFloat()
            val k = min(size.width / sw, size.height / sh)
            val dw = (sw * k).roundToInt().coerceAtLeast(1)
            val dh = (sh * k).roundToInt().coerceAtLeast(1)
            drawImage(
                t, srcOffset = IntOffset(region[0], region[1]), srcSize = IntSize(region[2], region[3]),
                dstOffset = IntOffset(((size.width - dw) / 2f).roundToInt(), ((size.height - dh) / 2f).roundToInt()), dstSize = IntSize(dw, dh),
            )
        }
    }
}

/** A small copy of a photo, turned as the camera wrote it (EXIF), for the list. */
private fun scanRecentThumb(f: File): ImageBitmap? {
    val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 }) ?: return null
    val degrees = when (androidx.exifinterface.media.ExifInterface(f.absolutePath).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
        6 -> 90f
        3 -> 180f
        8 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return bmp.asImageBitmap()
    val m = android.graphics.Matrix().apply { postRotate(degrees) }
    return android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).asImageBitmap()
}
