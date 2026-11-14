package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.PdfSwitch
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/** What the crop screen needs when it is the Adjust borders step (#458). */
internal class ScanBordersStep(
    /** The page counted from 1, and how many the scan has. */
    val page: Int,
    val pages: Int,
    val opener: ScanBordersOpener,
    val finding: ScanBordersFinding,
    /** The quarter turns clockwise the page is shown and saved with. */
    val turns: Int,
    /** Retake; null in a pass over several pages (#515), which a retake would leave. */
    val onRetake: (() -> Unit)?,
    /** Rotate left (-1) or right (+1). */
    val onRotate: (Int) -> Unit,
    /** Back after a photo, confirmed: the photo is dropped and the camera comes back. */
    val onDiscard: () -> Unit,
    /** A pass over several pages (#515): the place shown and the way to the others; null for one page. */
    val batch: ScanBatchNav? = null,
)

/** A borders pass's place (#515): page [at] (from 1) of [count], and leaving the page shown towards -1, +1 or 0 (the tick) with its corners. */
internal class ScanBatchNav(val at: Int, val count: Int, val onLeave: (Int, FloatArray, ScanCropStats) -> Unit, val onBack: () -> Unit)

/** The pass's counter between Previous and Next (#515). */
@Composable
internal fun ScanBatchNavRow(nav: ScanBatchNav, onGo: (Int) -> Unit) {
    val c = pdfColors
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        ScanBarButton(LucideIcons.ChevronLeft, stringResource(R.string.tools_previous_page), nav.at > 1) { onGo(-1) }
        Text(
            stringResource(R.string.tools_page_of, nav.at, nav.count), color = c.text, fontSize = 15.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        ScanBarButton(LucideIcons.ChevronRight, stringResource(if (nav.at < nav.count) R.string.tools_next_page else R.string.common_done)) { onGo(if (nav.at < nav.count) 1 else 0) }
    }
}

/** "Page N of M", and what the search for the page's outline came to. */
@Composable
internal fun ScanBordersHeader(step: ScanBordersStep, index: Int) {
    val c = pdfColors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (step.pages > 1) Text(stringResource(R.string.tools_page_of, step.page, step.pages), color = c.textSecondary, fontSize = 13.sp)
        // a polite live region: TalkBack reads it when the search answers
        Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
            when (step.finding) {
                ScanBordersFinding.Searching -> {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.accent)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.scan_borders_searching), color = c.textSecondary, fontSize = 13.sp)
                }
                ScanBordersFinding.NotFound -> Text(stringResource(R.string.scan_borders_not_found), color = c.text, fontSize = 14.sp)
                ScanBordersFinding.Found -> Unit
            }
        }
    }
}

/**
 * The photo and its corners turned by [turns] quarter turns. Touches are turned with it, so the
 * handles still follow the finger; a quarter turn lays the content out with width and height
 * swapped, then turns it into the space.
 */
@Composable
internal fun ScanTurnedBox(turns: Int, modifier: Modifier, content: @Composable (Modifier) -> Unit) {
    val q = scanQuarter(turns)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val inner = if (q % 2 == 1) Modifier.requiredSize(maxHeight, maxWidth) else Modifier.fillMaxSize()
        content(inner.graphicsLayer { rotationZ = 90f * q })
    }
}

/**
 * The bottom bar (#458): Retake, Rotate left, Rotate right, Whole photo ⇄ Auto, and Snap as a
 * small switch above it. Next or Done is the top bar's filled action.
 */
@Composable
internal fun ScanBordersBar(
    step: ScanBordersStep,
    toggle: ScanBordersToggle,
    detecting: Boolean,
    snapOn: Boolean,
    onSnap: (Boolean) -> Unit,
    onWhole: () -> Unit,
    onAuto: () -> Unit,
) {
    val c = pdfColors
    val snapLabel = stringResource(R.string.tools_crop_snap)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Text(snapLabel, color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
        PdfSwitch(snapOn, onSnap, Modifier.semantics { contentDescription = snapLabel })
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
        // equal shares of the width, at most 96 dp each, rather than fixed widths that must add up (#538)
        val share = Modifier.weight(1f, fill = false).widthIn(max = 96.dp).fillMaxWidth()
        step.onRetake?.let { ScanBarButton(LucideIcons.Camera, stringResource(R.string.tools_retake), modifier = share, onClick = it) }
        ScanBarButton(LucideIcons.RotateCcw, stringResource(R.string.scan_borders_rotate_left), modifier = share) { step.onRotate(-1) }
        ScanBarButton(LucideIcons.RotateCw, stringResource(R.string.scan_borders_rotate_right), modifier = share) { step.onRotate(1) }
        when (toggle) {
            ScanBordersToggle.WholePhoto -> ScanBarButton(LucideIcons.Maximize, stringResource(R.string.scan_borders_whole), modifier = share, onClick = onWhole)
            ScanBordersToggle.Auto -> ScanBarButton(LucideIcons.ScanText, stringResource(R.string.scan_borders_auto), enabled = !detecting, modifier = share, onClick = onAuto)
        }
    }
}

/** One button of a bottom bar: an icon with its word under it; the bars give each an equal share of their width (#538). */
@Composable
internal fun ScanBarButton(icon: ImageVector, label: String, enabled: Boolean = true, modifier: Modifier = Modifier.width(72.dp), onClick: () -> Unit) {
    val c = pdfColors
    val tint = if (enabled) c.text else c.textSecondary
    Column(
        modifier.sizeIn(minHeight = 56.dp).clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Text(label, color = tint, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 14.sp)
    }
}

/** Back on Adjust borders right after a photo: the photo is dropped only when asked (#456). Keep is the default. */
@Composable
internal fun ScanBordersDiscardDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.scan_borders_discard_title)) },
        text = { Text(stringResource(R.string.scan_borders_discard_body)) },
        confirmButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.scan_borders_keep)) } },
        dismissButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.scan_borders_discard)) } },
    )
}
