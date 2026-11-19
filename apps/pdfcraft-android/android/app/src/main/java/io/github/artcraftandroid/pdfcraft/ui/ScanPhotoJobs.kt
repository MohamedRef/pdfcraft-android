package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.text
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * A scan's photos: taking, importing, searching each one for its page, reordering, deleting and discarding (#730: moved out of AppViewModel.kt as they were).
 * The scan's state stays in [AppViewModel].
 */

internal fun AppViewModel.takeScanPhoto(retake: Int? = null) {
    val dir = File(getApplication<Application>().cacheDir, "camera").apply { mkdirs() }
    val f = File(dir, "scan-${System.nanoTime()}.jpg")
    shotFile = f
    retakeIndex = retake
    cameraShots.trySend(f)
}

internal fun AppViewModel.retakeScan(index: Int) = takeScanPhoto(index)

/** Photos from the gallery for the scan, straight from the document screen (#460). */
internal fun AppViewModel.importScanPhotos() {
    retakeIndex = null
    Diag.event(DiagArea.Scan, "camera_import_open", "from" to "document", "pages" to scanPages.size)
    scanImports.trySend(Unit)
}

/**
 * Move page [from] to [to] (#460); the other pages close up, and the PDF follows the new order.
 * The two halves of a book spread are pages of their own: moving one leaves the other. [by] is
 * `drag` or `talkback`, for the log.
 */
internal fun AppViewModel.moveScanPage(from: Int, to: Int, by: String) {
    if (from !in scanPages.indices || to !in scanPages.indices || from == to) return
    Diag.event(DiagArea.Scan, "pages_reorder", *io.github.artcraftandroid.pdfcraft.ui.scan.scanReorderDiag(from, to, scanPages.size, by))
    scanPages.add(to, scanPages.removeAt(from))
}

/** Delete the pages at [indexes] together, after the person said yes (#460); their photos go unless a spread's other half still uses one. */
internal fun AppViewModel.deleteScanPages(indexes: List<Int>) {
    // largest first, so each removal leaves the indexes still to go where they were. A plain
    // Kotlin list: SortedSet.reversed() is Java 21's SequencedCollection member, API 35+ only (#502)
    val gone = indexes.filter { it in scanPages.indices }.distinct().sortedDescending()
    if (gone.isEmpty()) return
    Diag.event(DiagArea.Scan, "pages_delete", *io.github.artcraftandroid.pdfcraft.ui.scan.scanPagesDeleteDiag(gone.size, scanPages.size, confirmed = true))
    val before = scanPages.toList()
    for (i in gone) scanPages.removeAt(i)
    deleteUnusedScanFiles(before, scanPages.toList())
    // every page deleted: nothing is left to save, so the scan ends
    if (scanPages.isEmpty()) discardScan(asked = true)
}

/** The person answered Cancel to deleting the chosen pages (#460). */
internal fun AppViewModel.keepScanPages(count: Int) {
    Diag.event(DiagArea.Scan, "pages_delete", *io.github.artcraftandroid.pdfcraft.ui.scan.scanPagesDeleteDiag(count, scanPages.size, confirmed = false))
}

