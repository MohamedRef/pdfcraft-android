package io.github.artcraftandroid.pdfcraft.ui.scan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.io.InputStream

/*
 * The only file that calls ML Kit's document scanner (#307, spec S21). Everything else in the app
 * uses ScanEngineChoice.kt and ScanEngineCopy.kt, which need no Google library.
 * API as written in the official guide, developers.google.com/ml-kit/vision/doc-scanner/android
 * (the guide's version: 16.0.0). Not compiled: see REPORT.md.
 */

/** Google Play services: its presence means Google's scanner can run on this device. */
private const val PLAY_SERVICES_PACKAGE: String = "com.google.android.gms"

/** Whether Google Play services is installed. Read from the package list; no library is needed. */
fun playServicesInstalled(context: Context): Boolean = try {
    context.packageManager.getApplicationInfo(PLAY_SERVICES_PACKAGE, 0)
    true
} catch (e: PackageManager.NameNotFoundException) {
    false
}

/**
 * The ML Kit options for [options]. Always JPEG pages (PdfCraft builds the PDF), and no page limit
 * unless [GoogleScanOptions.pageLimit] is a positive number.
 */
fun buildGoogleScannerOptions(options: GoogleScanOptions): GmsDocumentScannerOptions {
    val builder: GmsDocumentScannerOptions.Builder = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(options.galleryImportAllowed)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .setScannerMode(scannerModeOf(options.mode))
    val limit: Int? = options.pageLimit
    if (limit != null && limit > 0) {
        builder.setPageLimit(limit)
    }
    return builder.build()
}

/** The ML Kit constant for [mode]. */
private fun scannerModeOf(mode: GoogleScanMode): Int = when (mode) {
    GoogleScanMode.Base -> GmsDocumentScannerOptions.SCANNER_MODE_BASE
    GoogleScanMode.BaseWithFilter -> GmsDocumentScannerOptions.SCANNER_MODE_BASE_WITH_FILTER
    GoogleScanMode.Full -> GmsDocumentScannerOptions.SCANNER_MODE_FULL
}

/** The failure named by an exception from starting the scanner. */
fun googleStartFailureOf(error: Exception): GoogleStartFailure {
    if (error is MlKitException && error.errorCode == MlKitException.UNSUPPORTED) {
        return GoogleStartFailure.Unsupported
    }
    return GoogleStartFailure.Other
}

/**
 * Ask Google's scanner for its start intent and launch it through [launcher], which the activity
 * registered with StartIntentSenderForResult when it was created. [onFailure] gets the failure
 * to pass to onGoogleStartFailure. Runs on the main thread; the ML Kit calls return at once.
 */
fun startGoogleScan(
    activity: Activity,
    options: GoogleScanOptions,
    launcher: ActivityResultLauncher<IntentSenderRequest>,
    onFailure: (GoogleStartFailure) -> Unit,
) {
    val client: GmsDocumentScanner = GmsDocumentScanning.getClient(buildGoogleScannerOptions(options))
    client.getStartScanIntent(activity)
        .addOnSuccessListener { intentSender: IntentSender ->
            try {
                launcher.launch(IntentSenderRequest.Builder(intentSender).build())
            } catch (e: Exception) {
                onFailure(GoogleStartFailure.Other)
            }
        }
        .addOnFailureListener { error: Exception -> onFailure(googleStartFailureOf(error)) }
}

/**
 * The image URIs of the pages in Google's result [data], in order. A page without a URI is kept
 * as null so that the count still matches the result. Empty when there is no result to read.
 */
fun googleScanPageUris(data: Intent?): List<Uri?> {
    if (data == null) return emptyList()
    val result: GmsDocumentScanningResult = GmsDocumentScanningResult.fromActivityResultIntent(data) ?: return emptyList()
    val pages = result.getPages() ?: return emptyList()
    val uris = ArrayList<Uri?>(pages.size)
    for (page in pages) {
        uris.add(page.getImageUri())
    }
    return uris
}

/**
 * Copy each of [uris] into [cameraDir] under a name from [googlePageFileName] (#307). Returns the
 * files made, in order. When any page cannot be copied, the copies already made are deleted and
 * the result is null: a scan is taken whole or not at all. The Google URIs are temporary, so the
 * copy is needed.
 */
fun copyGooglePages(context: Context, uris: List<Uri?>, cameraDir: File): List<File>? {
    cameraDir.mkdirs()
    val made = ArrayList<File>(uris.size)
    var stamp: Long = System.nanoTime()
    for (uri in uris) {
        // a name that is not taken yet, so no page overwrites an earlier one
        var target = File(cameraDir, googlePageFileName(stamp))
        while (target.exists()) {
            stamp += 1
            target = File(cameraDir, googlePageFileName(stamp))
        }
        val input: InputStream? = openPageStream(context, uri)
        if (!copyPageStream(input, target)) {
            made.forEach { it.delete() }
            return null
        }
        made.add(target)
    }
    return made
}

/** The stream of [uri], or null when it cannot be opened. */
private fun openPageStream(context: Context, uri: Uri?): InputStream? {
    if (uri == null) return null
    return try {
        context.contentResolver.openInputStream(uri)
    } catch (e: Exception) {
        null
    }
}
