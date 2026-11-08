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
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import io.github.artcraftandroid.pdfcraft.ui.AppRoot
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.FilePickKind
import io.github.artcraftandroid.pdfcraft.ui.PdfcraftTheme
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
    private val pickBookmarkJson = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> vm.onBookmarkJsonPicked(uri) }
    private val pickBookmarkPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> vm.onBookmarkPdfPicked(uri) }

    private val saveCopy = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> vm.copyTo(uri) }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> vm.onScanPhoto(ok) }
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> vm.addImage(uri) }
    private val pickSignature = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> vm.importSignatureImage(uri) }
    private val pickPhotos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris -> vm.onPhotosPicked(uris) }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::addLocalFolder) }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.onMicPermission(granted) }

    private val pickBatesPdfs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.onBatesFilesPicked(it) }

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
        if (savedInstanceState == null || (vm.document == null && !vm.opening)) openFromIntent(intent)
        collectRequests()
        setContent {
            PdfcraftTheme {
                AppRoot(
                    vm,
                    onBrowse = { launchSafely { pickPdf.launch(arrayOf("application/pdf")) } },
                    onPickSignatureImage = { launchSafely { pickSignature.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
                )
            }
        }
    }

    /** The view model's requests that need the activity: other apps, pickers, the camera, printing. */
    private fun collectRequests() {
        lifecycleScope.launch { for (i in vm.intents) launchSafely { startActivity(i) } }
        // a document another app opened was closed: back to that app, not Home (#47)
        lifecycleScope.launch { for (u in vm.leave) finish() }
        lifecycleScope.launch { for ((file, name) in vm.prints) print(file, name) }
        lifecycleScope.launch { for (name in vm.saveCopyRequests) launchSafely { saveCopy.launch(name) } }
        lifecycleScope.launch {
            for (ignored in vm.bookmarkJsonPicks) {
                launchSafely { pickBookmarkJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            }
        }
        lifecycleScope.launch {
            for (ignored in vm.bookmarkPdfPicks) {
                launchSafely { pickBookmarkPdf.launch(arrayOf("application/pdf")) }
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
                        FilePickKind.Insert, FilePickKind.Open -> pickOne.launch(arrayOf("application/pdf"))
                        // .p12 / .pfx: providers type them as PKCS #12, or not at all
                        FilePickKind.DigitalId -> pickOne.launch(arrayOf("application/x-pkcs12", "application/pkcs12", "application/octet-stream"))
                    }
                }
            }
        }
        lifecycleScope.launch {
            for (f in vm.cameraShots) {
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
                launchSafely { takePhoto.launch(uri) }
            }
        }
        lifecycleScope.launch { for (d in vm.imagePicks) launchSafely { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
        lifecycleScope.launch { for (u in vm.photoPicks) launchSafely { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
        // the folder picker opens at Documents, where PDFs usually are (Download itself can't be chosen)
        val documents = android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")
        lifecycleScope.launch { for (u in vm.accessRequests) launchSafely { pickFolder.launch(documents) } }
        lifecycleScope.launch {
            for (u in vm.micRequests) {
                // refused before: explain first (the reference's order); otherwise Android's own prompt
                if (shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)) vm.micRationale = true
                else launchSafely { micPermission.launch(android.Manifest.permission.RECORD_AUDIO) }
            }
        }
        // keep the screen on while reading, if asked, until half an hour without a touch
        lifecycleScope.launch {
            while (true) {
                val on = vm.keepScreenOn && vm.document != null && System.currentTimeMillis() - lastTouch < 30L * 60 * 1000
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                delay(5_000)
            }
        }
    }

    private var lastTouch = System.currentTimeMillis()

    // a keyboard (or desktop mode): keys the viewer takes when no text field has them
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        vm.viewerKey(keyCode, event.isCtrlPressed) || super.onKeyDown(keyCode, event)

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastTouch = System.currentTimeMillis()
    }

    private inline fun launchSafely(block: () -> Unit) {
        try {
            block()
        } catch (e: ActivityNotFoundException) {
            vm.tell(getString(R.string.main_error_no_app))
        } catch (e: SecurityException) {
            vm.tell(getString(R.string.main_error_not_allowed))
        }
    }

    override fun onPause() {
        super.onPause()
        vm.onPause()
    }

    override fun onResume() {
        super.onResume()
        vm.onResumed()
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
        val chosen = firstExternalPdfCandidate(
            action,
            candidates.map { IncomingPdfUri(it.scheme, it.authority, it.path) },
        ) ?: return null
        return candidates[chosen]
    }

    /** An external document must not terminate the whole app on a malformed parcelable. */
    private fun openFromIntent(intent: Intent?) {
        val uri = runCatching { externalPdfUri(intent) }.getOrNull() ?: return
        vm.open(uri, fromOtherApp = true)
    }

    /** Hand [file] to Android's print framework. */
    private fun print(file: File, name: String) {
        val pm = getSystemService(PrintManager::class.java) ?: return
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) {
                if (cancel?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                callback.onLayoutFinished(PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), old != new)
            }

            override fun onWrite(pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancel: CancellationSignal?, callback: WriteResultCallback) {
                try {
                    file.inputStream().use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }
        }
        runCatching { pm.print(name, adapter, null) }.onFailure { vm.tell(getString(R.string.main_error_print_not_allowed)) }
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
