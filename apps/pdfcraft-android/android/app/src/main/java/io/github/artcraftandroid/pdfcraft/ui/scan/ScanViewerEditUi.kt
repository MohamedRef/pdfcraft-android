package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument

/** "Keep the comments on changed pages?" before a scan opened from its PDF goes back into it (#513). */
@Composable
internal fun ScanEditCommentsDialog(comments: Int, onKeep: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_edit_comments_title)) },
        text = { Text(pluralStringResource(R.plurals.scan_edit_comments_body, comments, comments)) },
        confirmButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.scan_edit_comments_keep)) } },
        dismissButton = { TextButton(onClick = onRemove) { Text(stringResource(R.string.scan_edit_comments_remove)) } },
    )
}

/**
 * A page of a saved scan, tapped in the viewer (#513): the page alone on a dark screen with its
 * number, and Edit, which opens it in the scan's own screens. Back or the close button goes back
 * to the PDF.
 */
@Composable
internal fun ScanViewerPage(vm: AppViewModel, doc: OpenDocument, page: Int) {
    val close = { vm.scanEdit.shown = null }
    BackHandler(onBack = close)
    val image = remember(doc, page, doc.info.generation) { doc.images.latestBase(page)?.bitmap }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF111111))
            // taps on the dark screen do not reach the PDF under it
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BarIcon(LucideIcons.X, stringResource(R.string.common_back), close, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.scan_edit_page_of, page + 1, doc.pageCount), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
            val label = stringResource(R.string.scan_edit_page_edit_description)
            Button(
                onClick = { vm.scanEdit.edit(doc, page) },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 24.dp),
                modifier = Modifier.height(48.dp).semantics { contentDescription = label },
            ) {
                Icon(LucideIcons.Pencil, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scan_edit_page_edit), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    }
}