/** The camera app came back: [ok] when it took the photo. */
internal fun AppViewModel.onScanPhoto(ok: Boolean) {
    val f = shotFile ?: return
    shotFile = null
    if (editor.imageShot) {
        editor.imageShot = false
        // after Android closed the app, the document the picture was for isn't open any more
        if (!ok || !f.isFile || f.length() == 0L || editor.imageFor == null) {
            f.delete()
            editor.imageFor = null
            editor.imageAt = null
            return
        }
        // turned upright as the camera meant it (the page keeps no orientation tag), then placed
        viewModelScope.launch {
            val upright = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.let { rotateForExif(f, it) } ?: return@runCatching f
                    val out = File(f.parentFile, "upright-${f.name}")
                    out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                    out
                }.getOrDefault(f)
            }
            editor.addImage(Uri.fromFile(upright))
        }
        return
    }
    if (!ok || !f.isFile || f.length() == 0L) {
        scanSteps.photoDropped()
        viewModelScope.launch {
            scanRestore?.join()
            if (scanPages.isEmpty()) tools.toolScreen = null
        }
        return
    }
    viewModelScope.launch {
        // the pages taken before Android closed the app come first
        scanRestore?.join()
        val thumb = withContext(Dispatchers.IO) { scanThumb(f) } ?: return@launch
        val r = retakeIndex
        // the Flatten page choice and the look of the page it replaces, or of the page before it
        // (#302, #301): a retaken page keeps them, a new one takes the last chosen
        val page = ScanPage(
            f, thumb,
            flatten = io.github.artcraftandroid.pdfcraft.ui.scan.scanNewPageFlatten(scanPages.map { it.flatten }, r),
            // a look the automatic look chose for one page is not passed on (#466)
            look = io.github.artcraftandroid.pdfcraft.ui.scan.scanLookForNewPage(scanPages.map { io.github.artcraftandroid.pdfcraft.ui.scan.scanLookToPassOn(it.look, it.auto) }, r),
        )
        retakeIndex = null
        // the page waits for its edges before it is made in the background (#465)
        pageQueue.findingStarted(f)
        // a retaken half of a book spread takes its other half with it (#303)
        replaceScanPages(r, listOf(page))
        // One page mode, or an import of one photo: Adjust borders opens on it (#458)
        scanSteps.pageAdded(page.key)
        if (scanName.isBlank()) scanName = "Scan " + java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US).format(java.util.Date())
        if (tools.toolScreen != ToolScreen.Scan) tools.toolScreen = ToolScreen.Scan
        // the document's edges in the photo, shown on the page and moved in Crop; the whole
        // photo is kept when none are found
        // (the engine may still be starting when the app was closed and opened again)
        val findStarted = android.os.SystemClock.elapsedRealtime()
        val eng = engine ?: application.engine.await().getOrNull()
        // One native call decodes the photo once, has the detector network find the page on a
        // small copy (#290) and moves each side onto the paper's edge at full size (#291). The
        // network's model is unpacked from the app's assets the first time; without it the
        // line detector is asked instead. The testing options (#288) can name the detector
        // and leave the closer look out.
        val lab = scanLab.options
        // a low-light merge (#297) gave a page already straightened: its edges are the whole picture
        val straightened = io.github.artcraftandroid.pdfcraft.ui.scan.ScanCaptures.of(f.path)?.straightened == true
        val doc = eng?.takeUnless { straightened }?.let {
            withContext(Dispatchers.IO) { runCatching { it.prepareOcrModels(getApplication()) } }
            withContext(Dispatchers.Default) { runCatching { io.github.artcraftandroid.pdfcraft.core.scanFindDocumentWith(f.absolutePath, lab.findOptions()) } }
                .onFailure { e -> Diag.failure(DiagArea.Scan, "scan_find_document", e) }.getOrNull()
        }
        val edges = doc?.takeIf { it.found }?.corners?.takeIf { it.size == 8 }?.toFloatArray()
        // book mode (#303): the outline (or the whole photo when none was found) cut along the gutter
        val book = scanBook.on
        val bookOrder = scanBook.order
        val bookStarted = android.os.SystemClock.elapsedRealtime()
        val cut = if (book && eng != null) splitScanSpread(f, edges ?: ScanPage.WHOLE, lab.bookGutter) else null
        val bookMs = android.os.SystemClock.elapsedRealtime() - bookStarted
        // the models are unpacked by now: whether the flattening one is among them (#302)
        if (!scanCanFlatten && eng != null) {
            scanCanFlatten = withContext(Dispatchers.Default) { runCatching { io.github.artcraftandroid.pdfcraft.core.scanCanFlatten() }.getOrDefault(false) }
        }
        Diag.event(
            DiagArea.Scan, "photo", "retake" to (r != null), "bytes" to f.length(), "edges_found" to (edges != null),
            "find_ms" to android.os.SystemClock.elapsedRealtime() - findStarted,
            "source" to (doc?.source?.name?.lowercase() ?: "failed"), "confidence" to (doc?.confidence?.toDouble() ?: 0.0),
            "angle" to (doc?.angle?.toInt() ?: 0), "passes" to (doc?.passes?.toInt() ?: 0),
            *(doc?.timings.orEmpty().map<io.github.artcraftandroid.pdfcraft.core.ScanTiming, Pair<String, Any?>> { it.stage.removePrefix("scan_") + "_ms" to it.ms.toLong() }.toTypedArray()),
            "refined_sides" to (doc?.refined?.count { it } ?: 0), "curved_sides" to (doc?.curved?.count { it } ?: 0), "curved_opposite" to scanCurvedOpposite(doc?.curved.orEmpty()),
            "finder" to ScanLab.finderId(lab.finder), "refine" to lab.refine, "straightened" to straightened,
            *io.github.artcraftandroid.pdfcraft.ui.scan.scanBookDiag(book, lab.bookGutter, bookOrder, cut, bookMs),
        )
        val finding = doc?.let { d ->
            ScanFinding(d.source.name.lowercase(), d.confidence.toDouble(), d.angle.toInt(), d.passes.toInt(), d.refined.count { it }, d.curved.count { it }, scanCurvedOpposite(d.curved), lab.findOptions())
        }
        // Adjust borders waits for this answer before Next (#458)
        scanSteps.markSearched(f.absolutePath)
        val at = scanPages.indexOfFirst { it.file == f }
        if (at >= 0) {
            // a page whose outline was not found remembers the corners it started with (#444)
            val one = if (edges != null) scanPages[at].copy(corners = edges, found = edges, finding = finding)
            else scanPages[at].let { it.copy(finding = finding, uncropped = io.github.artcraftandroid.pdfcraft.ui.scan.scanUncropped(it.corners, null, straightened)) }
            // in book mode the page becomes the spread's two halves, in the order chosen (#303)
            val halves = io.github.artcraftandroid.pdfcraft.ui.scan.scanBookPages(one.corners, cut, bookOrder).map { (corners, half) ->
                // a half's own outline is what was found for it, so a corner moved later is measured from there
                if (half == null) one else one.copy(corners = corners, found = one.found?.let { corners }, uncropped = one.uncropped?.let { corners }, book = half)
            }
            scanPages.removeAt(at)
            scanPages.addAll(at, halves)
        }
    }.invokeOnCompletion {
        // the page may be made now that its edges are known (#465)
        viewModelScope.launch { pageQueue.findingDone(f) }
    }
}

