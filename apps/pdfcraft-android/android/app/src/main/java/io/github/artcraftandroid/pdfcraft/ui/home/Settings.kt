package io.github.artcraftandroid.pdfcraft.ui.home

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.PdfField
import io.github.artcraftandroid.pdfcraft.ui.PdfSwitch
import io.github.artcraftandroid.pdfcraft.ui.SheetDivider
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import io.github.artcraftandroid.pdfcraft.ui.safeTopPadding
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanCameraSetting
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanMergeSettingRow
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanModelsSection
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanUprightSetting

/**
 * Settings' pages (#447). Home lists them in groups; each holds the settings of one thing. The
 * owner's path to the log, Settings > About > Diagnostics, still works: About links to Diagnostics,
 * which is also on Settings' own page.
 */
enum class SettingsPage(@StringRes val title: Int) {
    Root(R.string.home_settings),
    Reading(R.string.settings_reading),
    Pen(R.string.home_section_pen),
    Forms(R.string.home_section_forms),
    Keyboard(R.string.home_section_keyboard),
    Scan(R.string.settings_scanner),
    Library(R.string.settings_library),
    Backup(R.string.backup_section),
    Updates(R.string.updater_title),
    Diagnostics(R.string.home_section_diagnostics),
    ScanTesting(R.string.scan_lab_section),
    Help(R.string.home_help_support),
    About(R.string.home_about_app),
}

/** The pages above [page]: Scanner testing sits in Diagnostics, everything else in Settings. */
internal fun settingsStack(page: SettingsPage): List<SettingsPage> = when (page) {
    SettingsPage.Root -> listOf(SettingsPage.Root)
    SettingsPage.ScanTesting -> listOf(SettingsPage.Root, SettingsPage.Diagnostics, SettingsPage.ScanTesting)
    else -> listOf(SettingsPage.Root, page)
}

/** PdfCraft for Android's own repository: its help, its issues and its source. */
private const val APP_REPO = "https://github.com/MohamedRef/pdfcraft-android"

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val c = pdfColors
    // kept across rotation; Home's update card and the like open straight on a page
    var stack by rememberSaveable { mutableStateOf(settingsStack(vm.home.settingsStartPage).map { it.name }) }
    val page = SettingsPage.valueOf(stack.last())
    val open = { p: SettingsPage -> stack = stack + p.name }
    val back = { if (stack.size <= 1) vm.home.settingsOpen = false else stack = stack.dropLast(1) }
    DisposableEffect(Unit) { onDispose { vm.home.openSettings(SettingsPage.Root, show = false) } }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().background(c.surface).safeTopPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                BarIcon(LucideIcons.ArrowLeft, stringResource(R.string.common_back), back)
            }
            Text(
                stringResource(page.title), color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 16.dp).semantics { heading() },
            )
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (page) {
                SettingsPage.Root -> RootPage(vm, open)
                SettingsPage.Reading -> ReadingPage(vm)
                SettingsPage.Pen -> PenPage(vm)
                SettingsPage.Forms -> FormsPage(vm)
                SettingsPage.Keyboard -> KeyboardPage(vm)
                SettingsPage.Scan -> ScanPage(vm)
                SettingsPage.Library -> LibraryPage(vm)
                SettingsPage.Backup -> BackupPage(vm)
                SettingsPage.Updates -> UpdatesPage()
                SettingsPage.Diagnostics -> DiagnosticsPage(vm, open)
                SettingsPage.ScanTesting -> ScanLabSettings(vm)
                SettingsPage.Help -> HelpPage(vm)
                SettingsPage.About -> AboutPage(vm, open)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---- Building blocks: one look for every page ------------------------------------------------

/** A group's title, announced as a heading. */
@Composable
internal fun SettingsHeader(title: String) {
    Text(
        title, color = pdfColors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** A short explanation under a group; rows carry their own in [summary]. */
@Composable
internal fun SettingsNote(text: String, color: Color = pdfColors.textSecondary) {
    Text(text, color = color, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp))
}

@Composable
private fun RowScope.Label(title: String, summary: String?, enabled: Boolean, titleColor: Color = pdfColors.text) {
    val c = pdfColors
    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
        Text(title, color = if (enabled) titleColor else titleColor.copy(alpha = 0.38f), fontSize = 16.sp)
        if (!summary.isNullOrBlank()) {
            Text(summary, color = if (enabled) c.textSecondary else c.textSecondary.copy(alpha = 0.38f), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun RowIcon(icon: ImageVector?, enabled: Boolean, tint: Color = pdfColors.icon) {
    if (icon == null) return
    Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.38f), modifier = Modifier.size(24.dp))
    Spacer(Modifier.width(16.dp))
}

/** A row that opens a page or runs an action; its summary is read with it. */
@Composable
internal fun SettingsRow(
    title: String,
    onClick: () -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    chevron: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = pdfColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon, enabled, if (destructive) c.red else c.icon)
        Label(title, summary, enabled, if (destructive) c.red else c.text)
        if (trailing != null) trailing()
        if (chevron) Icon(LucideIcons.ChevronRight, null, tint = c.icon, modifier = Modifier.padding(start = 8.dp).size(20.dp))
    }
}

/** A switch: the whole row is one control, announced with its state (#448). */
@Composable
internal fun SettingsSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit, summary: String? = null, icon: ImageVector? = null, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon, enabled)
        Label(title, summary, enabled)
        Spacer(Modifier.width(12.dp))
        PdfSwitch(checked, null)
    }
}

