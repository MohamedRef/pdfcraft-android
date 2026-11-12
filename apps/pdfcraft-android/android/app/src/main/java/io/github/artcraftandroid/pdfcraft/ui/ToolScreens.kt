package io.github.artcraftandroid.pdfcraft.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.toBitmap
import io.github.artcraftandroid.pdfcraft.ui.viewer.formatSize
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext

/** A tool's full screen: a top bar with back and title, the content, and the action button at the bottom. */
@Composable
fun ToolScaffold(
    title: String,
    onBack: () -> Unit,
    action: String?,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    /** Black under the camera and the viewer's band behind the navigation buttons, as the viewer's own screens have. */
    viewerBands: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = pdfColors
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().background(if (viewerBands) Color.Black else c.surface).safeTopPadding()
            .background(if (viewerBands) c.navBand else c.surface).navigationBarsPadding().background(c.surface).imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(io.github.artcraftandroid.pdfcraft.ui.viewer.TopBarHeight).then(
                if (viewerBands) Modifier.drawBehind { drawRect(c.divider, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) } else Modifier,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) { BarIcon(LucideIcons.ArrowLeft, stringResource(R.string.common_back), onBack, iconSize = 27.dp) }
            Spacer(Modifier.width(16.dp))
            Text(title, color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        Column(Modifier.weight(1f).fillMaxWidth(), content = content)
        if (action != null) {
            Button(
                onClick = onAction,
                enabled = actionEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = c.button, contentColor = Color.White, disabledContainerColor = c.pressed),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(48.dp),
            ) { Text(action, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
        }
    }
}

/** A file as the tools show it: an icon, its name and size. */
@Composable
fun FileCard(name: String, size: Long?, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val c = pdfColors
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceRaised).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PdfBadge()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name.substringBeforeLast('.'), color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(name.substringAfterLast('.', "").uppercase().ifEmpty { null }, size?.let { formatSize(it) }).joinToString("  •  "), color = c.textSecondary, fontSize = 13.sp)
        }
        trailing?.invoke()
    }
}

/** The red document badge with "PDF" on it. */
@Composable
fun PdfBadge(size: Int = 36) {
    val c = pdfColors
    Box(Modifier.size(size.dp, (size * 1.2f).dp).clip(RoundedCornerShape(4.dp)).background(c.red), contentAlignment = Alignment.BottomCenter) {
        Text("PDF", color = Color.White, fontSize = (size / 3.6f).sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 3.dp))
    }
}

/**
 * A tool page as the reference lays its own out: a bar with only back (and [trailing]), a large
 * title, the content, then the action as a blue pill at the lower right.
 */
@Composable
fun ToolPage(
    title: String,
    onBack: () -> Unit,
    action: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = pdfColors
    BackHandler(onBack = onBack)
    // the keyboard covers the pill rather than lifting it, as the reference's does
    Column(Modifier.fillMaxSize().background(c.surface).safeTopPadding().background(c.navBand).navigationBarsPadding().background(c.surface)) {
        Row(Modifier.fillMaxWidth().height(io.github.artcraftandroid.pdfcraft.ui.viewer.TopBarHeight), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) { BarIcon(LucideIcons.ArrowLeft, stringResource(R.string.common_back), onBack, iconSize = 27.dp) }
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
            Spacer(Modifier.width(8.dp))
        }
        Text(title, color = c.label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 14.dp))
        Column(Modifier.weight(1f).fillMaxWidth(), content = content)
        Row(Modifier.fillMaxWidth().padding(end = 24.dp, bottom = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.End) {
            ActionPill(action, actionEnabled, onAction)
        }
    }
}

/** The blue pill a tool page ends with (grey while it can't be used). */
@Composable
fun ActionPill(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = pdfColors
    Box(
        modifier.heightIn(min = 40.dp).widthIn(min = 90.dp).clip(RoundedCornerShape(20.dp))
            .background(if (enabled) c.button else c.pressed).clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) Color.White else c.textSecondary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

/** The file a tool works on, in an outlined card: a picture of its first page, its name, type and size. */
@Composable
fun OutlinedFileCard(vm: AppViewModel, doc: OpenDocument) {
    val c = pdfColors
    var thumb by remember(doc.info.generation) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(doc.info.generation) {
        val eng = vm.engine ?: return@LaunchedEffect
        val p = doc.info.pages.firstOrNull() ?: return@LaunchedEffect
        val scale = 120f / maxOf(p.width, p.height).coerceAtLeast(1f)
        thumb = runCatching { withContext(eng.thumbnails) { eng.session.renderPage(doc.id, 0u, scale, null).toBitmap().asImageBitmap() } }.getOrNull()
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, c.divider, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp, 40.dp).background(Color.White)) {
            thumb?.let { Image(it, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(doc.name.substringBeforeLast('.'), color = c.label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("PDF • ${formatSize(doc.info.fileSize.toLong())}", color = c.text, fontSize = 13.sp)
        }
    }
}

/** A radio choice of a tool page: the circle, a bold name and a line under it. */
@Composable
internal fun ToolChoice(selected: Boolean, name: String, description: String?, onClick: () -> Unit) {
    val c = pdfColors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 8.dp, end = 16.dp, top = if (description == null) 0.dp else 6.dp, bottom = if (description == null) 0.dp else 8.dp),
        verticalAlignment = if (description == null) Alignment.CenterVertically else Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = c.toolAccent, unselectedColor = c.icon))
        Column(Modifier.padding(start = 6.dp, top = if (description == null) 0.dp else 10.dp)) {
            Text(name, color = c.label, fontSize = 16.sp, fontWeight = if (description == null) FontWeight.Normal else FontWeight.SemiBold)
            if (description != null) {
                Spacer(Modifier.height(6.dp))
                Text(description, color = c.text, fontSize = 14.sp)
            }
        }
    }
}

