package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.home.HomeRoot
import io.github.artcraftandroid.pdfcraft.ui.home.SettingsScreen
import io.github.artcraftandroid.pdfcraft.ui.viewer.CertSignScreen
import io.github.artcraftandroid.pdfcraft.ui.viewer.CreateIdScreen
import io.github.artcraftandroid.pdfcraft.ui.viewer.IdPasswordDialog
import io.github.artcraftandroid.pdfcraft.ui.viewer.SignFieldChoiceDialog
import io.github.artcraftandroid.pdfcraft.ui.viewer.SignaturePadScreen
import io.github.artcraftandroid.pdfcraft.ui.viewer.AdaptiveViewer

/** Home or the viewer, and everything that can come over them: tools, settings, dialogs, snackbars. */
@Composable
fun AppRoot(vm: AppViewModel, onBrowse: () -> Unit, onPickSignatureImage: () -> Unit) {
    val c = pdfColors
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        for (m in vm.messages) {
            val result = snackbar.showSnackbar(
                m.text, actionLabel = m.actionLabel, withDismissAction = m.actionLabel == null,
                duration = if (m.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) m.action?.invoke()
        }
    }
    val doc = vm.document
    // outside the viewer (which sets its own) the bar icons follow the app's theme, not the system's
    val view = androidx.compose.ui.platform.LocalView.current
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    androidx.compose.runtime.SideEffect {
        val window = activity?.window ?: return@SideEffect
        if (doc == null) {
            val insets = androidx.core.view.WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = !c.dark
            insets.isAppearanceLightNavigationBars = !c.dark
        }
    }
    // Back in the viewer closes the document (saving it) and returns to Home
    androidx.activity.compose.BackHandler(enabled = doc != null && vm.toolScreen == null) { vm.closeDocument() }
    // sideways, the camera cutout and the navigation bar sit at the sides: content stays between
    // them, on black, as the reference's does. With Extra Features' status bar in documents the
    // page goes under the camera too, as it does upright (#45): the viewer is laid out full width
    // and keeps its bars clear of the camera itself.
    val fullWidthViewer = doc != null && vm.statusBarInDocuments
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (fullWidthViewer && doc != null) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))) { AdaptiveViewer(vm, doc) }
        }
        Box(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .then(if (fullWidthViewer) Modifier else Modifier.background(c.surface)),
        ) {
            when {
                fullWidthViewer -> Unit
                doc != null -> AdaptiveViewer(vm, doc)
                else -> HomeRoot(vm, onBrowse)
            }
            // here rather than in the file's row: a sheet inside a scrolling list has no height to fill
            if (doc == null) vm.fileSheet?.let { (r, recent) -> io.github.artcraftandroid.pdfcraft.ui.home.FileSheet(vm, r, recent) { vm.fileSheet = null } }
            vm.toolScreen?.let { ToolScreenHost(vm, it) }
            vm.study.reviewing?.let { ReviewScreen(vm, it) }
            vm.study.cardDraft?.let { CardEditor(vm, it) }
            if (vm.study.newNotebookOpen) io.github.artcraftandroid.pdfcraft.ui.home.NewNotebookSheet(vm) { vm.study.newNotebookOpen = false }
            vm.signing.signaturePad?.let { SignaturePadScreen(vm, it, onPickSignatureImage) }
            vm.signing.certSign?.let { CertSignScreen(vm, it) }
            if (vm.signing.idCreate) CreateIdScreen(vm)
            if (vm.settingsOpen) SettingsScreen(vm)
            if (vm.showLicences) LicencesScreen(vm)

            if (vm.opening && doc == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.accent)
            if (doc?.info?.preparing == true) {
                Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 76.dp)
                    .background(c.surface).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.document_preparing), color = c.text, fontSize = 12.sp)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp), color = c.accent)
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp, start = 8.dp, end = 8.dp))

            vm.passwordRequest?.let { PasswordDialog(vm, it) }
            vm.signing.idImport?.let { IdPasswordDialog(vm, it) }
            vm.signing.signFieldChoice?.let { (d, w) -> SignFieldChoiceDialog(vm, d, w) }
            if (vm.tipsOpen) FirstRunTipsDialog(onClose = { vm.tipsOpen = false })
            if (vm.authorPrompt) {
                var name by remember { mutableStateOf("") }
                PdfDialog(
                    onDismiss = { vm.authorPrompt = false }, title = stringResource(R.string.home_author_title),
                    confirm = stringResource(R.string.common_save), onConfirm = { vm.toolPrefs.author = name.trim(); vm.authorPrompt = false; vm.prefsChanged++ },
                    dismiss = stringResource(R.string.root_skip),
                ) {
                    Text(stringResource(R.string.root_author_prompt_body), color = c.text, fontSize = 14.sp)
                    Spacer(Modifier.height(14.dp))
                    PdfField(name, { name = it }, stringResource(R.string.common_your_name))
                }
            }
            vm.readOnlyPrompt?.let { d ->
                // Save a copy is the main choice; Keep editing goes back; discarding is a separate,
                // confirmed step rather than a button next to the safe one (#191)
                var confirmDiscard by remember(d) { mutableStateOf(false) }
                if (confirmDiscard) {
                    PdfDialog(
                        onDismiss = { confirmDiscard = false }, title = stringResource(R.string.root_discard_title, d.name),
                        confirm = stringResource(R.string.root_discard), onConfirm = { vm.answerReadOnly(false) }, destructive = true,
                        dismiss = stringResource(R.string.common_cancel), onDismissButton = { confirmDiscard = false },
                    ) { Text(stringResource(R.string.root_discard_body), color = c.text, fontSize = 14.sp) }
                } else {
                    PdfDialog(
                        onDismiss = { vm.answerReadOnly(null) }, title = stringResource(R.string.root_read_only_title),
                        confirm = stringResource(R.string.common_save_copy), onConfirm = { vm.answerReadOnly(true) },
                        dismiss = stringResource(R.string.root_keep_editing), onDismissButton = { vm.answerReadOnly(null) },
                    ) {
                        Text(stringResource(R.string.root_read_only_body, d.name), color = c.text, fontSize = 14.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.root_discard), color = c.red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { confirmDiscard = true }.padding(vertical = 8.dp),
                        )
                    }
                }
            }
            vm.linkPrompt?.let { url ->
                val shown = runCatching { android.net.Uri.parse(url).let { u -> "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}" } }.getOrDefault(url)
                PdfDialog(onDismiss = { vm.answerLink(false) }, title = stringResource(R.string.root_open_link_title), confirm = stringResource(R.string.common_open), onConfirm = { vm.answerLink(true) }) {
                    Text(stringResource(R.string.root_open_link_body, shown), color = c.text, fontSize = 14.sp)
                }
            }
            // a form script's alert, as the form words it
            vm.scriptAlerts.firstOrNull()?.let { message ->
                PdfDialog(onDismiss = vm::dismissScriptAlert, title = null, confirm = stringResource(R.string.common_ok), onConfirm = vm::dismissScriptAlert, dismiss = null) {
                    Text(message, color = c.text, fontSize = 14.sp)
                }
            }
            vm.confirmDeleteAllBookmarks?.let { d ->
                PdfDialog(
                    onDismiss = { vm.confirmDeleteAllBookmarks = null }, title = stringResource(R.string.root_delete_bookmarks_title),
                    confirm = stringResource(R.string.root_delete_all), onConfirm = { vm.confirmDeleteAllBookmarks = null; vm.deleteAllBookmarks(d) }, destructive = true,
                ) { Text(stringResource(R.string.root_delete_bookmarks_body), color = c.text, fontSize = 14.sp) }
            }
            vm.readingModeBlocked?.let {
                PdfDialog(onDismiss = { vm.readingModeBlocked = null }, title = stringResource(R.string.root_reading_mode_title), confirm = stringResource(R.string.common_ok), onConfirm = { vm.readingModeBlocked = null }, dismiss = null) {
                    Text(stringResource(R.string.root_reading_mode_body), color = c.text, fontSize = 14.sp)
                }
            }
            io.github.artcraftandroid.pdfcraft.ui.viewer.ToolDialogs(vm)
            vm.busy?.let { message ->
                // a count and Cancel when the job has them (#200)
                val cancel = vm.busyCancel
                var cancelling by remember(cancel) { mutableStateOf(false) }
                PdfDialog(
                    onDismiss = {}, title = null, confirm = null,
                    dismiss = if (cancel != null) stringResource(if (cancelling) R.string.busy_cancelling else R.string.common_cancel) else null,
                    onDismissButton = { if (cancel != null && !cancelling) { cancelling = true; cancel() } },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = c.accent, strokeWidth = 3.dp)
                        Spacer(Modifier.width(20.dp))
                        Text(message, color = c.text)
                    }
                    vm.busyProgress?.let { (done, total) ->
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress = { if (total > 0) done.toFloat() / total else 0f },
                            modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.divider,
                        )
                    }
                    if (cancel != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.busy_cancel_keeps), color = c.textSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** "Enter password": the file's name, a masked field with an eye, OK and Cancel; wrong turns it red. */
