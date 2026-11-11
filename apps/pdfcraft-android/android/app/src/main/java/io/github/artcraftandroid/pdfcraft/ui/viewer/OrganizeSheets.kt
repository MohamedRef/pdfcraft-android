package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LabelStyle
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.PdfField
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.ReplacePrompt
import io.github.artcraftandroid.pdfcraft.ui.SheetDivider
import io.github.artcraftandroid.pdfcraft.ui.SheetRow
import io.github.artcraftandroid.pdfcraft.ui.SheetTitle
import io.github.artcraftandroid.pdfcraft.ui.SplitMode
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

@Composable
private fun Choice(selected: Boolean, label: String, onClick: () -> Unit) {
    val c = pdfColors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = c.toolAccent, unselectedColor = c.icon))
        Text(label, color = c.text, fontSize = 16.sp)
    }
}

@Composable
private fun NumberField(value: String, onValue: (String) -> Unit, hint: String, decimal: Boolean = false, modifier: Modifier = Modifier) {
    PdfField(
        value,
        { v -> onValue(v.filter { it.isDigit() || decimal && it == '.' }.take(6)) },
        hint,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
}

@Composable
private fun SheetButtons(primary: String, enabled: Boolean, onPrimary: () -> Unit, secondary: String? = null, onSecondary: () -> Unit = {}) {
    val c = pdfColors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
        if (secondary != null) {
            androidx.compose.material3.OutlinedButton(onClick = onSecondary, enabled = enabled) { Text(secondary, color = if (enabled) c.text else c.textSecondary) }
        }
        androidx.compose.material3.Button(
            onClick = onPrimary, enabled = enabled,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = c.toolAccent),
        ) { Text(primary) }
    }
}

/**
 * Split: every N pages, before the selected pages, at top-level bookmarks, or by size. The parts
 * go to a folder the person picks, or to the share sheet.
 */
@Composable
fun SplitSheet(vm: AppViewModel, doc: OpenDocument, picked: List<Int>, onDismiss: () -> Unit) {
    val c = pdfColors
    var kind by remember { mutableStateOf(if (picked.any { it > 0 }) 1 else 0) }
    var every by remember { mutableStateOf("1") }
    var mb by remember { mutableStateOf("10") }
    val mode: SplitMode? = when (kind) {
        0 -> every.toIntOrNull()?.let { SplitMode.Every(it) }
        1 -> SplitMode.Before(picked)
        2 -> SplitMode.AtBookmarks
        else -> mb.toDoubleOrNull()?.let { SplitMode.MaxSize(it) }
    }
    val ok = mode?.usable(doc.pageCount) == true
    PdfSheet(onDismiss = onDismiss, dim = true) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SheetTitle(stringResource(R.string.pages_split_title))
            Choice(kind == 0, stringResource(R.string.pages_split_every)) { kind = 0 }
            if (kind == 0) NumberField(every, { every = it }, stringResource(R.string.pages_split_every_count), modifier = Modifier.padding(start = 60.dp, end = 16.dp, bottom = 8.dp))
            Choice(kind == 1, stringResource(R.string.pages_split_before_selected)) { kind = 1 }
            if (kind == 1 && picked.none { it > 0 }) {
                Text(stringResource(R.string.pages_split_before_none), color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 60.dp, end = 16.dp, bottom = 8.dp))
            }
            if (doc.info.hasOutline) Choice(kind == 2, stringResource(R.string.pages_split_bookmarks)) { kind = 2 }
            Choice(kind == 3, stringResource(R.string.pages_split_size)) { kind = 3 }
            if (kind == 3) NumberField(mb, { mb = it }, stringResource(R.string.pages_split_size_mb), decimal = true, modifier = Modifier.padding(start = 60.dp, end = 16.dp, bottom = 8.dp))
            SheetDivider()
            SheetButtons(
                stringResource(R.string.pages_save_to_folder), ok, { mode?.let { vm.split(doc, it, share = false) } },
                stringResource(R.string.common_share), { mode?.let { vm.split(doc, it, share = true) } },
            )
        }
    }
}