/** Compress PDF: one of three levels (medium by default), then a smaller copy in Downloads. */
@Composable
fun CompressScreen(vm: AppViewModel, doc: OpenDocument) {
    val c = pdfColors
    var level by remember { mutableStateOf(1) }
    var o by remember { mutableStateOf(vm.compressOptions) }
    var target by remember { mutableStateOf(o.targetMb?.let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() } ?: "") }
    var audit by remember { mutableStateOf(false) }
    val levels = listOf(
        stringResource(R.string.tools_compress_high) to stringResource(R.string.tools_compress_high_desc),
        stringResource(R.string.tools_compress_medium) to stringResource(R.string.tools_compress_medium_desc),
        stringResource(R.string.tools_compress_low) to stringResource(R.string.tools_compress_low_desc),
    )
    val targetOk = target.isEmpty() || target.toDoubleOrNull()?.let { it > 0 } == true
    fun options() = o.copy(targetMb = target.toDoubleOrNull()?.takeIf { it > 0 })
    ToolPage(stringResource(R.string.common_tool_compress), { vm.toolScreen = null }, stringResource(R.string.tools_compress_action), targetOk, {
        val chosen = options()
        vm.updateCompressOptions(chosen)
        vm.compress(doc, level, chosen)
    }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedFileCard(vm, doc)
            Text(
                stringResource(R.string.compress_whats_taking_space), color = c.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { audit = true }.padding(horizontal = 24.dp, vertical = 10.dp),
            )
            if (!o.enabled) {
                Text(stringResource(R.string.tools_compression_level), color = c.label, fontSize = 15.sp, modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 4.dp))
                levels.forEachIndexed { i, (name, desc) -> ToolChoice(level == i, name, desc) { level = i } }
            }
            Spacer(Modifier.height(8.dp))
            SwitchRow(stringResource(R.string.compress_advanced), o.enabled) { o = o.copy(enabled = it) }
            if (o.enabled) {
                Text(stringResource(R.string.compress_target_size), color = c.label, fontSize = 15.sp, modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 4.dp))
                io.github.artcraftandroid.pdfcraft.ui.PdfField(
                    target, { v -> target = v.filter { it.isDigit() || it == '.' }.take(7) }, stringResource(R.string.compress_target_hint),
                    modifier = Modifier.padding(horizontal = 24.dp), error = !targetOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                SwitchRow(stringResource(R.string.compress_for_phones), o.mobile) { o = o.copy(mobile = it) }
                if (!o.mobile) {
                    Text(stringResource(R.string.compress_resolution, o.ppi), color = c.text, fontSize = 15.sp, modifier = Modifier.padding(start = 24.dp, top = 8.dp))
                    androidx.compose.material3.Slider(
                        value = o.ppi.toFloat(), onValueChange = { o = o.copy(ppi = (it / 6).toInt() * 6) }, valueRange = 36f..600f,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Text(stringResource(R.string.compress_quality, o.quality), color = c.text, fontSize = 15.sp, modifier = Modifier.padding(start = 24.dp))
                    androidx.compose.material3.Slider(
                        value = o.quality.toFloat(), onValueChange = { o = o.copy(quality = it.toInt()) }, valueRange = 10f..100f,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
                SwitchRow(stringResource(R.string.compress_remove_invalid_links), o.removeInvalidLinks) { o = o.copy(removeInvalidLinks = it) }
                SwitchRow(stringResource(R.string.compress_discard_form_actions), o.discardFormActions) { o = o.copy(discardFormActions = it) }
                SwitchRow(stringResource(R.string.compress_discard_tags), o.discardTags) { o = o.copy(discardTags = it) }
                SwitchRow(stringResource(R.string.compress_discard_alternate_images), o.discardAlternateImages) { o = o.copy(discardAlternateImages = it) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (audit) SpaceAuditDialog(vm, doc) { audit = false }
}

/** What's taking space: each kind of content as a bar with its size and share (`doc_audit_space`). */
@Composable
private fun SpaceAuditDialog(vm: AppViewModel, doc: OpenDocument, onDismiss: () -> Unit) {
    val c = pdfColors
    var rows by remember { mutableStateOf<List<Triple<String, Long, Double>>?>(null) }
    LaunchedEffect(Unit) { rows = vm.auditSpace(doc) ?: emptyList() }
    PdfDialog(onDismiss = onDismiss, title = stringResource(R.string.compress_whats_taking_space), confirm = stringResource(R.string.common_ok), onConfirm = onDismiss, dismiss = null) {
        val list = rows
        if (list == null) { Text("…", color = c.textSecondary); return@PdfDialog }
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            for ((label, bytes, percent) in list.filter { it.second > 0 }) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(label, color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("${io.github.artcraftandroid.pdfcraft.ui.viewer.formatSize(bytes)} · ${"%.1f".format(percent)} %", color = c.textSecondary, fontSize = 13.sp)
                }
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.divider)) {
                    Box(Modifier.fillMaxWidth((percent / 100.0).toFloat().coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.toolAccent))
                }
            }
        }
    }
}

/** A password field of Set password: its label above an outlined box, with the eye to show it. */
@Composable
private fun PasswordField(label: String, value: String, onValue: (String) -> Unit, visible: Boolean, onToggle: (() -> Unit)?, error: Boolean, focus: androidx.compose.ui.focus.FocusRequester? = null) {
    val c = pdfColors
    var focused by remember { mutableStateOf(false) }
    Text(label, color = c.label, fontSize = 16.sp, modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(44.dp).clip(RoundedCornerShape(4.dp)).background(c.menu)
            .border(if (focused) 2.dp else 1.dp, if (error) c.red else if (focused) c.toolAccent else c.divider, RoundedCornerShape(4.dp)).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onValue, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = c.text, fontSize = 17.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.toolAccent),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.weight(1f).then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                .onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label },
        )
        BarIcon(if (visible) LucideIcons.Eye else LucideIcons.EyeOff, stringResource(if (visible) R.string.ui_hide_password else R.string.ui_show_password), { onToggle?.invoke() }, size = 44.dp)
    }
}

