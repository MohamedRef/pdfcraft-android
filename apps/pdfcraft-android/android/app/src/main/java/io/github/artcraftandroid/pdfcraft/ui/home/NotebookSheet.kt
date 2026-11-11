package io.github.artcraftandroid.pdfcraft.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.NotebookColor
import io.github.artcraftandroid.pdfcraft.engine.NotebookPage
import io.github.artcraftandroid.pdfcraft.engine.NotebookPaper
import io.github.artcraftandroid.pdfcraft.engine.NotebookSize
import io.github.artcraftandroid.pdfcraft.engine.NotebookTemplate
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.PdfField
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

@StringRes
fun NotebookSize.label(): Int = when (this) {
    NotebookSize.A4 -> R.string.notebook_size_a4
    NotebookSize.Letter -> R.string.notebook_size_letter
    NotebookSize.A5 -> R.string.notebook_size_a5
    NotebookSize.Tall -> R.string.notebook_size_tall
    NotebookSize.Landscape -> R.string.notebook_size_landscape
}

@StringRes
fun NotebookPaper.label(): Int = when (this) {
    NotebookPaper.Blank -> R.string.notebook_paper_blank
    NotebookPaper.NarrowRuled -> R.string.notebook_paper_narrow
    NotebookPaper.WideRuled -> R.string.notebook_paper_wide
    NotebookPaper.Grid -> R.string.notebook_paper_grid
    NotebookPaper.Dots -> R.string.notebook_paper_dots
    NotebookPaper.Cornell -> R.string.notebook_paper_cornell
    NotebookPaper.Music -> R.string.notebook_paper_music
    NotebookPaper.Engineering -> R.string.notebook_paper_engineering
}

@StringRes
fun NotebookColor.label(): Int = when (this) {
    NotebookColor.White -> R.string.notebook_color_white
    NotebookColor.Cream -> R.string.notebook_color_cream
    NotebookColor.Dark -> R.string.notebook_color_dark
}

/**
 * Choose a notebook's size, paper and colour (or one of the pages saved as templates), then
 * Create: the defaults (A4, dots, white) make a notebook in one more tap.
 */
@Composable
fun NewNotebookSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshUserTemplates() }
    var picked by remember { mutableStateOf(NotebookTemplate()) }
    var user by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }
    PdfSheet(onDismiss = onDismiss, dim = true) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            NotebookTemplatePicker(picked, user, vm.userTemplates, onPick = { picked = it; user = null }, onUser = { user = it }, onDeleteUser = { deleting = it })
            Spacer(Modifier.height(12.dp))
            PdfField(title, { title = it.take(80) }, stringResource(R.string.notebook_name_hint), Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(16.dp))
            val create = stringResource(R.string.notebook_create)
            Box(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF1473E6))
                    .combinedClickable(role = Role.Button, onClick = { vm.createNotebook(user?.let(NotebookPage::User) ?: NotebookPage.Builtin(picked), title) }),
                contentAlignment = Alignment.Center,
            ) { Text(create, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
    deleting?.let { name ->
        PdfDialog(
            onDismiss = { deleting = null }, title = stringResource(R.string.notebook_delete_template_title, name.removeSuffix(".pdf")),
            confirm = stringResource(R.string.common_delete), destructive = true,
            onConfirm = { deleting = null; if (user == name) user = null; vm.deleteUserTemplate(name) },
        )
    }
}

/** The size, paper and colour rows with a preview, and the user's own templates. */
@Composable
fun NotebookTemplatePicker(
    picked: NotebookTemplate,
    user: String?,
    userTemplates: List<String>,
    onPick: (NotebookTemplate) -> Unit,
    onUser: (String) -> Unit,
    onDeleteUser: ((String) -> Unit)? = null,
    showSize: Boolean = true,
) {
    val c = pdfColors
    Column {
        Text(stringResource(R.string.notebook_title), color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) { NotebookPreview(picked, dimmed = user != null) }
        if (showSize) ChipRow(R.string.notebook_size, NotebookSize.entries, { it.label() }, picked.size.takeIf { user == null }) { onPick(picked.copy(size = it)) }
        ChipRow(R.string.notebook_paper, NotebookPaper.entries, { it.label() }, picked.paper.takeIf { user == null }) { onPick(picked.copy(paper = it)) }
        ChipRow(R.string.notebook_color, NotebookColor.entries, { it.label() }, picked.color.takeIf { user == null }) { onPick(picked.copy(color = it)) }
        if (userTemplates.isNotEmpty()) {
            Text(stringResource(R.string.notebook_my_templates), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (name in userTemplates) Chip(name.removeSuffix(".pdf"), name == user, onClick = { onUser(name) }, onLongClick = onDeleteUser?.let { { it(name) } })
            }
        }
    }
}

@Composable
private fun <T> ChipRow(@StringRes title: Int, values: List<T>, label: (T) -> Int, selected: T?, onPick: (T) -> Unit) {
    val c = pdfColors
    Text(stringResource(title), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (v in values) Chip(stringResource(label(v)), v == selected, onClick = { onPick(v) })
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val c = pdfColors
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.heightIn(min = 36.dp).clip(shape)
            .then(if (selected) Modifier.background(Color(0xFF1473E6)) else Modifier.border(1.dp, c.textSecondary, shape))
            .combinedClickable(role = Role.RadioButton, onClick = onClick, onLongClick = onLongClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) Color.White else c.label, fontSize = 14.sp, maxLines = 1) }
}