@Composable
private fun PasswordDialog(vm: AppViewModel, req: PasswordRequest) {
    val c = pdfColors
    var pw by remember(req) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(req) { runCatching { focus.requestFocus() } }
    // the sentence with the file's name in italics where its %1$s is
    val body = stringResource(R.string.root_password_body)
    PdfDialog(onDismiss = { vm.answerPassword(null) }, title = stringResource(R.string.root_enter_password), confirm = stringResource(R.string.common_ok), onConfirm = { vm.answerPassword(pw) }) {
        Text(buildAnnotatedString {
            val at = body.indexOf("%1\$s")
            if (at < 0) {
                append(body)
            } else {
                append(body.substring(0, at))
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(req.file.name) }
                append(body.substring(at + "%1\$s".length))
            }
        }, color = c.text, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))
        PdfField(
            pw, { pw = it }, stringResource(R.string.common_password), error = req.wrong && pw.isEmpty(), password = true, focus = focus,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (pw.isNotEmpty()) vm.answerPassword(pw) }),
        )
    }
}

/** The open-source licences of what the app is built from. */
@Composable
private fun LicencesScreen(vm: AppViewModel) {
    val c = pdfColors
    val context = LocalContext.current
    androidx.activity.compose.BackHandler { vm.showLicences = false }
    // the full texts of the bundled notices, in the order of the entries below
    val texts = remember {
        listOf("LICENSE-lucide.txt", "OFL-Inter.txt", "OFL-DancingScript.txt", "LICENSE-foxit.txt", "LICENSE-adobe-cmaps.txt").map { name ->
            runCatching { context.assets.open("licences/$name").bufferedReader().readText() }.getOrDefault("")
        }
    }
    Column(Modifier.fillMaxSize().background(c.surface).safeTopPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) { BarIcon(LucideIcons.ArrowLeft, stringResource(R.string.common_back), { vm.showLicences = false }) }
            Text(stringResource(R.string.home_licences), color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            @Composable
            fun entry(name: String, licence: String) {
                Text(name, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
                Text(licence, color = c.textSecondary, fontSize = 13.sp)
            }
            entry(stringResource(R.string.root_licence_pdfcraft), stringResource(R.string.root_licence_pdfcraft_terms))
            entry("UniFFI", "MPL-2.0, Mozilla")
            entry("JNA", "Apache-2.0")
            entry("AndroidX, Jetpack Compose, Kotlin coroutines", "Apache-2.0")
            entry(stringResource(R.string.root_licence_lucide), "ISC, Lucide Contributors")
            entry(stringResource(R.string.root_licence_inter), "SIL Open Font License 1.1, The Inter Project Authors")
            entry(stringResource(R.string.root_licence_dancing_script), "SIL Open Font License 1.1, The Dancing Script Project Authors")
            entry(stringResource(R.string.root_licence_foxit), "BSD-3-Clause, Foxit Software Inc. and the PDFium Authors")
            entry(stringResource(R.string.root_licence_cmaps), "BSD-3-Clause, Adobe")
            entry(stringResource(R.string.root_licence_ocrs), stringResource(R.string.root_licence_ocrs_terms))
            entry(stringResource(R.string.root_licence_rust), stringResource(R.string.root_licence_rust_terms))
            for (licence in texts.filter { it.isNotBlank() }) {
                Spacer(Modifier.height(20.dp))
                Text(licence, color = c.textSecondary, fontSize = 11.sp)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