/** A few values as pills; each is a radio button that says whether it is chosen (#448). */
@Composable
internal fun <T> SettingsPills(values: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    val c = pdfColors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((value, name) in values) {
            val on = value == selected
            Text(
                name, color = if (on) c.onAccent else c.text, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) c.accent else c.surfaceRaised)
                    .selectable(selected = on, role = Role.RadioButton) { onPick(value) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Asks before an action that removes something (#448). */
@Composable
private fun rememberConfirm(title: String, confirm: String, body: String? = null, action: () -> Unit): () -> Unit {
    var asking by remember { mutableStateOf(false) }
    if (asking) PdfDialog(
        onDismiss = { asking = false }, title = title, confirm = confirm, destructive = true,
        onConfirm = { asking = false; action() },
        content = body?.let { { Text(it, color = pdfColors.text, fontSize = 14.sp) } },
    )
    return { asking = true }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) = Column(Modifier.fillMaxWidth(), content = content)

// ---- Settings' own page ------------------------------------------------------------------------

@Composable
private fun RootPage(vm: AppViewModel, open: (SettingsPage) -> Unit) {
    val c = pdfColors
    val context = LocalContext.current
    val version = "${vm.home.appVersion} (${installedVersionCode(context)})"
    // the app and its version; an update waiting shows here too
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceRaised)
            .clickable(role = Role.Button) { open(SettingsPage.About) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.ic_launcher_tile), null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name), color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(version, color = c.textSecondary, fontSize = 14.sp)
        }
        Icon(LucideIcons.ChevronRight, null, tint = c.icon, modifier = Modifier.size(20.dp))
    }
    UpdaterState.candidate?.let { update ->
        SettingsRow(
            stringResource(R.string.updater_available), { open(SettingsPage.Updates) },
            summary = "${update.version} (${update.apkVersionCode})", icon = LucideIcons.Sparkles, chevron = true,
            trailing = { Box(Modifier.size(9.dp).background(c.red, CircleShape)) },
        )
    }

    SettingsHeader(stringResource(R.string.settings_group_documents))
    SettingsRow(stringResource(R.string.settings_reading), { open(SettingsPage.Reading) }, stringResource(R.string.settings_reading_summary), LucideIcons.BookOpen, chevron = true)
    SettingsRow(stringResource(R.string.home_section_pen), { open(SettingsPage.Pen) }, stringResource(R.string.settings_pen_summary), LucideIcons.PenLine, chevron = true)
    SettingsRow(stringResource(R.string.home_section_forms), { open(SettingsPage.Forms) }, stringResource(R.string.settings_forms_summary), LucideIcons.TextCursorInput, chevron = true)
    SettingsRow(stringResource(R.string.home_section_keyboard), { open(SettingsPage.Keyboard) }, stringResource(R.string.home_keyboard_shortcuts), LucideIcons.SquareTerminal, chevron = true)

    SettingsHeader(stringResource(R.string.settings_group_tools))
    SettingsRow(stringResource(R.string.settings_scanner), { open(SettingsPage.Scan) }, stringResource(R.string.settings_scanner_summary), LucideIcons.Scan, chevron = true)
    SettingsRow(stringResource(R.string.settings_library), { open(SettingsPage.Library) }, stringResource(R.string.settings_library_summary), LucideIcons.FileSearch, chevron = true)

    SettingsHeader(stringResource(R.string.settings_group_app))
    val backupName = remember(vm.backups.backupTree) { vm.backups.backupFolderName() }
    val backupSummary = when {
        vm.backups.backupTree == null -> stringResource(R.string.settings_off)
        vm.backups.backupFailed -> stringResource(R.string.backup_status_failed)
        else -> stringResource(R.string.backup_folder_is, backupName ?: "")
    }
    SettingsRow(stringResource(R.string.backup_section), { open(SettingsPage.Backup) }, backupSummary, LucideIcons.FileArchive, chevron = true)
    SettingsRow(stringResource(R.string.updater_title), { open(SettingsPage.Updates) }, stringResource(R.string.settings_updates_summary), LucideIcons.RotateCw, chevron = true)

    SettingsHeader(stringResource(R.string.settings_group_support))
    SettingsRow(
        stringResource(R.string.home_section_diagnostics), { open(SettingsPage.Diagnostics) },
        stringResource(if (vm.settings.recordDiagnostics) R.string.settings_diagnostics_recording else R.string.settings_off), LucideIcons.Gauge, chevron = true,
    )
    SettingsRow(stringResource(R.string.home_help_support), { open(SettingsPage.Help) }, stringResource(R.string.settings_help_summary), LucideIcons.CircleHelp, chevron = true)
    SettingsRow(stringResource(R.string.home_about_app), { open(SettingsPage.About) }, version, LucideIcons.Info, chevron = true)
}