/** The outline [corners] of the photo [f] cut along the book's gutter (#303); null when that failed. */
private suspend fun AppViewModel.splitScanSpread(f: File, corners: FloatArray, lookForGutter: Boolean): io.github.artcraftandroid.pdfcraft.ui.scan.ScanBookCut? =

/**
 * Put [replacement] in place of page [at] (null or out of range: after the last page). A half
 * of a book spread goes with its other half, and a photo no page uses any more is deleted (#303).
 */
private fun AppViewModel.replaceScanPages(at: Int?, replacement: List<ScanPage>) {
    val before = scanPages.toList()
    val after = io.github.artcraftandroid.pdfcraft.ui.scan.scanReplacePage(before, at, replacement) { a, b -> a.file == b.file }
    scanPages.clear()
    scanPages.addAll(after)
    deleteUnusedScanFiles(before, after)
}

/**
 * Remove page [index] from the scan; its photo is deleted unless the spread's other half still
 * uses it (#303). [asked]: the person confirmed it in the Delete page question (#456).
 */
internal fun AppViewModel.deleteScanPage(index: Int, asked: Boolean = false) {
    if (index !in scanPages.indices) return
    Diag.event(DiagArea.Scan, "page_delete", *io.github.artcraftandroid.pdfcraft.ui.scan.scanPageDeleteDiag(index, scanPages.size, asked, confirmed = true))
    val before = scanPages.toList()
    scanPages.removeAt(index)
    deleteUnusedScanFiles(before, scanPages.toList())
}

private fun AppViewModel.deleteUnusedScanFiles(before: List<ScanPage>, after: List<ScanPage>) {
    val unused = io.github.artcraftandroid.pdfcraft.ui.scan.scanUnusedFiles(before.map { it.file.path }, after.map { it.file.path })
    unused.forEach { File(it).delete() }
}

internal fun AppViewModel.rotateForExif(f: File, bmp: android.graphics.Bitmap): android.graphics.Bitmap {
    val deg = runCatching {
        when (androidx.exifinterface.media.ExifInterface(f.absolutePath).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    if (deg == 0f) return bmp
    val m = android.graphics.Matrix().apply { postRotate(deg) }
    return android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}

/** The person answered Keep scanning (or Cancel) to the scan screen's question (#456): nothing goes. */
internal fun AppViewModel.keepScanAfterAsking(deletingPage: Int? = null) {
    if (deletingPage != null) {
        Diag.event(DiagArea.Scan, "page_delete", *io.github.artcraftandroid.pdfcraft.ui.scan.scanPageDeleteDiag(deletingPage, scanPages.size, asked = true, confirmed = false))
    } else {
        Diag.event(DiagArea.Scan, "discard", *io.github.artcraftandroid.pdfcraft.ui.scan.scanDiscardDiag(scanPages.size, asked = true, confirmed = false))
    }
}

/** Throw the scan away: every page's photo is deleted. [asked]: the person confirmed it in the scan screen's question (#456). */
internal fun AppViewModel.discardScan(asked: Boolean = false) {
    val discarded = scanPages.toList()
    val gallery = scanGallerySaved.toSet()
    val fromRecent = scanRecent.openedFrom != null
    Diag.event(DiagArea.Scan, "discard", *io.github.artcraftandroid.pdfcraft.ui.scan.scanDiscardDiag(discarded.size, asked, confirmed = true))
    if (scanLab.options.keep && discarded.isNotEmpty()) {
        // kept for testing first (#288): a scan given up on is often the one worth looking at
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                scanLab.keepDiscarded(discarded, asked, gallery, fromRecent)
                discarded.forEach { it.file.delete() }
            }
            scanLab.refreshKept()
        }
    } else {
        discarded.forEach { it.file.delete() }
    }
    scanPageDir().listFiles()?.forEach { it.delete() }
    scanPages.clear()
    scanSteps.reset()
    scanBatch.borders = null
    scanBatch.look = null
    scanBatch.offer = 0
    scanBatch.asking = null
    // a scan opened from Recent scans and given up on stays there as it was saved (#461)
    scanRecent.openedFrom = null
    scanGallerySaved.clear()
    scanName = ""
    // the previews and the strip's tiles of a scan given up on (#301)
    releaseScanPreviews()
    insertScanAt = null
    // a scan opened from its PDF and given up on leaves the PDF as it is (#513)
    scanEdit.dropped()
    tools.toolScreen = null
}