/** Set password: a password twice (6 to 128 printable characters), then a protected copy in Downloads. */
@Composable
fun ProtectScreen(vm: AppViewModel, doc: OpenDocument) {
    val c = pdfColors
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    // Advanced: a permissions password and what it restricts (#177)
    var restrict by remember { mutableStateOf(false) }
    var owner by remember { mutableStateOf("") }
    var ownerConfirm by remember { mutableStateOf("") }
    var rules by remember { mutableStateOf(Restrictions()) }
    var removing by remember { mutableStateOf(false) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val bad = pw.isNotEmpty() && !passwordOk(pw)
    val mismatch = confirm.isNotEmpty() && confirm != pw
    val ownerBad = owner.isNotEmpty() && (!passwordOk(owner) || owner == pw)
    val ownerMismatch = ownerConfirm.isNotEmpty() && ownerConfirm != owner
    val args = if (mismatch || pw.isNotEmpty() && confirm.isEmpty() || restrict && (ownerMismatch || ownerConfirm.isEmpty())) null
        else protectArgs(pw, if (restrict) owner else null, rules)
    ToolPage(stringResource(R.string.common_tool_set_password), { vm.toolScreen = null }, stringResource(R.string.common_tool_set_password), args != null, { args?.let { vm.protect(doc, it) } },
        trailing = { BarIcon(LucideIcons.Info, stringResource(R.string.tools_about_passwords), { info = true }) }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.tools_protect_intro), color = c.label, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 24.dp))
            PasswordField(stringResource(R.string.tools_new_password), pw, { pw = it }, visible, { visible = !visible }, bad, focus)
            if (bad) Text(stringResource(R.string.tools_password_rule), color = c.red, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            PasswordField(stringResource(R.string.tools_repeat_password), confirm, { confirm = it }, visible, { visible = !visible }, mismatch)
            if (mismatch) Text(stringResource(R.string.tools_password_mismatch), color = c.red, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            Spacer(Modifier.height(16.dp))
            SwitchRow(stringResource(R.string.protect_restrict), restrict) { restrict = it }
            if (restrict) {
                Text(stringResource(R.string.protect_restrict_explain), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp))
                PasswordField(stringResource(R.string.protect_permissions_password), owner, { owner = it }, visible, { visible = !visible }, ownerBad)
                if (ownerBad) Text(stringResource(if (owner == pw) R.string.protect_passwords_differ else R.string.tools_password_rule), color = c.red, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                PasswordField(stringResource(R.string.tools_repeat_password), ownerConfirm, { ownerConfirm = it }, visible, { visible = !visible }, ownerMismatch)
                if (ownerMismatch) Text(stringResource(R.string.tools_password_mismatch), color = c.red, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                Spacer(Modifier.height(8.dp))
                SwitchRow(stringResource(R.string.protect_allow_printing), rules.printing) { rules = rules.copy(printing = it) }
                SwitchRow(stringResource(R.string.protect_allow_changes), rules.changes) { rules = rules.copy(changes = it) }
                SwitchRow(stringResource(R.string.protect_allow_copying), rules.copy) { rules = rules.copy(copy = it) }
                SwitchRow(stringResource(R.string.protect_allow_screen_readers), rules.accessibility) { rules = rules.copy(accessibility = it) }
            }
            if (doc.info.encrypted) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.protect_remove), color = c.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { removing = true }.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (info) {
        PdfDialog(onDismiss = { info = false }, title = stringResource(R.string.tools_about_passwords), confirm = stringResource(R.string.common_ok), onConfirm = { info = false }, dismiss = null) {
            Text(stringResource(R.string.tools_about_passwords_body), color = c.text, fontSize = 14.sp)
        }
    }
    if (removing) {
        PdfDialog(onDismiss = { removing = false }, title = stringResource(R.string.protect_remove), confirm = stringResource(R.string.protect_remove_action), onConfirm = { removing = false; vm.unprotect(doc) }) {
            Text(stringResource(R.string.protect_remove_explain), color = c.text, fontSize = 14.sp)
        }
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Switch) { onChange(!checked) }.padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = pdfColors.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
        PdfSwitch(checked, onChange = onChange)
    }
}

/**
 * Export PDF: Document (Word, RTF, Excel, PowerPoint, web page, Markdown, plain text), Image of its
 * pages (JPEG, PNG, TIFF at a chosen resolution and quality), or Pictures in the PDF (#173).
 */
