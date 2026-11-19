package io.github.artcraftandroid.pdfcraft

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.AppRoot
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.FilePickKind
import io.github.artcraftandroid.pdfcraft.ui.PdfcraftTheme
import io.github.artcraftandroid.pdfcraft.ui.XFDF_MIME
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanCameraModel
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanCameraRouter
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    /** Browse: any PDF a document provider offers, opened with write access when it allows. */
    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::open) }

    private var pickKind = FilePickKind.Open
    private val pickMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.onFilesPicked(pickKind, uris) }
    private val pickOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> vm.onFilesPicked(pickKind, listOfNotNull(uri)) }
    // Separate picker/result from the regular file pick kind, so a stale PDF selection cannot
    // accidentally feed untrusted bookmark JSON into another tool or document.
    private val pickBookmarkJson = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> vm.tools.onBookmarkJsonPicked(uri) }
    private val pickBookmarkPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> vm.tools.onBookmarkPdfPicked(uri) }
    // Comments to import (XFDF, FDF or another PDF's): a picker of their own, like the bookmarks'.
    private val pickComments = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> vm.commenting.onCommentsPicked(uri) }

    private val saveCopy = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> vm.sharing.copyTo(uri) }
    // Scan's photos: PdfCraft's camera screen or the phone's camera app (#294)
    private val scanCameraModel: ScanCameraModel by viewModels()
    private val scanCamera = ScanCameraRouter(this, { vm }, { scanCameraModel })
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> vm.editor.addImage(uri) }
    private val pickSignature = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> vm.signing.importSignatureImage(uri) }
    private val pickPhotos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris -> vm.home.onPhotosPicked(uris) }
    private val pickOutputFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> vm.docTools.onOutputFolderPicked(uri) }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm.home::addLocalFolder) }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.commenting.onMicPermission(granted) }
    private val pickBackupFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.backups.onBackupFolderPicked(it) }
    private val pickCardFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { vm.study.onCardFilePicked(it) }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.study.updateFocusTimer(vm.study.focusTimer) }

    private val pickBatesPdfs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.onBatesFilesPicked(it) }

    // the editing tools' own pickers (FileRequests): each answer goes back to the tool that asked
    private val requestCreate = registerForActivityResult(CreateTypedDocument()) { uri -> vm.requests.onCreated(uri) }
    private val requestOpenOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> vm.requests.onOpened(listOfNotNull(uri)) }
    private val requestOpenMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.requests.onOpened(uris) }
    private val requestImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> vm.requests.onImage(uri) }
    private val requestFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> vm.requests.onFolder(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // transparent system bars with no contrast scrim: every screen paints the bands behind the
        // status bar and the navigation buttons itself, in its own colours (AppRoot)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false
        super.onCreate(savedInstanceState)
        // A process recreated from a saved task still needs its incoming PDF. Avoid reopening
        // when the retained ViewModel already owns the document or an open request.
        if (shouldRouteInitialPdfIntent(
                savedInstanceStatePresent = savedInstanceState != null,
                hasDocument = vm.document != null,
                opening = vm.opening,
                awaitingPassword = vm.passwordRequest != null,
            )
        ) openFromIntent(intent)
        collectRequests()
        publishShortcuts()
        setContent {
            PdfcraftTheme {
                androidx.compose.foundation.layout.Box {
                    AppRoot(
                        vm,
                        onBrowse = { launchSafely { pickPdf.launch(arrayOf("application/pdf")) } },
                        onPickSignatureImage = { launchSafely { pickSignature.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
                    )
                    // the in-app scan camera, over everything while a photo is wanted (#294)
                    scanCamera.Screen()
                }
            }
        }
    }

    /** The view model's requests that need the activity: other apps, pickers, the camera, printing. */
    private fun collectRequests() {
        lifecycleScope.launch { for (i in vm.intents) launchSafely { startActivity(i) } }
        // a document another app opened was closed: back to that app, not Home (#47)
        lifecycleScope.launch { for (u in vm.leave) finish() }
        lifecycleScope.launch { for (job in vm.prints) print(job.file, job.name, job.deleteWhenDone) }
        lifecycleScope.launch { for (name in vm.sharing.saveCopyRequests) launchSafely(onFail = vm.sharing::saveCopyLaunchFailed) { saveCopy.launch(name) } }
        lifecycleScope.launch {
            for (ignored in vm.tools.bookmarkJsonPicks) {
                launchSafely { pickBookmarkJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            }
        }
        lifecycleScope.launch {
            for (ignored in vm.tools.bookmarkPdfPicks) {
                launchSafely { pickBookmarkPdf.launch(arrayOf("application/pdf")) }
            }
        }
        lifecycleScope.launch {
            for (ignored in vm.commenting.commentImportPicks) {
                // providers rarely know XFDF's type: anything may be picked, the engine reads its content
                launchSafely { pickComments.launch(arrayOf(XFDF_MIME, FDF_MIME, "application/pdf", "application/xml", "text/xml", "application/octet-stream", "*/*")) }
            }
        }
        lifecycleScope.launch {
            for (ignored in vm.batesPicks) launchSafely {
                try { pickBatesPdfs.launch(arrayOf("application/pdf")) }
                catch (e: Exception) { vm.onBatesFilesPicked(emptyList()); throw e }
            }
        }
        lifecycleScope.launch {
            for (kind in vm.filePicks) {
                pickKind = kind
                launchSafely {
                    when (kind) {
                        FilePickKind.Combine -> pickMany.launch(arrayOf("application/pdf", "image/*", "text/plain") + OFFICE_TYPES)
                        FilePickKind.Create -> pickMany.launch(arrayOf("image/*", "text/plain", "application/pdf") + OFFICE_TYPES)
                        FilePickKind.Insert, FilePickKind.Open, FilePickKind.Replace -> pickOne.launch(arrayOf("application/pdf"))
                        FilePickKind.Attach -> pickOne.launch(arrayOf("*/*"))
                        FilePickKind.Library -> pickMany.launch(arrayOf("application/pdf"))
                        FilePickKind.MarkImage -> pickOne.launch(arrayOf("image/*", "application/pdf"))
                        // .p12 / .pfx: providers type them as PKCS #12, or not at all
                        FilePickKind.DigitalId -> pickOne.launch(arrayOf("application/x-pkcs12", "application/pkcs12", "application/octet-stream"))
                    }
                }
            }
        }
        // a failed request is logged and the next one still taken (#716): one exception used to end
        // the loop, and Scan from photos then did nothing until the app was restarted
        lifecycleScope.launch {
            for (f in vm.cameraShots) keepTaking("camera_route") { scanCamera.route(f) }
        }
        lifecycleScope.launch {
            for (u in vm.scanImports) keepTaking("photo_picker") { scanCamera.importPhotos() }
        }
        // pictures shared from another app start a scan (#617)
        lifecycleScope.launch {
            for (uris in vm.scanShared) keepTaking("shared_import") { scanCamera.importShared(uris) }
        }
        lifecycleScope.launch {
            for (r in vm.requests.creates) launchSafely {
                try { requestCreate.launch(r.name to r.mime) } catch (e: Exception) { vm.requests.onCreated(null); throw e }
            }
        }
        lifecycleScope.launch {
            for (r in vm.requests.opens) launchSafely {
                try { if (r.many) requestOpenMany.launch(r.mimes) else requestOpenOne.launch(r.mimes) } catch (e: Exception) { vm.requests.onOpened(emptyList()); throw e }
            }
        }
        lifecycleScope.launch {
            for (u in vm.requests.images) launchSafely {
                try { requestImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } catch (e: Exception) { vm.requests.onImage(null); throw e }
            }
        }
        lifecycleScope.launch {
            for (u in vm.requests.folders) launchSafely {
                try { requestFolder.launch(null) } catch (e: Exception) { vm.requests.onFolder(null); throw e }
            }
        }
        lifecycleScope.launch { for (d in vm.editor.imagePicks) launchSafely { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
        lifecycleScope.launch { for (u in vm.home.photoPicks) launchSafely { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
        // the folder picker opens at Documents, where PDFs usually are (Download itself can't be chosen)
        val documents = android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")
        lifecycleScope.launch {
            for (u in vm.docTools.outputFolderRequests) {
                try { pickOutputFolder.launch(null) } catch (e: ActivityNotFoundException) { vm.docTools.onOutputFolderPicked(null); vm.tell(getString(R.string.main_error_no_app)) }
            }
        }
        lifecycleScope.launch { for (u in vm.home.accessRequests) launchSafely { pickFolder.launch(documents) } }
        lifecycleScope.launch {
            for (u in vm.commenting.micRequests) {
                // refused before: explain first (the reference's order); otherwise Android's own prompt
                if (shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)) vm.commenting.micRationale = true
                else launchSafely { micPermission.launch(android.Manifest.permission.RECORD_AUDIO) }
            }
        }
        lifecycleScope.launch { for (u in vm.backups.backupFolderRequests) launchSafely { pickBackupFolder.launch(null) } }
        lifecycleScope.launch { for (u in vm.study.cardImportRequests) launchSafely { pickCardFile.launch(arrayOf("text/plain", "text/csv", "text/comma-separated-values", "text/tab-separated-values")) } }
        lifecycleScope.launch {
            for (u in vm.study.notificationRequests) if (android.os.Build.VERSION.SDK_INT >= 33) launchSafely { notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
        // keep the screen on while reading, if asked, until half an hour without a touch
        lifecycleScope.launch {
            while (true) {
                val on = vm.settings.keepScreenOn && vm.document != null && System.currentTimeMillis() - lastTouch < 30L * 60 * 1000
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                delay(5_000)
            }
        }
    }

    private var lastTouch = System.currentTimeMillis()

    // a keyboard (or desktop mode): keys the viewer takes when no text field has them
    // Ctrl+Tab switches tabs (#138) before Compose takes Tab to move focus
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_TAB && event.isCtrlPressed && vm.document != null && vm.tabs.tabs.size > 1) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) vm.sharing.viewerKey(event.keyCode, ctrl = true, shift = event.isShiftPressed)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        vm.sharing.viewerKey(keyCode, event.isCtrlPressed, event.isShiftPressed) || super.onKeyDown(keyCode, event)

    // the viewer's shortcuts in the system's Meta+/ list (#153)
    override fun onProvideKeyboardShortcuts(data: MutableList<android.view.KeyboardShortcutGroup>, menu: android.view.Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        if (vm.settings.keyboardShortcuts) data.add(io.github.artcraftandroid.pdfcraft.ui.viewer.shortcutGroup(this))
    }

    /** Log raw pen samples (real historical pressure, pen speed) before Compose handles the event. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        io.github.artcraftandroid.pdfcraft.ui.viewer.StylusLog.record(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastTouch = System.currentTimeMillis()
        vm.study.studyActivity()
    }

    /** One request from the scan's channels: a failure is logged as a scan failure at [stage], and the loop goes on (#716). */
    private inline fun keepTaking(stage: String, block: () -> Unit) {
        try {
            block()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Diag.failure(DiagArea.Scan, stage, e)
        }
    }

    private inline fun launchSafely(onFail: () -> Unit = {}, block: () -> Unit) {
        try {
            block()
        } catch (e: ActivityNotFoundException) {
            onFail()
            vm.tell(getString(R.string.main_error_no_app))
        } catch (e: SecurityException) {
            onFail()
            vm.tell(getString(R.string.main_error_not_allowed))
        }
    }

    /** When the app last started one of its own screens (a picker, the share sheet, the camera). */
    private var ownLaunchAt = 0L

    // every picker, chooser and permission-free launch passes through here (#97: tell a pause for
    // the app's own picker from leaving the app)
    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        ownLaunchAt = android.os.SystemClock.elapsedRealtime()
        @Suppress("DEPRECATION")
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onPause() {
        super.onPause()
        val ownScreen = pauseForOwnScreen(android.os.SystemClock.elapsedRealtime(), ownLaunchAt, isFinishing)
        io.github.artcraftandroid.pdfcraft.engine.Diag.event(
            io.github.artcraftandroid.pdfcraft.engine.DiagArea.App, "pause",
            "own_screen" to ownScreen,
            "changing_config" to isChangingConfigurations, "finishing" to isFinishing,
        )
        vm.onPause(ownScreen)
        io.github.artcraftandroid.pdfcraft.ui.home.UpdaterState.onForeground(this, false)
    }

    override fun onResume() {
        super.onResume()
        io.github.artcraftandroid.pdfcraft.engine.Diag.event(io.github.artcraftandroid.pdfcraft.engine.DiagArea.App, "resume")
        vm.home.onResumed()
        vm.recheckWriteAccess()
        io.github.artcraftandroid.pdfcraft.ui.home.UpdaterState.onForeground(this, true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask reuses this Activity. Android's getIntent() otherwise exposes a stale
        // document after a newer Open with, including across subsequent recreation.
        setIntent(intent)
        openFromIntent(intent)
    }

    /** Extract external PDF URIs defensively: other apps can supply broken extras or ClipData. */
    private fun externalPdfUri(intent: Intent?): Uri? {
        if (intent == null) return null
        val action = intent.action ?: return null
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
        val candidates = ArrayList<Uri>()
        // A sender may place the only working URI into ClipData rather than EXTRA_STREAM.
        // The permission grant covers the whole receiving Intent, including these clip items.
        // Each parcelable is isolated: a malformed single-item field must not prevent a
        // valid multiple-item list (or the permission-bearing ClipData) from being checked.
        when (action) {
            Intent.ACTION_VIEW -> runCatching { intent.data }.getOrNull()?.let(candidates::add)
            Intent.ACTION_SEND -> runCatching {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            }.getOrNull()?.let(candidates::add)
            Intent.ACTION_SEND_MULTIPLE -> {
                runCatching {
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                }.getOrNull()?.let(candidates::addAll)
                runCatching {
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                }.getOrNull()?.let(candidates::add)
            }
        }
        runCatching {
            val clip = intent.clipData
            if (clip != null) {
                for (i in 0 until minOf(clip.itemCount, 32)) {
                    clip.getItemAt(i).uri?.let(candidates::add)
                }
            }
        }
        val privateRoots = listOfNotNull(
            applicationInfo.dataDir, filesDir.parent, cacheDir.parent, noBackupFilesDir.parent,
            getExternalFilesDir(null)?.parentFile?.path, externalCacheDir?.parentFile?.path,
        ).flatMap { listOf(it, runCatching { java.io.File(it).canonicalPath }.getOrNull()) }.filterNotNull().distinct()
        val chosen = firstExternalPdfCandidate(
            action,
            candidates.map { u ->
                val canonical = if (u.scheme.equals("file", ignoreCase = true)) u.path?.let { p -> runCatching { java.io.File(p).canonicalPath }.getOrNull() } else null
                IncomingPdfUri(u.scheme, u.authority, u.path, canonical)
            },
            privateRoots,
        ) ?: return null
        return candidates[chosen]
    }

    /**
     * The launcher's shortcuts (#617): New scan, Scan from photos, Open file. Dynamic, so they
     * follow the build's own package name; refreshed at each start, in the app's language.
     */
    private fun publishShortcuts() {
        runCatching {
            fun shortcut(id: String, action: String, short: Int, long: Int, icon: Int) =
                androidx.core.content.pm.ShortcutInfoCompat.Builder(this, id)
                    .setShortLabel(getString(short)).setLongLabel(getString(long))
                    .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(this, icon))
                    .setIntent(Intent(action).setClass(this, MainActivity::class.java))
                    .build()
            androidx.core.content.pm.ShortcutManagerCompat.setDynamicShortcuts(this, listOf(
                shortcut("new_scan", io.github.artcraftandroid.pdfcraft.ui.scan.ScanShortcuts.ACTION_NEW_SCAN, R.string.home_new_scan, R.string.home_new_scan, R.drawable.ic_shortcut_scan),
                shortcut("scan_photos", io.github.artcraftandroid.pdfcraft.ui.scan.ScanShortcuts.ACTION_SCAN_PHOTOS, R.string.shortcut_scan_photos, R.string.scan_from_photos, R.drawable.ic_shortcut_photos),
                shortcut("open_file", io.github.artcraftandroid.pdfcraft.ui.scan.ScanShortcuts.ACTION_OPEN_FILE, R.string.home_open_file, R.string.home_open_file, R.drawable.ic_shortcut_open),
            ))
        }.onFailure { Diag.failure(DiagArea.Scan, "shortcuts_publish", it) }
    }

    /**
     * A launcher shortcut, or pictures shared to the app (#617). True when [intent] was one of
     * them, so it is not read as a PDF.
     */
    private fun startFromIntent(intent: Intent?): Boolean {
        val action = runCatching { intent?.action }.getOrNull() ?: return false
        when (action) {
            io.github.artcraftandroid.pdfcraft.ui.scan.ScanShortcuts.ACTION_NEW_SCAN -> vm.startScan(io.github.artcraftandroid.pdfcraft.ui.scan.ScanStart.Shortcut)
            io.github.artcraftandroid.pdfcraft.ui.scan.ScanShortcuts.ACTION_SCAN_PHOTOS -> vm.startScan(io.github.artcraftandroid.pdfcraft.ui.scan.ScanStart.Shortcut, photos = true)
            io.github.artcraftandroid.pdfcraft.ui.scan.ScanShortcuts.ACTION_OPEN_FILE -> {
                Diag.event(DiagArea.Home, "shortcut_open_file")
                launchSafely { pickPdf.launch(arrayOf("application/pdf")) }
            }
            else -> {
                val streams = mutableListOf<Uri?>()
                runCatching { IntentCompat.getParcelableArrayListExtra(intent!!, Intent.EXTRA_STREAM, Uri::class.java) }.getOrNull()?.let(streams::addAll)
                runCatching { IntentCompat.getParcelableExtra(intent!!, Intent.EXTRA_STREAM, Uri::class.java) }.getOrNull()?.let(streams::add)
                val clip = runCatching { intent!!.clipData?.let { c -> (0 until minOf(c.itemCount, 200)).map { c.getItemAt(it).uri } } }.getOrNull().orEmpty()
                val images = io.github.artcraftandroid.pdfcraft.ui.scan.scanSharedImages(action, runCatching { intent!!.type }.getOrNull(), streams, clip) ?: return false
                if (images.isEmpty()) vm.tell(getString(R.string.scan_shared_none)) else vm.startScanShared(images)
            }
        }
        return true
    }

    /**
     * The kind of app that sent [intent] (#656): the referrer Android reports, else the one the
     * intent names. Only its kind is kept, never the package name.
     */
    private fun senderOf(intent: Intent?): String {
        val pkg = runCatching { referrer?.takeIf { it.scheme == "android-app" }?.host }.getOrNull()
            ?: runCatching { IntentCompat.getParcelableExtra(intent!!, Intent.EXTRA_REFERRER, Uri::class.java)?.takeIf { it.scheme == "android-app" }?.host }.getOrNull()
            ?: runCatching { intent?.getStringExtra(Intent.EXTRA_REFERRER_NAME)?.let(Uri::parse)?.takeIf { it.scheme == "android-app" }?.host }.getOrNull()
        return io.github.artcraftandroid.pdfcraft.engine.senderKind(pkg, packageName)
    }

    /** An external document must not terminate the whole app on a malformed parcelable. */
    private fun openFromIntent(intent: Intent?) {
        if (runCatching { startFromIntent(intent) }.getOrDefault(false)) return
        val uri = runCatching { externalPdfUri(intent) }.getOrNull() ?: return
        // shared comments (#158) go into the document open now, not open as one
        if (isCommentsType(runCatching { intent?.type }.getOrNull())) vm.commenting.receiveComments(uri)
        else vm.open(uri, fromOtherApp = true, sender = senderOf(intent))
    }

    /**
     * Hand [file] to Android's print framework. [deleteWhenDone]: [file] is a temporary copy (an
     * unlocked one, without its password) that goes when the print job finishes or fails (#105).
     */
    private fun print(file: File, name: String, deleteWhenDone: Boolean = false) {
        val pm = getSystemService(PrintManager::class.java) ?: run { if (deleteWhenDone) file.delete(); return }
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) {
                if (cancel?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                callback.onLayoutFinished(PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), old != new)
            }

            override fun onWrite(pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancel: CancellationSignal?, callback: WriteResultCallback) {
                val start = System.nanoTime()
                try {
                    file.inputStream().use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    Diag.event(DiagArea.Pages, "print_write", "bytes" to file.length(), "ms" to (System.nanoTime() - start) / 1_000_000, "temporary" to deleteWhenDone)
                } catch (e: Exception) {
                    Diag.failure(DiagArea.Pages, "print_write", e)
                    callback.onWriteFailed(e.message)
                }
            }

            override fun onFinish() {
                Diag.event(DiagArea.Pages, "print_finished", "temporary" to deleteWhenDone)
                if (deleteWhenDone) file.delete()
            }
        }
        runCatching { pm.print(name, adapter, null) }.onFailure {
            Diag.failure(DiagArea.Pages, "print_start", it)
            if (deleteWhenDone) file.delete()
            vm.tell(getString(R.string.main_error_print_not_allowed))
        }
    }
}

/**
 * Office documents Create PDF and Combine accept: Word, Excel and PowerPoint (the engine converts
 * the current formats; the older binary ones are offered so it can say to resave them), and RTF.
 */
private val OFFICE_TYPES = arrayOf(
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
    "application/rtf", "text/rtf",
)

/** Create a document of a type chosen per request: input is (file name, MIME type). */
private class CreateTypedDocument : androidx.activity.result.contract.ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: android.content.Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent.takeIf { resultCode == android.app.Activity.RESULT_OK }?.data
}

/**
 * Whether a pause is for one of the app's own screens, started at [ownLaunchAt] (0 when none):
 * a picker or the share sheet in front pauses the app within moments of the launch (#97).
 */
internal fun pauseForOwnScreen(now: Long, ownLaunchAt: Long, finishing: Boolean): Boolean =
    !finishing && ownLaunchAt > 0 && now - ownLaunchAt in 0 until 2_000