// ---- Documents --------------------------------------------------------------------------------

@Composable
private fun ReadingPage(vm: AppViewModel) = Group {
    val s = vm.settings
    SettingsSwitch(stringResource(R.string.home_keep_screen_on), s.keepScreenOn, { s.updateKeepScreenOn(it) }, stringResource(R.string.home_keep_screen_on_body))
    SettingsSwitch(stringResource(R.string.home_status_bar_in_documents), s.statusBarInDocuments, { s.updateStatusBarInDocuments(it) }, stringResource(R.string.home_status_bar_in_documents_body))
    SettingsSwitch(stringResource(R.string.compact_previews), s.compactPreviews, { s.updateCompactPreviews(it) }, stringResource(R.string.compact_previews_body))
}

@Composable
private fun PenPage(vm: AppViewModel) = Group {
    // the same settings as Draw's Pens sheet: one state, so both places stay in step
    val prefs = vm.settings.toolPrefs
    var authorDialog by remember { mutableStateOf(false) }
    SettingsSwitch(stringResource(R.string.toolbar_stylus_only), prefs.stylusOnly, { prefs.updateStylusOnly(it) }, stringResource(R.string.ink_pen_only_explanation))
    SettingsSwitch(stringResource(R.string.toolbar_snap_to_shape), prefs.snapToShape, { prefs.updateSnapToShape(it) })
    SettingsRow(stringResource(R.string.home_author_title), { authorDialog = true }, summary = prefs.author.ifBlank { stringResource(R.string.home_author_hint) })
    if (authorDialog) {
        var name by remember { mutableStateOf(prefs.author) }
        PdfDialog(
            onDismiss = { authorDialog = false }, title = stringResource(R.string.home_author_title),
            confirm = stringResource(R.string.common_ok), onConfirm = { prefs.author = name.trim(); authorDialog = false; vm.home.prefsChanged++ },
        ) { PdfField(name, { name = it }, stringResource(R.string.common_your_name)) }
    }
}

@Composable
private fun FormsPage(vm: AppViewModel) = Group {
    val e = vm.editor
    SettingsSwitch(stringResource(R.string.home_offer_suggestions), e.formSuggestions, { e.updateFormSuggestions(it) })
    val title = stringResource(R.string.home_clear_suggestions)
    val clear = rememberConfirm(title, stringResource(R.string.common_clear)) { e.clearFormSuggestions() }
    SettingsRow(title, clear)
}