@Composable
fun ExportScreen(vm: AppViewModel, doc: OpenDocument) {
    val c = pdfColors
    var tab by remember { mutableStateOf(0) }
    // the image format used last time comes first chosen
    var format by remember(tab) { mutableStateOf(if (tab == 1) listOf("jpg", "png", "tif").indexOf(vm.lastImageFormat).coerceAtLeast(0) else 0) }
    var dpi by remember { mutableStateOf(vm.imageDpi) }
    var quality by remember { mutableStateOf(vm.imageQuality) }
    var skipSmall by remember { mutableStateOf(true) }
    val formats = when (tab) {
        0 -> listOf(
            stringResource(R.string.tools_format_word) to "docx", stringResource(R.string.tools_format_rtf) to "rtf",
            stringResource(R.string.tools_format_excel) to "xlsx", stringResource(R.string.tools_format_powerpoint) to "pptx",
            stringResource(R.string.export_format_html) to "html", stringResource(R.string.export_format_markdown) to "md",
            stringResource(R.string.export_format_text) to "txt",
        )
        1 -> listOf(stringResource(R.string.tools_format_jpeg) to "jpg", stringResource(R.string.tools_format_png) to "png", stringResource(R.string.export_format_tiff) to "tif")
        else -> emptyList()
    }
    val textBlocked = doc.info.encrypted && !doc.info.allowsCopying
    ToolPage(stringResource(R.string.common_tool_export), { vm.toolScreen = null }, stringResource(if (tab == 2) R.string.pages_save_to_folder else R.string.tools_export_action), !(tab != 1 && textBlocked), {
        when (tab) {
            0 -> vm.exportDocument(doc, formats[format].second)
            1 -> { vm.updateImageSettings(dpi, quality); vm.exportImages(doc, formats[format].second) }
            else -> vm.exportAllImages(doc, skipSmall, share = false)
        }
    }) {
        // the tabs sit at the left, each underlined across its own label
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(c.divider))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf(R.string.tools_export_document, R.string.tools_export_image, R.string.export_pictures_in_pdf).forEachIndexed { i, label ->
                    Column(Modifier.clickable { tab = i }.padding(horizontal = 8.dp).width(androidx.compose.foundation.layout.IntrinsicSize.Max)) {
                        Text(stringResource(label), color = if (tab == i) c.label else c.text, fontSize = 16.sp, modifier = Modifier.padding(vertical = 12.dp))
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (tab == i) c.label else Color.Transparent))
                    }
                    if (i < 2) Spacer(Modifier.width(14.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (tab != 1 && textBlocked) {
                Text(stringResource(R.string.tools_export_not_allowed), color = c.red, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
            }
            formats.forEachIndexed { i, (label, _) -> ToolChoice(format == i, label, null) { format = i } }
            if (tab == 1) {
                Text(stringResource(R.string.export_resolution), color = c.label, fontSize = 15.sp, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp))
                for (d in listOf(72, 150, 300, 600)) ToolChoice(dpi == d, stringResource(R.string.export_dpi, d), null) { dpi = d }
                if (formats.getOrNull(format)?.second == "jpg") {
                    Text(stringResource(R.string.compress_quality, quality), color = c.text, fontSize = 15.sp, modifier = Modifier.padding(start = 24.dp, top = 12.dp))
                    androidx.compose.material3.Slider(
                        value = quality.toFloat(), onValueChange = { quality = it.toInt() }, valueRange = 10f..100f,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
            if (tab == 2) {
                Text(stringResource(R.string.export_pictures_explain), color = c.text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 24.dp))
                SwitchRow(stringResource(R.string.export_skip_small), skipSmall) { skipSmall = it }
                Text(
                    stringResource(R.string.common_share), color = c.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(enabled = !textBlocked, role = androidx.compose.ui.semantics.Role.Button) { vm.exportAllImages(doc, skipSmall, share = true) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Create PDF's first page: where the files come from (the photos, or any file through the
 * system's picker), and a scan to make a new one. Choosing closes this page.
 */
@Composable
fun CreateSourcesScreen(vm: AppViewModel) {
    val c = pdfColors
    val back = { vm.toolScreen = null }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().background(c.surface).safeTopPadding().background(c.navBand).navigationBarsPadding().background(c.surface)) {
        Row(Modifier.fillMaxWidth().height(io.github.artcraftandroid.pdfcraft.ui.viewer.TopBarHeight), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) { BarIcon(LucideIcons.ArrowLeft, stringResource(R.string.common_back), back, iconSize = 27.dp) }
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.tools_create_title), color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        SourceRow(LucideIcons.Images, stringResource(R.string.home_photos)) { vm.toolScreen = null; vm.pickPhotosToPdf() }
        SourceRow(LucideIcons.FolderOpen, stringResource(R.string.tools_browse_files)) { vm.toolScreen = null; vm.pickFiles(FilePickKind.Create) }
        Text(stringResource(R.string.tools_or_make_new), color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp))
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(c.divider))
        SourceRow(LucideIcons.Camera, stringResource(R.string.tools_scan_document)) { vm.toolScreen = null; vm.startScan() }
        SourceRow(LucideIcons.BookOpen, stringResource(R.string.notebook_new)) { vm.toolScreen = null; vm.study.newNotebookOpen = true }
    }
}

@Composable
private fun SourceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val c = pdfColors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(c.pressed), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.icon, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text(label, color = c.label, fontSize = 16.sp)
    }
}

/** The tool screen that is open. */
@Composable
fun ToolScreenHost(vm: AppViewModel, screen: ToolScreen) {
    when (screen) {
        is ToolScreen.Compress -> CompressScreen(vm, screen.doc)
        is ToolScreen.Combine -> CombineScreen(vm)
        is ToolScreen.Protect -> ProtectScreen(vm, screen.doc)
        is ToolScreen.Crop -> CropScreen(vm, screen.doc)
        is ToolScreen.Export -> ExportScreen(vm, screen.doc)
        ToolScreen.Bates -> BatesScreen(vm)
        ToolScreen.OcrBatch -> OcrBatchScreen(vm)
        ToolScreen.Scan -> ScanScreen(vm)
        ToolScreen.Create -> CreateSourcesScreen(vm)
        is ToolScreen.PageMarks -> io.github.artcraftandroid.pdfcraft.ui.viewer.PageMarksScreen(vm, screen.doc)
    }
}


/**
 * Combine files, as the reference's: "Combine files" with an outlined Add files at the top, the
 * files in order (the open document first when there is one; hold the grip to reorder, the minus to
 * remove), and Combine across the bottom once there are two to twelve of them.
 */
@Composable
fun CombineScreen(vm: AppViewModel) {
    var naming by remember { mutableStateOf(false) }
    val c = pdfColors
    val files = vm.combineFiles
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { 58.dp.toPx() }
    val ok = files.size in 2..12
    val back = { vm.toolScreen = null; vm.combineFiles.clear() }
    val dragLabel = stringResource(R.string.tools_drag_to_reorder)
    val removedMessage = stringResource(R.string.tools_file_removed)
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().background(c.surface).safeTopPadding().background(c.navBand).navigationBarsPadding().background(c.surface)) {
        Row(
            Modifier.fillMaxWidth().height(io.github.artcraftandroid.pdfcraft.ui.viewer.TopBarHeight)
                .drawBehind { drawRect(c.divider, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) { BarIcon(LucideIcons.ArrowLeft, stringResource(R.string.common_back), back, iconSize = 27.dp) }
            Spacer(Modifier.width(4.dp))
            Text(
                if (files.size > 1) pluralStringResource(R.plurals.tools_file_count, files.size, files.size) else stringResource(R.string.common_tool_combine),
                color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.padding(end = 20.dp).height(36.dp).clip(RoundedCornerShape(18.dp)).border(2.dp, c.label, RoundedCornerShape(18.dp))
                    .clickable { vm.pickFiles(FilePickKind.Combine) }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.tools_add_files), color = c.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
        if (files.size > 12) Text(stringResource(R.string.tools_combine_too_many), color = c.red, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp)) {
            itemsIndexed(files, key = { _, f -> f.uri.toString() }) { i, f ->
                Row(
                    Modifier.fillMaxWidth().height(58.dp)
                        .offset { IntOffset(0, if (dragIndex == i) dragOffset.roundToInt() else 0) }
                        .background(if (dragIndex == i) c.pressed else Color.Transparent)
                        .drawBehind { drawRect(c.divider, Offset(8.dp.toPx(), size.height - 1.dp.toPx()), Size(size.width - 16.dp.toPx(), 1.dp.toPx())) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(44.dp).pointerInput(f.uri) {
                            detectDragGestures(
                                onDragStart = { dragIndex = files.indexOf(f); dragOffset = 0f },
                                onDragEnd = { dragIndex = null; dragOffset = 0f },
                                onDragCancel = { dragIndex = null; dragOffset = 0f },
                            ) { change, d ->
                                change.consume()
                                val from = dragIndex ?: return@detectDragGestures
                                dragOffset += d.y
                                val steps = (dragOffset / rowPx).toInt()
                                val to = (from + steps).coerceIn(0, files.lastIndex)
                                if (to != from) {
                                    files.add(to, files.removeAt(from))
                                    dragIndex = to
                                    dragOffset -= (to - from) * rowPx
                                }
                            }
                        }.semantics { contentDescription = dragLabel },
                        contentAlignment = Alignment.Center,
                    ) { Icon(LucideIcons.GripVertical, null, tint = c.icon, modifier = Modifier.size(20.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(f.name.substringBeforeLast('.'), color = c.label, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(f.name.substringAfterLast('.', "").uppercase(), f.size?.let { formatSize(it) }).joinToString(" • "), color = c.text, fontSize = 14.sp)
                    }
                    BarIcon(LucideIcons.CircleMinus, stringResource(R.string.tools_remove_file, f.name), { files.remove(f); vm.tell(removedMessage) })
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp).height(42.dp).clip(RoundedCornerShape(21.dp))
                .background(if (ok) c.button else c.pressed).clickable(enabled = ok) { naming = true },
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.tools_combine_as_pdf), color = if (ok) Color.White else c.textSecondary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        if (naming) {
            var name by remember { mutableStateOf("Combine " + java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(java.util.Date())) }
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            PdfDialog(
                onDismiss = { naming = false }, title = stringResource(R.string.tools_combine_name_title), confirm = stringResource(R.string.common_save),
                onConfirm = { naming = false; vm.combine(name.trim().ifEmpty { "Combine" }) },
                confirmEnabled = name.isNotBlank(),
            ) { PdfField(name, { name = it }, stringResource(R.string.common_file_name), focus = focus) }
        }
    }
}


/** A file picked for a tool: where it is, its name and size. */
data class PickedFile(val uri: android.net.Uri, val name: String, val size: Long?)

/** What a file picker was opened for. */
enum class FilePickKind { Combine, Create, Insert, Open, DigitalId, Replace, Attach, Library, MarkImage }


/**
 * Crop pages, laid out as the reference's: the page inset 32 dp on black, a crop frame with ringed
 * corner handles and pill handles mid-edge, the page number in a grey pill under it, then the All
 * pages switch and Reset. Save and Reset stay grey until the frame moves.
 */
@Composable
fun CropScreen(vm: AppViewModel, doc: OpenDocument) {
    val c = pdfColors
    var page by remember { mutableStateOf(doc.viewer.currentPage) }
    var allPages by remember { mutableStateOf(false) }
    // the crop as fractions of the page: left, top, right, bottom
    var rect by remember { mutableStateOf(floatArrayOf(0f, 0f, 1f, 1f)) }
    var bitmap by remember(page) { mutableStateOf<ImageBitmap?>(null) }
    val p = doc.info.pages.getOrNull(page)
    LaunchedEffect(page) {
        val eng = vm.engine ?: return@LaunchedEffect
        val info = p ?: return@LaunchedEffect
        val scale = 1200f / maxOf(info.width, info.height).coerceAtLeast(1f)
        bitmap = runCatching { withContext(eng.thumbnails) { eng.session.renderPage(doc.id, page.toUInt(), scale, null).toBitmap().asImageBitmap() } }.getOrNull()
    }
    val changed = !(rect[0] == 0f && rect[1] == 0f && rect[2] == 1f && rect[3] == 1f)
    ToolScaffold(stringResource(if (allPages) R.string.tools_all_pages else R.string.tools_current_page), { vm.toolScreen = null }, null, false, {},
        viewerBands = true,
        trailing = {
            Text(
                stringResource(R.string.common_save), color = if (changed) c.label else c.textSecondary.copy(alpha = 0.5f), fontSize = 17.sp,
                modifier = Modifier.clickable(enabled = changed) {
                    val info = p ?: return@clickable
                    vm.crop(doc, if (allPages) null else page, floatArrayOf(rect[0] * info.width, rect[1] * info.height, rect[2] * info.width, rect[3] * info.height))
                }.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(c.desk), contentAlignment = Alignment.Center) {
            val info = p ?: return@BoxWithConstraints
            val ratio = info.width / info.height.coerceAtLeast(1f)
            // the page sits 32 dp in from the sides; touches are taken a margin beyond its edges
            val margin = 32f
            val boxW = minOf(maxWidth.value - 2 * margin, (maxHeight.value - 2 * margin) * ratio).coerceAtLeast(1f)
            val boxH = boxW / ratio
            val density = LocalDensity.current
            val wPx = with(density) { boxW.dp.toPx() }
            val hPx = with(density) { boxH.dp.toPx() }
            val mPx = with(density) { margin.dp.toPx() }
            Box(
                Modifier.size((boxW + 2 * margin).dp, (boxH + 2 * margin).dp).pointerInput(page) {
                    var mode = -1
                    detectDragGestures(
                        onDragStart = { o ->
                            val x = (o.x - mPx) / wPx
                            val y = (o.y - mPx) / hPx
                            val near = 0.08f
                            val l = kotlin.math.abs(x - rect[0]) < near
                            val r = kotlin.math.abs(x - rect[2]) < near
                            val t = kotlin.math.abs(y - rect[1]) < near
                            val b = kotlin.math.abs(y - rect[3]) < near
                            mode = when {
                                l && t -> 0; r && t -> 1; r && b -> 2; l && b -> 3
                                l -> 4; t -> 5; r -> 6; b -> 7
                                x in rect[0]..rect[2] && y in rect[1]..rect[3] -> 8
                                else -> -1
                            }
                        },
                    ) { change, d ->
                        change.consume()
                        val dx = d.x / wPx
                        val dy = d.y / hPx
                        val n = rect.copyOf()
                        val min = 0.1f
                        when (mode) {
                            0 -> { n[0] += dx; n[1] += dy }
                            1 -> { n[2] += dx; n[1] += dy }
                            2 -> { n[2] += dx; n[3] += dy }
                            3 -> { n[0] += dx; n[3] += dy }
                            4 -> n[0] += dx
                            5 -> n[1] += dy
                            6 -> n[2] += dx
                            7 -> n[3] += dy
                            8 -> {
                                val mx = dx.coerceIn(-n[0], 1f - n[2])
                                val my = dy.coerceIn(-n[1], 1f - n[3])
                                n[0] += mx; n[2] += mx; n[1] += my; n[3] += my
                            }
                            else -> return@detectDragGestures
                        }
                        n[0] = n[0].coerceIn(0f, n[2] - min); n[1] = n[1].coerceIn(0f, n[3] - min)
                        n[2] = n[2].coerceIn(n[0] + min, 1f); n[3] = n[3].coerceIn(n[1] + min, 1f)
                        rect = n
                    }
                },
            ) {
                Box(Modifier.padding(margin.dp).fillMaxSize().background(Color.White)) {
                    bitmap?.let { Image(it, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                }
                val ring = c.toolAccent
                val fill = c.menu
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val l = mPx + rect[0] * wPx
                    val t = mPx + rect[1] * hPx
                    val r = mPx + rect[2] * wPx
                    val b = mPx + rect[3] * hPx
                    val dim = Color.Black.copy(alpha = 0.45f)
                    drawRect(dim, Offset(mPx, mPx), Size(wPx, t - mPx))
                    drawRect(dim, Offset(mPx, b), Size(wPx, mPx + hPx - b))
                    drawRect(dim, Offset(mPx, t), Size(l - mPx, b - t))
                    drawRect(dim, Offset(r, t), Size(mPx + wPx - r, b - t))
                    val line = 2.dp.toPx()
                    drawRect(ring, Offset(l, t), Size(r - l, b - t), style = Stroke(line))
                    // corners: 18 dp rings; edges: 44 x 18 dp pills along the edge
                    val corner = 9.dp.toPx()
                    for ((hx, hy) in listOf(l to t, r to t, r to b, l to b)) {
                        drawCircle(fill, corner, Offset(hx, hy))
                        drawCircle(ring, corner - line / 2, Offset(hx, hy), style = Stroke(line))
                    }
                    val long = 44.dp.toPx()
                    val short = 18.dp.toPx()
                    for ((hx, hy, across) in listOf(Triple((l + r) / 2, t, true), Triple((l + r) / 2, b, true), Triple(l, (t + b) / 2, false), Triple(r, (t + b) / 2, false))) {
                        val w = if (across) long else short
                        val h = if (across) short else long
                        val tl = Offset(hx - w / 2, hy - h / 2)
                        val cr = androidx.compose.ui.geometry.CornerRadius(short / 2)
                        drawRoundRect(fill, tl, Size(w, h), cr)
                        drawRoundRect(ring, Offset(tl.x + line / 2, tl.y + line / 2), Size(w - line, h - line), cr, style = Stroke(line))
                    }
                }
            }
        }
        // the page in a grey pill (with arrows to step through the pages when cropping them all)
        Row(Modifier.fillMaxWidth().background(c.desk).height(88.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (allPages) BarIcon(LucideIcons.ChevronLeft, stringResource(R.string.tools_previous_page), { page = (page - 1).coerceAtLeast(0) }, enabled = page > 0)
            Box(
                Modifier.widthIn(min = 102.dp).height(32.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFFD1D1D1)).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.tools_page_number, page + 1), color = Color(0xFF2C2C2C), fontSize = 15.sp) }
            if (allPages) BarIcon(LucideIcons.ChevronRight, stringResource(R.string.tools_next_page), { page = (page + 1).coerceAtMost(doc.pageCount - 1) }, enabled = page < doc.pageCount - 1)
        }
        Row(Modifier.fillMaxWidth().height(54.dp).background(c.surface).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PdfSwitch(allPages, { allPages = it })
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.tools_all_pages), color = c.label, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.tools_reset), color = if (changed) c.label else c.textSecondary.copy(alpha = 0.5f), fontSize = 16.sp,
                modifier = Modifier.clickable(enabled = changed) { rect = floatArrayOf(0f, 0f, 1f, 1f) }.padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}


/**
 * A scanned page: the photo's file, its thumbnail (as taken) and the part of it kept: [corners]
 * as shares of the photo's width and height (x1, y1 … x4, y4 from the top left, clockwise), the
 * whole photo when No crop was chosen or no edges were found. [found] is what Auto-detect found.
 */
data class ScanPage(val file: java.io.File, val thumb: ImageBitmap, val corners: FloatArray = WHOLE, val found: FloatArray? = null) {
    companion object {
        val WHOLE = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
    }
}

/** The looks a scan can have, and how the engine's camera clean-up gives each. */
enum class ScanFilter(@StringRes val label: Int, val mode: String, val level: Int) {
    Original(R.string.tools_filter_original, "original", 0),
    /** The scan crate's Auto look (#298): no shadows, white paper, dark ink, real colours. */
    CleanScan(R.string.tools_filter_clean_scan, "auto", 60),
    AutoColor(R.string.tools_filter_auto_colour, "colour", 50),
    Grayscale(R.string.tools_filter_grayscale, "greyscale", 40),
    Whiteboard(R.string.tools_filter_whiteboard, "colour", 85),
    LightText(R.string.tools_filter_light_text, "bw", 60),
}

/** Scan: pages taken with the camera, reviewed (name, retake, crop, delete, look), then saved as a PDF. */
@Composable
fun ScanScreen(vm: AppViewModel) {
    val c = pdfColors
    val pages = vm.scanPages
    var filter by remember { mutableStateOf(ScanFilter.CleanScan) }
    var current by remember { mutableStateOf(0) }
    var cropping by remember { mutableStateOf<java.io.File?>(null) }
    var naming by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (pages.isEmpty()) vm.takeScanPhoto() }
    // a page just taken is the one shown
    var seen by remember { mutableStateOf(pages.size) }
    LaunchedEffect(pages.size) {
        if (pages.size > seen) current = pages.lastIndex
        seen = pages.size
    }
    val croppingAt = pages.indexOfFirst { it.file == cropping }
    if (croppingAt >= 0) {
        ScanCrop(pages[croppingAt], onDone = { pages[croppingAt] = pages[croppingAt].copy(corners = it); cropping = null }, onCancel = { cropping = null })
        return
    }
    val addPageLabel = stringResource(R.string.tools_add_page)
    ToolScaffold(
        if (pages.isEmpty()) stringResource(R.string.common_tool_scan) else stringResource(R.string.tools_page_of, current.coerceAtMost(pages.lastIndex) + 1, pages.size),
        { vm.discardScan() },
        stringResource(R.string.tools_save_pdf), pages.isNotEmpty(), { vm.saveScan(filter) },
    ) {
        // the new file's name, changeable before it's saved (not when the pages go into an open document)
        if (vm.insertScanAt == null && vm.scanName.isNotBlank()) {
            val nameLabel = stringResource(R.string.tools_scan_name_description, vm.scanName)
            Row(
                Modifier.fillMaxWidth().clickable { naming = true }.padding(horizontal = 16.dp, vertical = 10.dp).semantics { contentDescription = nameLabel },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LucideIcons.FileText, null, tint = c.icon, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(vm.scanName, color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Icon(LucideIcons.Pencil, null, tint = c.icon, modifier = Modifier.size(20.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(c.desk), contentAlignment = Alignment.Center) {
            val page = pages.getOrNull(current.coerceAtMost(pages.lastIndex))
            if (page != null) {
                ScanPhoto(page.thumb, page.corners, Modifier.fillMaxSize())
            } else {
                Text(stringResource(R.string.tools_scan_empty), color = c.textSecondary)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pages.forEachIndexed { i, p ->
                Image(p.thumb, stringResource(R.string.tools_page_number, i + 1), modifier = Modifier.size(56.dp, 72.dp).clip(RoundedCornerShape(4.dp)).border(2.dp, if (i == current) c.accent else Color.Transparent, RoundedCornerShape(4.dp)).clickable { current = i }, contentScale = ContentScale.Crop)
            }
            Box(Modifier.size(56.dp, 72.dp).clip(RoundedCornerShape(4.dp)).background(c.surfaceRaised).clickable { vm.takeScanPhoto() }.semantics { contentDescription = addPageLabel }, contentAlignment = Alignment.Center) {
                Icon(LucideIcons.Plus, null, tint = c.icon)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ScanFilter.entries.forEach { f ->
                Text(
                    stringResource(f.label), color = if (f == filter) c.accent else c.text, fontSize = 14.sp,
                    modifier = Modifier.clip(CircleShape).background(if (f == filter) c.pressed else Color.Transparent).clickable { filter = f }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { vm.retakeScan(current) }, enabled = pages.isNotEmpty()) { Text(stringResource(R.string.tools_retake), color = c.accent) }
            TextButton(onClick = { cropping = pages.getOrNull(current.coerceAtMost(pages.lastIndex))?.file }, enabled = pages.isNotEmpty()) { Text(stringResource(R.string.tools_crop), color = c.accent) }
            TextButton(onClick = { if (pages.isNotEmpty()) { pages.removeAt(current.coerceAtMost(pages.lastIndex)); current = (current - 1).coerceAtLeast(0) } }, enabled = pages.isNotEmpty()) { Text(stringResource(R.string.common_delete), color = c.accent) }
        }
    }
    if (naming) {
        var name by remember { mutableStateOf(vm.scanName) }
        PdfDialog(
            onDismiss = { naming = false }, title = stringResource(R.string.common_file_name),
            confirm = stringResource(R.string.common_ok), confirmEnabled = name.isNotBlank(), onConfirm = { vm.scanName = name.trim(); naming = false },
        ) {
            PdfField(name, { name = it }, stringResource(R.string.common_file_name))
        }
    }
}

/**
 * Crop one scanned page: drag the corners onto the document's, or take what Auto-detect found,
 * or the whole photo (No crop).
 */
@Composable
private fun ScanCrop(page: ScanPage, onDone: (FloatArray) -> Unit, onCancel: () -> Unit) {
    val c = pdfColors
    var corners by remember(page.file) { mutableStateOf(page.corners) }
    ToolScaffold(stringResource(R.string.tools_crop), onCancel, stringResource(R.string.common_done), true, { onDone(corners) }) {
        Box(Modifier.weight(1f).fillMaxWidth().background(c.desk)) {
            ScanPhoto(page.thumb, corners, Modifier.fillMaxSize(), onMove = { corners = it })
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { page.found?.let { corners = it } }, enabled = page.found != null) { Text(stringResource(R.string.tools_auto_detect), color = if (page.found != null) c.accent else c.textSecondary) }
            TextButton(onClick = { corners = ScanPage.WHOLE }) { Text(stringResource(R.string.tools_no_crop), color = c.accent) }
        }
    }
}

/** Whether [q] (x1, y1 … x4, y4) is a convex quadrilateral, its corners going round one way. */
private fun convexQuad(q: FloatArray): Boolean {
    var sign = 0
    for (i in 0 until 4) {
        val (ax, ay) = q[2 * i] to q[2 * i + 1]
        val (bx, by) = q[2 * ((i + 1) % 4)] to q[2 * ((i + 1) % 4) + 1]
        val (cx, cy) = q[2 * ((i + 2) % 4)] to q[2 * ((i + 2) % 4) + 1]
        val cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
        val s = if (cross > 1e-6f) 1 else if (cross < -1e-6f) -1 else return false
        if (sign != 0 && s != sign) return false
        sign = s
    }
    return true
}

/**
 * A scanned page's photo, fitted, with the part kept outlined and the rest dimmed: [corners] as
 * shares of the photo. With [onMove], each corner has a handle to drag (the shape stays a convex
 * four-sided one, as straightening needs).
 */
@Composable
private fun ScanPhoto(thumb: ImageBitmap, corners: FloatArray, modifier: Modifier = Modifier, onMove: ((FloatArray) -> Unit)? = null) {
    val c = pdfColors
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        // room around the photo for the handles
        val pad = with(density) { (if (onMove != null) 24.dp else 16.dp).toPx() }
        val k = minOf((constraints.maxWidth - 2 * pad) / thumb.width, (constraints.maxHeight - 2 * pad) / thumb.height).coerceAtLeast(0.01f)
        val (iw, ih) = thumb.width * k to thumb.height * k
        val origin = Offset((constraints.maxWidth - iw) / 2f, (constraints.maxHeight - ih) / 2f)
        val latest = androidx.compose.runtime.rememberUpdatedState(corners)
        val point = { q: FloatArray, i: Int -> Offset(origin.x + q[2 * i] * iw, origin.y + q[2 * i + 1] * ih) }
        val drag = if (onMove == null) Modifier else Modifier.pointerInput(thumb, iw, ih) {
            var held = -1
            detectDragGestures(
                onDragStart = { p ->
                    val q = latest.value
                    held = (0..3).minByOrNull { (point(q, it) - p).getDistance() }?.takeIf { (point(q, it) - p).getDistance() < 48.dp.toPx() } ?: -1
                },
                onDragEnd = { held = -1 },
                onDragCancel = { held = -1 },
            ) { change, d ->
                if (held < 0) return@detectDragGestures
                change.consume()
                val q = latest.value.copyOf()
                q[2 * held] = (q[2 * held] + d.x / iw).coerceIn(0f, 1f)
                q[2 * held + 1] = (q[2 * held + 1] + d.y / ih).coerceIn(0f, 1f)
                if (convexQuad(q)) onMove(q)
            }
        }
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().then(drag)) {
            drawImage(thumb, dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()), dstSize = androidx.compose.ui.unit.IntSize(iw.roundToInt().coerceAtLeast(1), ih.roundToInt().coerceAtLeast(1)))
            val quad = androidx.compose.ui.graphics.Path().apply {
                (0..3).forEach { i -> point(corners, i).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                close()
            }
            clipPath(quad, androidx.compose.ui.graphics.ClipOp.Difference) {
                drawRect(Color.Black.copy(alpha = 0.5f), origin, Size(iw, ih))
            }
            drawPath(quad, c.accent, style = Stroke(2.dp.toPx()))
            if (onMove != null) {
                for (i in 0..3) {
                    drawCircle(Color.White, 11.dp.toPx(), point(corners, i))
                    drawCircle(c.accent, 11.dp.toPx(), point(corners, i), style = Stroke(2.5.dp.toPx()))
                }
            }
        }
    }
}
