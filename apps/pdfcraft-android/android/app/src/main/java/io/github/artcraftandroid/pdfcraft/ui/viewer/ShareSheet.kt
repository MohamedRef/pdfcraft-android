package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.SheetRow
import io.github.artcraftandroid.pdfcraft.ui.SheetTitle

/** What Share sends (#205). */
enum class ShareChoice(val id: String, @StringRes val label: Int) {
    /** The PDF with its comments and fields, as it is. */
    Pdf("pdf", R.string.share_choice_pdf),

    /** Comments and form fields baked into the pages, so the receiver can't remove or change them. */
    Flattened("flattened", R.string.share_choice_flattened),

    /** A copy without comments and markups; the open document keeps them (#155). */
    Clean("clean", R.string.sheet_share_clean),

    /** The page in view, as a PDF of its own. */
    Page("page", R.string.share_choice_page),

    /** Pictures of pages, picked in the page grid. */
    Images("images", R.string.share_choice_images);

    val icon: ImageVector
        get() = when (this) {
            Pdf -> LucideIcons.FileText
            Flattened -> LucideIcons.Layers
            Clean -> LucideIcons.BrushCleaning
            Page -> LucideIcons.File
            Images -> LucideIcons.Images
        }

    companion object {
        /** The choice saved as [id]; Share PDF when there is none (or it's unknown). */
        fun of(id: String?): ShareChoice = entries.firstOrNull { it.id == id } ?: Pdf
    }
}

/** Share: what to send, the last choice ticked. Share PDF stays first, one tap away (#205). */
@Composable
fun ShareSheet(vm: AppViewModel, doc: OpenDocument) {
    val ctl = doc.controller
    val last = vm.lastShareChoice
    PdfSheet(onDismiss = { ctl.sheet = ViewerSheet.None }) {
        SheetTitle(stringResource(R.string.common_share))
        for (choice in ShareChoice.entries) {
            SheetRow(choice.icon, stringResource(choice.label), {
                ctl.sheet = ViewerSheet.None
                vm.share(doc, choice)
            }, checked = choice == last)
        }
        Spacer(Modifier.height(8.dp))
    }
}