@Composable
private fun KeyboardPage(vm: AppViewModel) = Group {
    val s = vm.settings
    SettingsSwitch(stringResource(R.string.home_keyboard_shortcuts), s.keyboardShortcuts, { s.updateKeyboardShortcuts(it) }, stringResource(R.string.home_keyboard_shortcuts_body))
}

// ---- Tools ------------------------------------------------------------------------------------

@Composable
private fun ScanPage(vm: AppViewModel) = Group {
    // which camera Scan uses (#294): the phone's camera app stays one tap away
    ScanCameraSetting()
    ScanMergeSettingRow()
    var autoUpright by remember { mutableStateOf(ScanUprightSetting.get(vm.settings.appPrefs)) }
    SettingsSwitch(stringResource(R.string.settings_scan_upright), autoUpright, { autoUpright = it; ScanUprightSetting.set(vm.settings.appPrefs, it) }, stringResource(R.string.settings_scan_upright_body))
    SheetDivider(vertical = 8.dp)
    // the scanner's models: what is installed and whether it is accepted (#289)
    ScanModelsSection(vm.engine, vm.settings.recordDiagnostics)
}

@Composable
private fun LibraryPage(vm: AppViewModel) = Group {
    val st = vm.study
    SettingsHeader(stringResource(R.string.library_title))
    SettingsSwitch(stringResource(R.string.library_index_switch), st.libraryIndexing, { st.updateLibraryIndexing(it) }, stringResource(R.string.library_index_body))
    val wipeTitle = stringResource(R.string.library_wipe_index)
    SettingsRow(wipeTitle, rememberConfirm(wipeTitle, stringResource(R.string.common_delete)) { st.wipeLibraryIndex() })
    SettingsHeader(stringResource(R.string.study_section))
    val clearTitle = stringResource(R.string.study_clear_stats)
    SettingsRow(clearTitle, rememberConfirm(clearTitle, stringResource(R.string.common_clear)) { st.clearStudyStats() }, summary = stringResource(R.string.study_stats_body))
}

// ---- App --------------------------------------------------------------------------------------

@Composable
private fun BackupPage(vm: AppViewModel) = Group {
    val c = pdfColors
    val b = vm.backups
    val backupName = remember(b.backupTree) { b.backupFolderName() }
    SettingsRow(
        if (backupName == null) stringResource(R.string.backup_choose_folder) else stringResource(R.string.backup_folder_is, backupName),
        { b.chooseBackupFolder() },
    )
    if (b.backupTree != null) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.backup_keep_label), color = c.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            SettingsPills(listOf(5, 10, 20, 50).map { it to it.toString() }, b.backupKeep) { b.updateBackupKeep(it) }
        }
        val status = when {
            b.backupFailed -> stringResource(R.string.backup_status_failed)
            b.backupLast > 0 -> stringResource(R.string.backup_status_last, android.text.format.DateUtils.getRelativeTimeSpanString(b.backupLast).toString())
            else -> stringResource(R.string.backup_status_none)
        }
        SettingsNote(status, if (b.backupFailed) c.red else c.textSecondary)
        if (b.backupFailed) SettingsRow(stringResource(R.string.backup_retry), { b.flushBackups() })
        SettingsRow(stringResource(R.string.backup_turn_off), { b.turnOffBackups() })
    }
    SettingsNote(stringResource(R.string.backup_body))
}

/** Checking, downloading and the automatic check, together (#447). */
@Composable
private fun UpdatesPage() = Group {
    val context = LocalContext.current
    var autoCheck by remember { mutableStateOf(UpdaterState.autoCheckOn(context)) }
    SettingsSwitch(stringResource(R.string.updater_auto_check), autoCheck, { autoCheck = it; UpdaterState.setAutoCheck(context, it) }, stringResource(R.string.updater_auto_check_body))
    SheetDivider(vertical = 8.dp)
    UpdatePanel(showTitle = false)
}

// ---- Support ----------------------------------------------------------------------------------