/** How labels from [start] in [style] with [prefix] read, for the sheet's preview line. */
internal fun labelPreview(style: LabelStyle, prefix: String, start: Int, count: Int = 3): String {
    fun roman(n: Int): String {
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i")
        var x = n
        return buildString { for (i in v.indices) while (x >= v[i]) { append(s[i]); x -= v[i] } }
    }
    // a, b, … z, aa, bb, … as PDF's alphabetic labels count
    fun alpha(n: Int): String = ('a' + (n - 1) % 26).toString().repeat((n - 1) / 26 + 1)
    return (0 until count).joinToString(", ") { k ->
        val n = start + k
        prefix + when (style) {
            LabelStyle.Decimal -> n.toString()
            LabelStyle.LowerRoman -> roman(n)
            LabelStyle.UpperRoman -> roman(n).uppercase()
            LabelStyle.LowerAlpha -> alpha(n)
            LabelStyle.UpperAlpha -> alpha(n).uppercase()
            LabelStyle.None -> ""
        }
    } + if (style == LabelStyle.None) "" else ", …"
}

/** Page labels: a range, a numbering style, a prefix and the first number, as Acrobat's Number Pages. */
@Composable
fun PageLabelsSheet(vm: AppViewModel, doc: OpenDocument, picked: List<Int>, onDismiss: () -> Unit) {
    val c = pdfColors
    var from by remember { mutableStateOf(((picked.firstOrNull() ?: 0) + 1).toString()) }
    var to by remember { mutableStateOf(((picked.lastOrNull() ?: (doc.pageCount - 1)) + 1).toString()) }
    var style by remember { mutableStateOf(LabelStyle.Decimal) }
    var prefix by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("1") }
    val f = from.toIntOrNull()
    val t = to.toIntOrNull()
    val s = start.toIntOrNull()
    val ok = f != null && t != null && s != null && f in 1..doc.pageCount && t in f..doc.pageCount && s >= 1 &&
        (style != LabelStyle.None || prefix.isNotEmpty())
    PdfSheet(onDismiss = onDismiss, dim = true) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SheetTitle(stringResource(R.string.pages_labels_title))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(from, { from = it }, stringResource(R.string.pages_labels_from), modifier = Modifier.weight(1f))
                NumberField(to, { to = it }, stringResource(R.string.pages_labels_to), modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.pages_labels_style), color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
            for (st in LabelStyle.entries) {
                Choice(style == st, if (st == LabelStyle.None) stringResource(R.string.pages_labels_style_none) else st.sample) { style = st }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PdfField(prefix, { prefix = it.take(32) }, stringResource(R.string.pages_labels_prefix), modifier = Modifier.weight(1f))
                if (style != LabelStyle.None) NumberField(start, { start = it }, stringResource(R.string.pages_labels_start), modifier = Modifier.weight(1f))
            }
            if (s != null && s >= 1) {
                Text(
                    stringResource(R.string.pages_labels_preview, labelPreview(style, prefix, s)),
                    color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 8.dp),
                )
            }
            SheetDivider(vertical = 8.dp)
            SheetButtons(stringResource(R.string.pages_labels_apply), ok, {
                onDismiss()
                vm.labelPages(doc, f!! - 1, t!! - 1, style.engine, prefix, s!!)
            })
        }
    }
}

/** Replace with…: which page of the picked file the replaced pages start from. */
@Composable
fun ReplacePagesDialog(vm: AppViewModel, prompt: ReplacePrompt) {
    val c = pdfColors
    var first by remember { mutableStateOf("1") }
    val n = first.toIntOrNull()
    PdfDialog(
        onDismiss = { vm.cancelReplace() },
        title = stringResource(R.string.pages_replace_title),
        confirm = stringResource(R.string.pages_replace_action),
        onConfirm = { n?.let { vm.replacePages(prompt, it) } },
        confirmEnabled = n != null && n >= 1,
    ) {
        Column {
            Text(pluralStringResource(R.plurals.pages_replace_explain, prompt.pages.size, prompt.pages.size), color = c.textSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.pages_replace_first_page), color = c.text, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            NumberField(first, { first = it }, stringResource(R.string.pages_replace_first_page))
        }
    }
}