/** A small drawing of the page: its shape, colour and lines, roughly as the PDF draws them. */
@Composable
private fun NotebookPreview(t: NotebookTemplate, dimmed: Boolean) {
    val h = 120.dp
    val w = h * (t.size.width / t.size.height).toFloat()
    val description = stringResource(R.string.notebook_preview)
    fun col(v: Triple<Double, Double, Double>) = Color(v.first.toFloat(), v.second.toFloat(), v.third.toFloat())
    Canvas(Modifier.size(w, h).border(1.dp, pdfColors.divider).semantics { contentDescription = description }) {
        val paper = col(t.color.paper)
        val line = col(t.color.line)
        drawRect(paper)
        val k = size.height / t.size.height.toFloat()
        val mm = (72f / 25.4f) * k
        val stroke = 1f
        fun h(y: Float, x0: Float = 0f, x1: Float = size.width) = drawLine(line, Offset(x0, y), Offset(x1, y), stroke)
        fun v(x: Float, y0: Float = 0f, y1: Float = size.height) = drawLine(line, Offset(x, y0), Offset(x, y1), stroke)
        when (t.paper) {
            NotebookPaper.Blank -> {}
            NotebookPaper.NarrowRuled, NotebookPaper.WideRuled -> {
                val step = (if (t.paper == NotebookPaper.NarrowRuled) 7.1f else 8.7f) * mm
                var y = 20 * mm
                while (y < size.height - 12 * mm) { h(y); y += step }
                drawLine(col(t.color.accent), Offset(25 * mm, 0f), Offset(25 * mm, size.height), stroke)
            }
            NotebookPaper.Grid, NotebookPaper.Engineering -> {
                val step = 5 * mm
                var x = step
                while (x < size.width) { v(x); x += step }
                var y = step
                while (y < size.height) { h(y); y += step }
            }
            NotebookPaper.Dots -> {
                val step = 5 * mm
                var y = step
                while (y < size.height) {
                    var x = step
                    while (x < size.width) { drawCircle(line, 0.8f, Offset(x, y)); x += step }
                    y += step
                }
            }
            NotebookPaper.Cornell -> {
                val step = 7.1f * mm
                var y = 20 * mm
                while (y < size.height - 50 * mm) { h(y, 60 * mm); y += step }
                v(60 * mm, 20 * mm - step, size.height - 50 * mm)
                h(size.height - 50 * mm)
            }
            NotebookPaper.Music -> {
                var y = 20 * mm
                while (y + 8 * mm < size.height - 12 * mm) {
                    for (i in 0 until 5) h(y + i * 2 * mm, 15 * mm, size.width - 15 * mm)
                    y += 8 * mm + 13 * mm
                }
            }
        }
        if (dimmed) drawRect(Color.Gray.copy(alpha = 0.5f))
    }
}

/** Pick a paper (or a saved template) for one new page and insert it at [onInsert]. */
@Composable
fun TemplatePageSheet(vm: AppViewModel, initial: NotebookTemplate, onDismiss: () -> Unit, onInsert: (NotebookPage) -> Unit) {
    LaunchedEffect(Unit) { vm.refreshUserTemplates() }
    var picked by remember { mutableStateOf(initial) }
    var user by remember { mutableStateOf<String?>(null) }
    PdfSheet(onDismiss = onDismiss, dim = true) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            NotebookTemplatePicker(picked, user, vm.userTemplates, onPick = { picked = it; user = null }, onUser = { user = it })
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF1473E6))
                    .combinedClickable(role = Role.Button, onClick = { onInsert(user?.let(NotebookPage::User) ?: NotebookPage.Builtin(picked)) }),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.notebook_insert_page), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}
