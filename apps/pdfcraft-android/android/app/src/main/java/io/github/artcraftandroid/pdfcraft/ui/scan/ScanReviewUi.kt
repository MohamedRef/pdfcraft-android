package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/*
 * What the review of pages whose borders need a check (#783) shows: the mark on a page of the
 * document screen, the question before Save PDF or Share, and the question when Back leaves a page
 * of a borders pass with its corners moved.
 */

/**
 * The mark on a page of the document screen whose borders need a check: "Check borders" on the
 * accent colour, in a corner of the tile. It is not a button (a tap on the tile opens the page as
 * before); the tile's own description gets its words, so TalkBack says "Page 2, Check borders".
 */
@Composable
internal fun ScanReviewBadge(modifier: Modifier = Modifier) {
    val c = pdfColors
    val words = stringResource(R.string.scan_review_badge)
    Row(
        modifier.padding(6.dp).clip(RoundedCornerShape(10.dp)).background(c.accent).padding(horizontal = 6.dp, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = words },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LucideIcons.Crop, null, tint = c.onAccent, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(words, color = c.onAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * "N pages may have the wrong borders" before Save PDF or Share ([share]) stores them as they are.
 * Check, the default, opens the review on them; Save anyway goes on; Cancel, Back or a tap outside
 * saves nothing.
 */
@Composable
internal fun ScanReviewSaveDialog(pages: Int, share: Boolean, onCheck: () -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(pluralStringResource(R.plurals.scan_unsure_offer, pages, pages)) },
        text = { Text(stringResource(R.string.scan_review_save_body)) },
        confirmButton = { TextButton(onClick = onCheck) { Text(stringResource(R.string.scan_batch_check)) } },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
            TextButton(onClick = onSave) { Text(stringResource(if (share) R.string.scan_review_share_anyway else R.string.scan_review_save_anyway)) }
        },
    )
}

/**
 * Back on a page of a borders pass whose corners were moved: Keep stores them and leaves, Discard
 * puts the page back as it was and leaves; Back again or a tap outside stays on the page.
 */
@Composable
internal fun ScanReviewLeaveDialog(onKeep: () -> Unit, onDiscard: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text(stringResource(R.string.scan_review_leave_title)) },
        text = { Text(stringResource(R.string.scan_review_leave_body)) },
        confirmButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.scan_borders_keep)) } },
        dismissButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.scan_borders_discard)) } },
    )
}