/** The diagnostics log, and the scanner's testing options that need it (#288). */
@Composable
private fun DiagnosticsPage(vm: AppViewModel, open: (SettingsPage) -> Unit) = Group {
    val s = vm.settings
    SettingsSwitch(stringResource(R.string.home_record_diagnostics), s.recordDiagnostics, { s.updateRecordDiagnostics(it) }, stringResource(R.string.home_record_diagnostics_body))
    if (s.recordDiagnostics) {
        SettingsRow(stringResource(R.string.home_share_diagnostics), { s.shareDiagnostics() }, icon = LucideIcons.Share2)
        val clearTitle = stringResource(R.string.home_clear_diagnostics)
        SettingsRow(clearTitle, rememberConfirm(clearTitle, stringResource(R.string.common_clear)) { s.clearDiagnostics() }, icon = LucideIcons.Trash2)
    }
    SheetDivider(vertical = 8.dp)
    // always listed, so it can be found; usable while the log is recorded
    SettingsRow(
        stringResource(R.string.scan_lab_section), { open(SettingsPage.ScanTesting) },
        summary = stringResource(if (s.recordDiagnostics) R.string.settings_scan_testing_summary else R.string.settings_scan_testing_needs_log),
        icon = LucideIcons.Wrench, chevron = true, enabled = s.recordDiagnostics,
    )
}

@Composable
private fun HelpPage(vm: AppViewModel) = Group {
    val uri = LocalUriHandler.current
    var leaving by remember { mutableStateOf<String?>(null) }
    SettingsRow(stringResource(R.string.tips_title), { vm.tipsOpen = true }, icon = LucideIcons.Sparkles)
    SettingsRow(stringResource(R.string.home_help), { leaving = "$APP_REPO#readme" }, icon = LucideIcons.CircleHelp, trailing = { LinkOut() })
    SettingsRow(stringResource(R.string.home_report_problem), { leaving = "$APP_REPO/issues" }, icon = LucideIcons.MessageSquare, trailing = { LinkOut() })
    leaving?.let { url ->
        PdfDialog(
            onDismiss = { leaving = null }, title = stringResource(R.string.home_leaving_title), confirm = stringResource(R.string.home_continue), onConfirm = { leaving = null; uri.openUri(url) },
        ) { Text(stringResource(R.string.home_leaving_body), color = pdfColors.text, fontSize = 14.sp) }
    }
}

@Composable
private fun LinkOut() = Icon(LucideIcons.ExternalLink, null, tint = pdfColors.icon, modifier = Modifier.padding(start = 8.dp).size(18.dp))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AboutPage(vm: AppViewModel, open: (SettingsPage) -> Unit) = Group {
    val c = pdfColors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    val version = stringResource(R.string.home_version, "${vm.home.appVersion} (${installedVersionCode(context)})", io.github.artcraftandroid.pdfcraft.core.engineVersion())
    val copied = stringResource(R.string.home_version_copied)
    val copyLabel = stringResource(R.string.settings_copy_version)
    val copy = { clipboard.setText(AnnotatedString(version)); vm.tell(copied) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 16.dp)
            // a tap copies the version too, not only a long press (#447)
            .combinedClickable(onClickLabel = copyLabel, onLongClickLabel = copyLabel, onLongClick = copy, onClick = copy),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.ic_launcher_tile), null, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.app_name), color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(version, color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 480.dp))
        Text(copyLabel, color = c.accent, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
    SheetDivider()
    SettingsRow(stringResource(R.string.updater_title), { open(SettingsPage.Updates) }, icon = LucideIcons.RotateCw, chevron = true)
    // Settings > About > Diagnostics, the path the owner shares logs with
    SettingsRow(
        stringResource(R.string.home_section_diagnostics), { open(SettingsPage.Diagnostics) },
        stringResource(if (vm.settings.recordDiagnostics) R.string.settings_diagnostics_recording else R.string.settings_off), LucideIcons.Gauge, chevron = true,
    )
    SettingsHeader(stringResource(R.string.home_section_legal))
    SettingsRow(stringResource(R.string.home_licences), { vm.home.showLicences = true }, chevron = true)
    SettingsRow(stringResource(R.string.home_source_code), { uri.openUri(APP_REPO) }, trailing = { LinkOut() })
    SettingsRow(stringResource(R.string.home_engine), { uri.openUri("https://github.com/storytold/pdfcraft") }, trailing = { LinkOut() })
    SettingsNote(stringResource(R.string.home_legal_notice))
}
