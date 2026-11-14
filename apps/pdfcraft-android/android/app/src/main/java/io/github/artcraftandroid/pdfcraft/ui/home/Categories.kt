package io.github.artcraftandroid.pdfcraft.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.DocCategory
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.PdfField
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/** A category's name: the person's own as typed, a built-in one in the app's language (#560). */
@Composable
fun categoryName(cat: DocCategory): String = cat.name ?: stringResource(
    when (cat.id) {
        DocCategory.STUDY -> R.string.category_study
        DocCategory.WORK -> R.string.category_work
        else -> R.string.category_personal
    },
)

/**
 * A chip: its name, and whether it is the chosen one (TalkBack says both). Never squeezed: it
 * keeps its width, and the row it is in scrolls (#538's rule).
 */
@Composable
fun CategoryChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = pdfColors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.heightIn(min = 36.dp).clip(shape)
            .background(if (selected) c.accent.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (selected) c.accent else c.textSecondary, shape)
            .clickable(role = Role.Tab, onClick = onClick).semantics { this.selected = selected }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(LucideIcons.Check, null, tint = c.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = if (selected) c.text else c.label, fontSize = 14.sp, maxLines = 1, softWrap = false)
    }
}

/**
 * The file's category as a row of chips (#560): one tap on a chip gives it that category, a tap
 * on the chosen one takes it off. The pencil edits the list. [from] names the place, for the log.
 */
@Composable
fun CategoryChooser(vm: AppViewModel, uri: String, name: String, from: String, modifier: Modifier = Modifier) {
    val c = pdfColors
    // the same file reached by another route has its category too: its path is looked up first
    var current by remember(uri, vm.study.libraryData) { mutableStateOf(vm.study.categoryOf(uri)) }
    androidx.compose.runtime.LaunchedEffect(uri, vm.study.libraryData) { current = vm.study.findCategory(uri) }
    var editing by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.category_label), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            for (cat in vm.study.libraryData.categories) {
                val on = cat.id == current
                CategoryChip(categoryName(cat), on) {
                    current = if (on) null else cat.id
                    vm.study.setCategory(uri, name, current, from)
                }
            }
            BarIcon(LucideIcons.Pencil, stringResource(R.string.category_edit), { editing = true }, size = 40.dp, iconSize = 20.dp)
        }
    }
    if (editing) ManageCategories(vm) { editing = false }
}

/** A sheet with only the file's category, for holding a row of Home's card. */
@Composable
fun CategorySheet(vm: AppViewModel, uri: String, name: String, from: String, onDismiss: () -> Unit) {
    val c = pdfColors
    PdfSheet(onDismiss = onDismiss, dim = true) {
        Text(name.removeSuffix(".pdf"), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        CategoryChooser(vm, uri, name, from)
        Spacer(Modifier.size(16.dp))
    }
}

/** Add, rename, delete and reorder the categories. */
@Composable
fun ManageCategories(vm: AppViewModel, onDismiss: () -> Unit) {
    val c = pdfColors
    val cats = vm.study.libraryData.categories
    var naming by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var deleting by remember { mutableStateOf<DocCategory?>(null) }
    PdfDialog(onDismiss = onDismiss, title = stringResource(R.string.category_edit), confirm = stringResource(R.string.common_done), onConfirm = onDismiss, dismiss = null) {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            for ((i, cat) in cats.withIndex()) {
                val label = categoryName(cat)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    BarIcon(LucideIcons.ChevronUp, stringResource(R.string.category_move_up), { vm.study.moveCategory(cat.id, -1) }, size = 40.dp, iconSize = 20.dp, enabled = i > 0)
                    BarIcon(LucideIcons.ChevronDown, stringResource(R.string.category_move_down), { vm.study.moveCategory(cat.id, 1) }, size = 40.dp, iconSize = 20.dp, enabled = i < cats.lastIndex)
                    BarIcon(LucideIcons.Pencil, stringResource(R.string.common_rename), { naming = cat.id to label }, size = 40.dp, iconSize = 20.dp)
                    BarIcon(LucideIcons.Trash2, stringResource(R.string.category_delete), { deleting = cat }, size = 40.dp, iconSize = 20.dp, tint = c.red)
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { naming = null to "" }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LucideIcons.Plus, null, tint = c.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.category_new), color = c.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    naming?.let { (id, initial) ->
        var text by remember(id) { mutableStateOf(initial) }
        PdfDialog(
            onDismiss = { naming = null },
            title = stringResource(if (id == null) R.string.category_new else R.string.common_rename),
            confirm = stringResource(R.string.common_save), confirmEnabled = text.isNotBlank(),
            onConfirm = { naming = null; if (id == null) vm.study.addCategory(text) else vm.study.renameCategory(id, text) },
        ) { PdfField(text, { text = it.take(40) }, stringResource(R.string.category_name_hint)) }
    }
    deleting?.let { cat ->
        PdfDialog(
            onDismiss = { deleting = null }, title = stringResource(R.string.category_delete),
            confirm = stringResource(R.string.common_delete), destructive = true,
            onConfirm = { deleting = null; vm.study.deleteCategory(cat.id) },
        ) { Text(stringResource(R.string.category_delete_body, categoryName(cat)), color = c.text, fontSize = 14.sp) }
    }
}
