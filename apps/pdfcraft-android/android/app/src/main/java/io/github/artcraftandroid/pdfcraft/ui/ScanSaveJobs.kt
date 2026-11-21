package io.github.artcraftandroid.pdfcraft.ui

import android.content.Intent
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.plural
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.engine.DocumentFiles
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.engine.Engine
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Save PDF, Share, Print, Extract text and Save to gallery for a scan (#730: moved out of AppViewModel.kt as they were).
 * The scan's state and the request builders stay in [AppViewModel].
 */

/**
 * Save the pages at [indexes] (all of them when null) into the gallery's PdfCraft album
 * (#470), each made as it goes into the PDF: borders, look, turn and flattening. Named after the
 * scan with the page number from 1; a page that fails is told by number and the others are kept.
 * The pages' order is the scan's; the turn the multi-page rule (#339) may give a page on Save
 * is not used here.
 */
internal fun AppViewModel.saveScanToGallery(indexes: List<Int>?) = job(text(R.string.scan_gallery_busy)) { _ ->
    // the pages made at full size ahead (#504) are not touched while the gallery is written
    fullPages.exclusive { saveScanToGalleryNow(indexes) }
}

private suspend fun AppViewModel.saveScanToGalleryNow(indexes: List<Int>?) {
    val pages = scanPages.toList()
    val chosen = (indexes ?: pages.indices.toList()).filter { it in pages.indices }.distinct().sorted()
    if (chosen.isEmpty()) return
    val started = android.os.SystemClock.elapsedRealtime()
    val dir = File(scanPageDir(), "gallery").apply { mkdirs() }
    val lab = scanLab.options
    val autoUpright = io.github.artcraftandroid.pdfcraft.ui.scan.ScanUprightSetting.get(settings.appPrefs)
    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US).format(java.util.Date())
    val base = DocumentFiles.safeName(scanName.trim().ifEmpty { "Scan $stamp" }).removeSuffix(".pdf")
    val failed = ArrayList<Int>()
    var saved = 0
    var bytes = 0L
    var cancelled = false
    tools.busyCancel = { cancelled = true }
    tools.busyCancelNote = text(R.string.busy_cancel_gallery)
    // pages made at full size ahead (#504): the gallery gets the page Save would make, taken
    // when it is ready and kept for Save when it is made here
    val ahead = lab.fullPagesAhead && lab.backgroundPages && lab.preview
    var readyCount = 0
    try {
        for ((n, i) in chosen.withIndex()) {
            if (cancelled) break
            val page = pages[i]
            val shown = if (ahead) scanShownOf(page, lab) else null
            val out = if (ahead) fullPages.newFile() else File(dir, "page-$i")
            val request = if (ahead) scanSaveRequest(page, out.absolutePath, page.turns ?: shown?.turns, shown, lab, autoUpright, "best") else scanRequestFor(page, out, page.turns, lab, autoUpright)
            val cached = if (ahead) fullPages.take(page, request) else null
            if (cached != null) readyCount++
            val r = cached ?: run {
                tools.busy = text(if (ahead) R.string.msg_busy_finishing_page else R.string.msg_busy_cleaning_page, n + 1, chosen.size)
                tools.busyProgress = n to chosen.size
                val started = android.os.SystemClock.elapsedRealtime()
                val made = withContext(Dispatchers.Default) { runCatching { io.github.artcraftandroid.pdfcraft.core.scanProcessPage(request) } }
                made.onFailure { Diag.failure(DiagArea.Scan, "scan_process_page", it) }
                made.getOrNull()?.also { if (ahead) fullPages.keep(i, page, request, it, android.os.SystemClock.elapsedRealtime() - started, null) }
            }
            val written = r?.let {
                withContext(Dispatchers.IO) {
                    val f = File(it.path)
                    val png = it.format == io.github.artcraftandroid.pdfcraft.core.ScanFormat.PNG
                    runCatching { files.saveToPictures(f, io.github.artcraftandroid.pdfcraft.ui.scan.scanGalleryName(base, i, png), if (png) "image/png" else "image/jpeg"); f.length() }
                        .onFailure { e -> Diag.failure(DiagArea.Scan, "gallery_save_page", e) }
                        // a page made at full size stays for Save until the scan ends (#504)
                        .also { if (!ahead) f.delete() }
                        .getOrNull()
                }
            }
            if (written != null) {
                saved++
                bytes += written
                scanGallerySaved += page.key
            } else {
                failed += i + 1
            }
        }
    } finally {
        tools.busyCancel = null
        tools.busyCancelNote = null
        tools.busyProgress = null
        withContext(Dispatchers.IO) { dir.deleteRecursively() }
    }
    Diag.event(DiagArea.Scan, "gallery_save", *io.github.artcraftandroid.pdfcraft.ui.scan.scanGalleryDiag(chosen.size, saved, failed, bytes, android.os.SystemClock.elapsedRealtime() - started, indexes != null, cancelled), "full_pages_ahead" to ahead, "pages_ready" to readyCount)
    val parts = buildList {
        if (saved > 0) add(plural(R.plurals.scan_gallery_saved, saved, saved))
        if (failed.isNotEmpty()) add(text(R.string.scan_gallery_failed, failed.joinToString(", ")))
    }
    if (parts.isNotEmpty()) tell(parts.joinToString(" "))
}

/**
 * Make the scan's PDF and save it into Downloads, or into the open document it was for. With
 * [share] (the document screen's Share, #460) the same PDF goes to Android's share sheet
 * instead: nothing is saved, and the scan stays open to be saved or shared again. [out] Print
 * and Text (#516) make the same PDF for the print screen, or read its words and hand them out
 * as text; nothing is saved either.
 */
internal fun AppViewModel.saveScan(share: Boolean = false, size: String? = null, out: io.github.artcraftandroid.pdfcraft.ui.scan.ScanOut = if (share) io.github.artcraftandroid.pdfcraft.ui.scan.ScanOut.Share else io.github.artcraftandroid.pdfcraft.ui.scan.ScanOut.Save) {
    val saving = out == io.github.artcraftandroid.pdfcraft.ui.scan.ScanOut.Save
    // a scan opened from its PDF (#513): comments on pages whose borders or place change are asked about first
    if (saving && scanEdit.mustAsk(size)) return
    job(text(if (saving || out == io.github.artcraftandroid.pdfcraft.ui.scan.ScanOut.Share) R.string.msg_busy_saving_scan else R.string.scan_busy_preparing)) { eng ->
        // the pages made at full size ahead (#504) are not touched while Save runs
        fullPages.exclusive { saveScanNow(eng, !saving, size, out) }
    }
}

private suspend fun AppViewModel.saveScanNow(eng: io.github.artcraftandroid.pdfcraft.engine.Engine, share: Boolean, size: String?, purpose: io.github.artcraftandroid.pdfcraft.ui.scan.ScanOut) {
    val pages = scanPages.toList()
    val scanStarted = android.os.SystemClock.elapsedRealtime()
    // the PDF this scan was opened from in the viewer, and its pages as they are now (#513)
    val editing = if (share) null else scanEdit.target
    val editedPages = editing?.let { scanEdit.pagesNow() }
    val dir = scanPageDir().apply { mkdirs() }
    val saved = ArrayList<ScanSavedPage>(pages.size)
    // the testing options in force (#288), and where this scan is kept when scans are kept
    val lab = scanLab.options
    // the size chosen on the sheet, else the one remembered (#505)
    val sizeChoice = scanSizeNow(lab, size)
    // Cancel in the busy dialog stops before the next page is made (#200); nothing is saved (#504)
    var cancelled = false
    tools.busyCancel = { cancelled = true }
    tools.busyCancelNote = text(R.string.busy_cancel_scan_save)
    // a share is not a saved scan: the kept scans (#288) are the ones saved
    val keepIn = if (share) null else try {
        withContext(Dispatchers.IO) { scanLab.begin() }
    } catch (e: Exception) {
        tools.busyCancel = null
        tools.busyCancelNote = null
        throw e
    }
    // which way up (#339): the ordinary setting, and each page's own turn when the person gave one
    val autoUpright = io.github.artcraftandroid.pdfcraft.ui.scan.ScanUprightSetting.get(settings.appPrefs)
    // each page made once, the same request the screen's preview was made from (#301)
    val made = arrayOfNulls<io.github.artcraftandroid.pdfcraft.core.ScanPageResult>(pages.size)
    val madeMs = LongArray(pages.size)
    // the turn and document type each page showed, kept for the PDF (#465): what was seen is what is saved
    val shown = pages.map { scanShownOf(it, lab) }
    // pages made at full size ahead of Save (#504): taken when Save would ask the same
    val ahead = lab.fullPagesAhead && lab.backgroundPages && lab.preview
    val firstTurns = pages.indices.map { pages[it].turns ?: shown[it]?.turns }
    val readyAtTap = pages.indices.map { i -> ahead && fullPages.take(pages[i], scanSaveRequest(pages[i], "", firstTurns[i], shown[i], lab, autoUpright, sizeChoice)) != null }
    // pages made ahead whatever their size (#528): with a size not stored they are made again
    val madeAhead = pages.indices.count { i -> ahead && fullPages.madeFrom(pages[i], scanSaveRequest(pages[i], "", firstTurns[i], shown[i], lab, autoUpright, sizeChoice)) }
    val toFinish = io.github.artcraftandroid.pdfcraft.ui.scan.scanPagesToFinish(readyAtTap)
    // the n of m in "Finishing page n of m": only the pages not made ahead are counted
    var finishing = 0
    // two pages made at once (#549), and the most that were
    val lanes = io.github.artcraftandroid.pdfcraft.ui.scan.scanSaveLanes(lab.parallelSave, toFinish.size)
    var atOnce = 0
    var mostAtOnce = 0
    suspend fun make(i: Int, turns: Int?) {
        val page = pages[i]
        // the page's place in the name: two pages are made at once (#549)
        val out = if (ahead) File(fullPages.newFile().path + "-$i") else File(dir, "page-$i")
        val request = scanSaveRequest(page, out.absolutePath, turns, shown[i], lab, autoUpright, sizeChoice)
        if (ahead) fullPages.take(page, request)?.let {
            made[i] = it
            return
        }
        if (ahead) {
            finishing++
            val total = maxOf(toFinish.size, finishing)
            tools.busy = text(R.string.msg_busy_finishing_page, finishing, total)
            tools.busyProgress = finishing - 1 to total
        } else {
            tools.busy = text(R.string.msg_busy_cleaning_page, i + 1, pages.size)
            tools.busyProgress = i to pages.size
        }
        val started = android.os.SystemClock.elapsedRealtime()
        atOnce++
        mostAtOnce = maxOf(mostAtOnce, atOnce)
        val result = try {
            withContext(Dispatchers.Default) { runCatching { io.github.artcraftandroid.pdfcraft.core.scanProcessPage(request) } }
        } finally {
            atOnce--
        }
        result.onFailure { Diag.failure(DiagArea.Scan, "scan_process_page", it) }
        madeMs[i] += android.os.SystemClock.elapsedRealtime() - started
        result.getOrNull()?.let {
            made[i] = it
            // kept for a Share followed by Save, or Save to gallery (#504)
            if (ahead) fullPages.keep(i, page, request, it, madeMs[i], null)
        }
    }
    // the size sheet's estimate may still be making the first page (#505): one full-size page at a time
    scanSizeBusy.withLock { }
    try {
        // [lanes] pages at once, each taking the next page in the scan's order (#549); one lane is the order as before
        var nextPage = 0
        kotlinx.coroutines.coroutineScope {
            val scope = this
            repeat(lanes) {
                scope.launch {
                    while (!cancelled) {
                        val i = nextPage++
                        if (i >= pages.size) break
                        make(i, firstTurns[i])
                    }
                }
            }
        }
    } finally {
        // from here the pages go into the PDF, which can't be stopped half-way
        tools.busyCancel = null
        tools.busyCancelNote = null
    }
    if (cancelled) {
        // nothing is saved: the pages made so far go, the scan stays on screen as it was
        val madeCount = made.count { it != null }
        withContext(Dispatchers.IO) {
            dir.listFiles()?.forEach { it.delete() }
            keepIn?.let { scanLab.end(it) }
        }
        Diag.event(DiagArea.Scan, "save_cancelled", "pages" to pages.size, "made" to madeCount, "ms" to android.os.SystemClock.elapsedRealtime() - scanStarted, "pages_ready" to readyAtTap.count { it })
        tell(text(R.string.msg_scan_save_cancelled))
        return
    }
    tools.busyProgress = null
    // what the classifier said of each page, before the multi-page rule makes any page again
    // a page whose shown turn was kept was not asked again: the classifier's answer is the shown one (#465)
    val found = made.mapIndexed { i, r -> r?.orientation ?: pageQueue.readyOf(pages[i])?.takeIf { shown[i]?.turns != null }?.orientation }
    val classifierTurns = made.mapIndexed { i, r -> if (shown[i]?.turns != null) pageQueue.readyOf(pages[i])?.quarterTurns ?: 0 else r?.quarterTurns?.toInt() ?: 0 }
    // the multi-page rule (#339): an unsure page between two sure neighbours that agree takes
    // their turn, and is made again with it
    val settled = if (lab.settleTurns && pages.size >= 3) {
        runCatching { io.github.artcraftandroid.pdfcraft.core.scanSettleTurns(found) }.getOrNull()
            ?.map { io.github.artcraftandroid.pdfcraft.ui.scan.ScanSettled(it.quarterTurns.toInt(), it.followed) }
    } else null
    val redo = settled?.let { io.github.artcraftandroid.pdfcraft.ui.scan.scanTurnsToRedo(it, made.map { r -> r?.turns?.toInt() }, lab.settleTurns) }.orEmpty()
    for ((i, turn) in redo) make(i, turn)
    for ((i, page) in pages.withIndex()) {
        // the Scan gates (#286): found edges, whether they were moved and how far (in % of the
        // photo's diagonal, so the thumbnail's proportions are enough)
        val stats = scanPageStats(page.corners, page.found, ScanPage.WHOLE, page.thumb.width.toDouble(), page.thumb.height.toDouble())
        // a whole photo in Original stays the camera's own file: natively it only gets its
        // resolution written in, so its PDF page has a size like the others (#293)
        val flatten = io.github.artcraftandroid.pdfcraft.ui.scan.scanPageFlattens(page.flatten, scanCanFlatten)
        val filter = page.look.filter
        val strength = scanLookStrength(page.look, lab)
        val autoFlatten = scanAutoFlattens(page, lab)
        val untouched = !(flatten || autoFlatten) && !scanNeedsProcessing(page.corners.contentEquals(ScanPage.WHOLE), filter.mode) && io.github.artcraftandroid.pdfcraft.ui.scan.scanMarksFor(page, lab) == null
        val r = made[i]
        val followed = i in redo
        val turnSource = io.github.artcraftandroid.pdfcraft.ui.scan.scanTurnSource(page.turns, followed, page.turns == null && autoUpright && lab.upright)
        // decode, straighten, look and encode times (#287), the page's pixels, and how its
        // proportions were worked out and which paper it was matched to (#293)
        Diag.event(
            DiagArea.Scan, "page", "index" to i + 1, "book_half" to (page.book?.side ?: "none"), "edges_found" to stats.found, "corners_moved" to stats.touched, "max_move_pct" to stats.maxMovePct,
            "filter" to filter, "enhanced" to (r != null && !untouched), "enhance_ms" to madeMs[i],
            // the page's own look and where its strength came from (#301)
            "look" to io.github.artcraftandroid.pdfcraft.ui.scan.scanFilterId(filter), "look_per_page" to lab.lookPerPage,
            "paper" to page.look.paper.word, "whiteboard_look" to lab.whiteboardLook,
            "strength_source" to (if (lab.strength != null) "lab" else page.look.strengthSource),
            "look_differs" to io.github.artcraftandroid.pdfcraft.ui.scan.scanLookDiffers(pages.map { it.look }, i),
            *(r?.timings.orEmpty().map<io.github.artcraftandroid.pdfcraft.core.ScanTiming, Pair<String, Any?>> { it.stage.removePrefix("scan_") + "_ms" to it.ms.toLong() }.toTypedArray()),
            "out_width" to (r?.width?.toLong() ?: 0L), "out_height" to (r?.height?.toLong() ?: 0L),
            "out_format" to (r?.format?.name?.lowercase() ?: "none"), "dpi" to (r?.dpi?.toDouble() ?: 0.0),
            "paper" to (r?.paper?.name?.lowercase() ?: "none"), "aspect" to (r?.aspectSource?.name?.lowercase() ?: "none"),
            // the scan's own paper and the card rule the page was asked with (#759)
            *io.github.artcraftandroid.pdfcraft.ui.scan.scanPaperRuleDiag(scanPaperHint(page, lab), scanCardPaper(page, lab), null),
            "focal_mismatch" to (r?.focalMismatch ?: false),
            "flatten" to flatten, "flattened" to (r?.flattened ?: false), "bend_pct" to ((r?.bend ?: 0f) * 100.0),
            "flatten_auto" to lab.flattenAuto, *scanFlattenCheckDiag(r?.flattenCheck),
            // the person's own choice for the page, and the strip of table trimmed from a flattened one (#302)
            "flatten_page" to io.github.artcraftandroid.pdfcraft.ui.scan.scanFlattenChoiceId(page.flatten),
            "flatten_trim" to lab.flattenTrim, "flatten_line_floor" to lab.flattenLineFloor, *io.github.artcraftandroid.pdfcraft.ui.scan.scanTrimDiag(r?.trim),
            *scanLineFlattenDiag(lab.lineFlatten, r?.lineFlatten),
            // the preview's flattening decision taken over instead of made again (#549)
            "flatten_reuse" to lab.flattenReuse, "flatten_reused" to (r?.flattenReused ?: false), "flatten_keep_all" to lab.flattenKeepAll,
            // a page already flat left unflattened (#549)
            "skip_flat" to lab.skipFlat, "flatten_skipped_flat" to (r?.flattenSkippedFlat ?: false),
            "strength" to strength, "side_proportions" to !lab.perspective, "snap_paper" to lab.snapPaper,
            // the strength the page was really made at, and whether the slider's counts as set by hand (#424)
            *scanAppliedStrengthDiag(scanAppliedStrength(page.look, lab, r?.kind?.strength?.toInt()), lab),
            // which way up the page was found to be and whether it was turned (#339)
            "upright" to lab.upright, "quarter_turns" to classifierTurns[i], "upright_sure" to (found[i]?.sure ?: false),
            "upright_confidence" to (found[i]?.confidence?.toDouble() ?: 0.0), "upright_agreement" to (found[i]?.agreement?.toDouble() ?: 0.0),
            "upright_tiles" to (found[i]?.tiles?.toInt() ?: 0),
            // the turn the page was saved with and where it came from: the person, its neighbours or the classifier
            "auto_upright" to autoUpright, "turns_manual" to (page.turns ?: -1), "turns" to (r?.turns?.toInt() ?: 0),
            "turn_source" to turnSource, "settle_turns" to lab.settleTurns, "followed" to followed,
            // sharpening and smoothing asked of the Clean scan filter, and the grain it measured (#299)
            "sharpen" to lab.sharpen, "denoise" to lab.denoise, "noise_sigma" to (r?.noise?.toDouble() ?: -1.0),
            // a grainy page's paper drawn white from a lower level (#466): the option, how much the paper varies, the level used
            "grain_white" to lab.grainWhite, "paper_spread" to (r?.paperSpread?.toDouble() ?: -1.0), "white_point" to (r?.whitePoint?.toDouble() ?: -1.0),
            // the kind of document Clean scan took the page for, its settings and what it read (#299)
            "presets" to lab.presets, "preset" to (r?.kind?.preset ?: "none"), "preset_chosen" to (r?.kind?.chosen ?: false),
            // where the kind came from: not worked out for a page whose outline was not found (#444)
            "kind_source" to io.github.artcraftandroid.pdfcraft.ui.scan.scanKindSource(page, lab), "unfound_document" to lab.unfoundDocument,
            "preset_strength" to (r?.kind?.strength?.toInt() ?: -1), "preset_sharpen" to (r?.kind?.sharpen?.toInt() ?: -1),
            "keep_paper" to (r?.kind?.keepPaper ?: false), "suggest_flatten" to (r?.kind?.suggestFlatten ?: false),
            "suggest_greyscale" to (r?.kind?.suggestGreyscale ?: false), "page_ratio" to (r?.kind?.ratio?.toDouble() ?: 0.0),
            "ink_share" to (r?.kind?.inkShare?.toDouble() ?: -1.0), "colour_ink_share" to (r?.kind?.colourInkShare?.toDouble() ?: -1.0),
            "photo_share" to (r?.kind?.photoShare?.toDouble() ?: -1.0), "stroke_mm" to (r?.kind?.strokeMm?.toDouble() ?: -1.0),
            "bright_share" to (r?.kind?.brightShare?.toDouble() ?: -1.0), "gutter_band" to (r?.kind?.gutterBand ?: false),
            // the book-page gutter rule (#427): the option, what the first rule says, and how much of the side was dark
            "strict_gutter" to lab.strictGutter, "gutter_loose" to (r?.kind?.gutterLoose ?: false),
            "gutter_cover" to (r?.kind?.gutterCover?.toDouble() ?: -1.0),
            // the photo rule (#427): the option, and what the first rule reads as photograph
            "strict_photo" to lab.strictPhoto, "photo_loose" to (r?.kind?.photoLoose?.toDouble() ?: -1.0),
            // the book page rule (#442): the option, what the first rule says, and its inputs
            "strict_book" to lab.strictBook, "book_loose" to (r?.kind?.bookLoose ?: false),
            "curved_opposite" to (page.finding?.curvedOpposite ?: false), "book_mode" to (page.book != null),
            "whole_photo" to (r?.kind?.wholePhoto ?: false),
            // the kind's own tone and whether it was used, and the photo mask in sharpening (#299)
            "kind_tone" to lab.kindTone, "kind_gamma" to (r?.kind?.extraGamma?.toDouble() ?: 0.0),
            "kind_s_curve" to (r?.kind?.sCurve ?: false), "kind_colour" to (r?.kind?.colourFactor?.toDouble() ?: 1.0),
            "photo_sharpen" to lab.photoSharpen, "suggestions" to lab.suggestions,
            // the turn and document type the page showed, kept for the PDF (#465)
            "background_pages" to lab.backgroundPages, "shown_turn_kept" to (shown[i]?.turns != null), "shown_preset_kept" to (shown[i]?.preset != null),
            "made_before_shown" to (pageQueue.madeBeforeShown(page)?.toString() ?: "none"),
            // fingers at the page's edge removed after Clean scan (#466)
            *io.github.artcraftandroid.pdfcraft.ui.scan.scanFingersDiag(io.github.artcraftandroid.pdfcraft.ui.scan.scanRemovesFingers(lab, page.auto), io.github.artcraftandroid.pdfcraft.ui.scan.ScanFingerCounts.of(r?.fingers)),
            // the marks made by hand (#495): counts, the share the eraser painted, the time
            *io.github.artcraftandroid.pdfcraft.ui.scan.scanMarksDiag(lab.markup, io.github.artcraftandroid.pdfcraft.ui.scan.scanMarksFor(page, lab), r?.markup?.erasedShare, r?.markup?.ms?.toLong()),
            // how the page was stored at the size chosen (#505)
            *io.github.artcraftandroid.pdfcraft.ui.scan.scanEncodingDiag(r?.encoding),
        )
        // what the automatic look decided for the page, one line each (#466)
        val decisions = io.github.artcraftandroid.pdfcraft.ui.scan.scanAutoDecisions(
            r?.kind?.preset, page.turns, r?.turns?.toInt() ?: 0, r?.flattened == true || r?.lineFlatten?.check?.kept == true,
            r?.flattenCheck?.why, page.flatten, autoFlatten, page.auto, io.github.artcraftandroid.pdfcraft.ui.scan.ScanFingerCounts.of(r?.fingers),
        )
        Diag.event(DiagArea.Scan, "auto_decisions", *decisions.diag(i, lab))
        if (keepIn != null) {
            val kept = r?.let {
                ScanTestResult(
                    it.width.toLong(), it.height.toLong(), it.format.name.lowercase(), it.dpi.toDouble(), it.paper.name.lowercase(),
                    it.aspectSource.name.lowercase(), it.focalMismatch, it.flattened, it.bend * 100.0, classifierTurns[i],
                    found[i]?.let { o -> ScanTestOrientation(o.quarterTurns.toInt(), o.confidence.toDouble(), o.agreement.toDouble(), o.tiles.toInt(), o.sure) },
                    it.noise?.toDouble(),
                    it.flattenCheck,
                    it.trim,
                    it.lineFlatten,
                    paperSpread = it.paperSpread?.toDouble(),
                    whitePoint = it.whitePoint?.toDouble(),
                    flattenReused = it.flattenReused,
                    flattenSkippedFlat = it.flattenSkippedFlat,
                )
            }
            val turn = ScanTestTurn(page.turns, autoUpright, r?.turns?.toInt() ?: 0, followed, turnSource)
            val kind = r?.kind?.let { k ->
                ScanTestKind(
                    k.preset, k.chosen, k.strength.toInt(), k.sharpen.toInt(), k.keepPaper, k.suggestFlatten, k.suggestGreyscale, k.ratio.toDouble(),
                    k.inkShare.toDouble(), k.colourInkShare.toDouble(), k.photoShare.toDouble(), k.strokeMm.toDouble(), k.brightShare.toDouble(), k.gutterBand, k.wholePhoto,
                    k.gutterLoose, k.gutterCover.toDouble(), k.photoLoose.toDouble(), k.bookLoose, k.curvedOpposite, k.bookMode,
                    k.extraGamma.toDouble(), k.sCurve, k.colourFactor.toDouble(),
                )
            }
            withContext(Dispatchers.IO) { scanLab.keepPage(keepIn, i, page, true, scanLookWord(filter, lab), strength, flatten, r?.path?.let(::File), kept, scanPreviewRecord(page), turn, kind, r?.let { scanAppliedStrength(page.look, lab, it.kind?.strength?.toInt()) }, shown[i]?.json(), gallerySaved = page.key in scanGallerySaved, autoDecisions = decisions.json(), fromRecent = scanRecent.openedFrom != null, erasedShare = r?.markup?.erasedShare?.toDouble(), madeFullBeforeSave = io.github.artcraftandroid.pdfcraft.ui.scan.scanMadeFullBeforeSave(ahead, readyAtTap[i]), encoding = io.github.artcraftandroid.pdfcraft.ui.scan.scanEncodingJson(r?.encoding), viewerSource = editing?.source) }
        }
        saved += scanSavedPage(page.file.absolutePath, r?.let { it.path to it.dpi })
    }
    if (keepIn != null) {
        withContext(Dispatchers.IO) { scanLab.end(keepIn) }
        scanLab.refreshKept()
    }
    // the pages' own bytes as stored (#505): a camera file kept as it was counts its file
    val pagesBytes = saved.sumOf { p -> runCatching { File(p.path).length() }.getOrDefault(0L) }
    // pages whose processing failed went in as the camera took them; the message counts them (#301)
    val unenhanced = io.github.artcraftandroid.pdfcraft.ui.scan.scanUnenhancedCount(made.map { it != null })
    val created = try {
        eng.tool("doc_create", scanCreateArgs(saved))
    } finally {
        // the PDF holds the pages now (pages made ahead stay until the scan ends, #504)
        dir.listFiles()?.forEach { it.delete() }
    }
    val id = created.optLong("doc")
    try {
        if (Diag.enabled()) {
            // the straightened pages' proportions (A4 is 1.414; the gate is within 2%)
            eng.tool("doc_info", mapOf("doc" to id)).optJSONArray("pages")?.let { out ->
                for (i in 0 until out.length()) {
                    val p = out.optJSONObject(i) ?: continue
                    val (pw, ph) = p.optDouble("width") to p.optDouble("height")
                    if (pw > 0 && ph > 0) Diag.event(DiagArea.Scan, "page_size", "index" to i + 1, "width_pt" to pw, "height_pt" to ph, "ratio" to maxOf(pw, ph) / minOf(pw, ph))
                }
            }
        }
        // the scan's words become text, searchable and selectable, only when the person asked for
        // it in Settings ▸ Scanner (#504); Skip saves the PDF without the pages not read yet
        // Extract text (#516) always reads the pages; Print never waits for it
        val ocrOn = when (purpose) {
            io.github.artcraftandroid.pdfcraft.ui.scan.ScanOut.Text -> true
            io.github.artcraftandroid.pdfcraft.ui.scan.ScanOut.Print -> false
            else -> io.github.artcraftandroid.pdfcraft.ui.scan.ScanOcrSetting.get(settings.appPrefs)
        }
        var ocrAvailable = false
        var ocrSkipped = false
        val ocrStarted = android.os.SystemClock.elapsedRealtime()
        if (ocrOn) {
            withContext(Dispatchers.IO) { runCatching { eng.prepareOcrModels(getApplication()) } }
            ocrAvailable = runCatching { eng.tool("ocr_status") }.getOrNull()?.optBoolean("available", false) == true
        }
        if (ocrOn && ocrAvailable) {
            tools.busyCancel = { ocrSkipped = true }
            tools.busyCancelLabel = text(R.string.busy_skip)
            tools.busyCancelNote = text(R.string.busy_skip_ocr)
            try {
                for (n in 1..pages.size) {
                    if (ocrSkipped) break
                    tools.busy = text(R.string.msg_busy_recognizing_page, n, pages.size)
                    tools.busyProgress = n - 1 to pages.size
                    runCatching { eng.tool("ocr_recognize", mapOf("doc" to id, "pages" to listOf(n))) }
                        .onFailure { Diag.failure(DiagArea.Scan, "ocr", it) }
                }
            } finally {
                tools.busyCancel = null
                tools.busyCancelLabel = null
                tools.busyCancelNote = null
                tools.busyProgress = null
            }
            tools.busy = text(R.string.msg_busy_saving_scan)
        }
        val ocr = io.github.artcraftandroid.pdfcraft.ui.scan.scanOcrOutcome(ocrOn, ocrAvailable, ocrSkipped)
        val ocrMs = if (ocrOn && ocrAvailable) android.os.SystemClock.elapsedRealtime() - ocrStarted else 0L
        if (purpose == io.github.artcraftandroid.pdfcraft.ui.scan.ScanOut.Text) {
            scanTextOut(eng, id, pages.size, ocrAvailable, ocrSkipped, android.os.SystemClock.elapsedRealtime() - scanStarted)
            return
        }
        // a scan of its own that is kept names its kept scan in the PDF, so a tap on a page in the
        // viewer can open it again (#513); a share or pages going into an open document are not kept
        val keepsRecent = !share && insertScanAt == null && scanRecent.on()
        val linkId = if (keepsRecent) scanRecent.idForSave() else null
        val linked = linkId != null && editing == null && scanLab.options.editFromViewer &&
            runCatching { eng.tool("doc_set_info", mapOf("doc" to id, "key" to io.github.artcraftandroid.pdfcraft.ui.scan.SCAN_LINK_KEY, "value" to linkId)) }
                .onFailure { Diag.failure(DiagArea.Scan, "scan_link", it) }.isSuccess
        val out = tempFile("pdf")
        eng.tool("doc_save", mapOf("doc" to id, "path" to out.absolutePath, "full" to true))
        // from the tap to the file (#504)
        val waitDiag = io.github.artcraftandroid.pdfcraft.ui.scan.scanSaveWaitDiag(ahead, readyAtTap.count { it }, android.os.SystemClock.elapsedRealtime() - scanStarted, ocr, ocrMs) +
            // made ahead whatever the size, stored ahead at this size, and the making done at Save (#528)
            io.github.artcraftandroid.pdfcraft.ui.scan.scanSizesAheadDiag(lab.sizesAhead, madeAhead, readyAtTap.count { it }, madeMs.sum()) +
            // how many pages Save made at once (#549)
            io.github.artcraftandroid.pdfcraft.ui.scan.scanSaveLanesDiag(lanes, mostAtOnce)
        if (purpose == io.github.artcraftandroid.pdfcraft.ui.scan.ScanOut.Print) {
            // the print screen reads the copy and deletes it when the job is done (#516); the scan stays as it is
            Diag.event(DiagArea.Scan, "print", "pages" to pages.size, "bytes" to out.length(), "ms" to android.os.SystemClock.elapsedRealtime() - scanStarted)
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US).format(java.util.Date())
            prints.trySend(PrintJob(out, DocumentFiles.safeName(scanName.trim().ifEmpty { "Scan $stamp" }), deleteWhenDone = true))
            if (unenhanced > 0) tell(text(R.string.msg_scan_unenhanced, unenhanced))
            return
        }
        if (share) {
            // the scan stays as it is; the copy handed out is swept from the share folder later
            Diag.event(DiagArea.Scan, "share", *io.github.artcraftandroid.pdfcraft.ui.scan.scanShareDiag(pages.size, out.length(), android.os.SystemClock.elapsedRealtime() - scanStarted), *waitDiag, *io.github.artcraftandroid.pdfcraft.ui.scan.scanSavedSizeDiag(sizeChoice, out.length(), pages.size, pagesBytes))
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US).format(java.util.Date())
            val name = DocumentFiles.safeName(scanName.trim().ifEmpty { "Scan $stamp" })
            val uri = try {
                withContext(Dispatchers.IO) { files.shareable(out, name) }
            } finally {
                out.delete()
            }
            if (unenhanced > 0) tell(text(R.string.msg_scan_unenhanced, unenhanced))
            intents.trySend(android.content.Intent.createChooser(sharing.sendIntent(listOf(uri)).putExtra(android.content.Intent.EXTRA_SUBJECT, name.removeSuffix(".pdf")), null))
            return
        }
        // the scan stays as it is (pages, photos, steps, name) until the place the PDF goes to has it (#787)
        val target = insertScanAt
        val chosen = scanName.trim()
        val unenhancedNote = if (unenhanced > 0) " " + text(R.string.msg_scan_unenhanced, unenhanced) else ""
        val bytes = out.length()
        // where the pages are going, for the line of a save that fails
        var going = when {
            target != null -> io.github.artcraftandroid.pdfcraft.ui.scan.ScanDestination.Insert
            editing != null && editedPages != null -> io.github.artcraftandroid.pdfcraft.ui.scan.ScanDestination.Replace
            else -> io.github.artcraftandroid.pdfcraft.ui.scan.ScanDestination.Downloads
        }
        val done = io.github.artcraftandroid.pdfcraft.ui.scan.scanCommitSave(
            write = {
                if (target != null) {
                    // the open document was closed meanwhile: the pages have nowhere to go
                    if (document !== target.first && tabs.tabs.none { it.doc === target.first }) throw io.github.artcraftandroid.pdfcraft.ui.scan.ScanDestinationGone()
                    toolNow(eng, target.first, "page_insert_file", mapOf("path" to out.absolutePath, "at" to target.second))
                    ScanSaveDone(io.github.artcraftandroid.pdfcraft.ui.scan.ScanDestination.Insert)
                } else {
                    // the changed pages go back into the PDF they came from (#513)
                    val put = if (editing != null && editedPages != null) scanEdit.putBack(eng, editing, editedPages, out) else null
                    if (put != null && put != io.github.artcraftandroid.pdfcraft.ui.scan.ScanPutBack.Closed) {
                        ScanSaveDone(io.github.artcraftandroid.pdfcraft.ui.scan.ScanDestination.Replace, put = put)
                    } else {
                        going = io.github.artcraftandroid.pdfcraft.ui.scan.ScanDestination.Downloads
                        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US).format(java.util.Date())
                        val name = DocumentFiles.safeName(chosen.ifEmpty { "Scan $stamp" })
                        val uri = withContext(Dispatchers.IO) { files.saveToDownloads(out, name) }
                        ScanSaveDone(io.github.artcraftandroid.pdfcraft.ui.scan.ScanDestination.Downloads, uri = uri, name = name)
                    }
                }
            },
            onFailed = { e ->
                // nothing of the scan was touched: it can be saved again
                val cause = (e as? io.github.artcraftandroid.pdfcraft.ui.scan.ScanReplaceFailed)?.error ?: e
                Diag.failure(DiagArea.Scan, "save_destination", cause)
                Diag.event(
                    DiagArea.Scan, "save_failed",
                    *io.github.artcraftandroid.pdfcraft.ui.scan.scanSaveFailedDiag(going, io.github.artcraftandroid.pdfcraft.ui.scan.scanSaveFailureReason(going, e), pages.size, draftKept = true, ms = android.os.SystemClock.elapsedRealtime() - scanStarted),
                )
                when {
                    e is io.github.artcraftandroid.pdfcraft.ui.scan.ScanDestinationGone -> {
                        // the next Save makes a PDF of its own
                        insertScanAt = null
                        fail(text(R.string.scan_insert_closed))
                    }
                    e is io.github.artcraftandroid.pdfcraft.ui.scan.ScanReplaceFailed && !e.rolledBack -> fail(text(R.string.scan_edit_partial))
                    else -> fail(cause.userMessage + "\n" + text(R.string.scan_save_kept))
                }
            },
            onWritten = { landed ->
                val notWritten = landed.put == io.github.artcraftandroid.pdfcraft.ui.scan.ScanPutBack.NotWritten
                if (notWritten) {
                    // the pages are in the open PDF, whose file refused the write: not a saved scan, and the
                    // scan ends as before, since the viewer keeps the change and offers Save a copy (#491)
                    Diag.event(
                        DiagArea.Scan, "save_failed",
                        *io.github.artcraftandroid.pdfcraft.ui.scan.scanSaveFailedDiag(landed.destination, "not_written", pages.size, draftKept = false, ms = android.os.SystemClock.elapsedRealtime() - scanStarted),
                    )
                } else {
                    Diag.event(
                        DiagArea.Scan, "saved", "pages" to pages.size, "filter" to pages.first().look.filter, "bytes" to bytes,
                        // how many pages have a look of their own, and how many went in as photographed (#301)
                        "looks_mixed" to io.github.artcraftandroid.pdfcraft.ui.scan.scanLooksMixed(pages.map { it.look }), "unenhanced" to unenhanced,
                        "untouched_pages" to pages.count { it.found != null && it.corners.contentEquals(it.found) },
                        "found_pages" to pages.count { it.found != null }, "ms" to android.os.SystemClock.elapsedRealtime() - scanStarted,
                        *waitDiag,
                        // the size chosen and what it came to (#505)
                        *io.github.artcraftandroid.pdfcraft.ui.scan.scanSavedSizeDiag(sizeChoice, bytes, pages.size, pagesBytes),
                        // where the pages went, said once they were there (#787)
                        "destination" to landed.destination.id,
                    )
                }
                if (editing == null) Diag.event(DiagArea.Scan, "scan_link", *io.github.artcraftandroid.pdfcraft.ui.scan.scanLinkDiag(linked, pages.size))
                // kept to be edited again (#461): a scan of its own (not pages going into an open
                // document), with the switch in Settings and the testing option on; else the photos go
                val recent = if (target == null && scanRecent.on()) {
                    ScanSession(null, null, false, chosen, pages.map { ScanSession.Page(it.file.absolutePath, it.corners, it.found, it.turns, it.preset, it.book?.encode(), it.look, it.flatten, it.auto, it.marks) })
                } else null
                if (recent != null) {
                    // the first page as made, for the Recent scans list's picture (#494)
                    val cover = pages.firstOrNull()?.let { pageQueue.readyOf(it)?.file }
                    withContext(Dispatchers.IO) { scanRecent.keep(recent, cover, linkId ?: scanRecent.openedFrom) }
                    // the kept scan knows the file it became (#513)
                    val uri = landed.uri
                    if (linkId != null && uri != null) withContext(Dispatchers.IO) { scanRecent.link(linkId, uri.toString(), pages.size) }
                } else {
                    scanRecent.openedFrom = null
                    pages.forEach { it.file.delete() }
                }
                // a scan starts with no category and is never offered for Study (#560)
                landed.uri?.let { study.markScanMade(it.toString()) }
            },
            onAuxFailed = { e -> Diag.failure(DiagArea.Scan, "save_after", e) },
            clear = {
                insertScanAt = null
                scanRecent.openedFrom = null
                releaseScanPreviews()
                scanPages.clear()
                scanSteps.reset()
                scanBatch.borders = null
                scanBatch.look = null
                scanBatch.offer = 0
                scanBatch.asking = null
                scanGallerySaved.clear()
                scanName = ""
                tools.toolScreen = null
            },
        )
        // the copy made for it is not needed either way: a scan saved again is made again
        out.delete()
        if (done == null) return
        val uri = done.uri
        if (uri != null) {
            tell(if (editing != null) text(R.string.scan_edit_closed) else text(R.string.msg_saved_downloads, done.name) + unenhancedNote)
            open(uri)
        } else if (unenhanced > 0) {
            tell(unenhancedNote.trim())
        }
    } finally {
        runCatching { eng.tool("doc_close", mapOf("doc" to id, "discard_changes" to true)) }
    }
}

/** Where a saved scan went (#787): the new file's address and name in Downloads, or how the pages went back into their PDF. */
private class ScanSaveDone(
    val destination: io.github.artcraftandroid.pdfcraft.ui.scan.ScanDestination,
    val uri: android.net.Uri? = null,
    val name: String = "",
    val put: io.github.artcraftandroid.pdfcraft.ui.scan.ScanPutBack? = null,
)

/**
 * Extract text (#516): the words of scan document [id] ([pages] pages, read just now) handed to
 * Android's share sheet as text, where Copy is one of the choices. The scan stays as it is.
 */
private suspend fun AppViewModel.scanTextOut(eng: io.github.artcraftandroid.pdfcraft.engine.Engine, id: Long, pages: Int, available: Boolean, skipped: Boolean, ms: Long) {
    if (!available) {
        Diag.event(DiagArea.Scan, "extract_text", "pages" to pages, "available" to false, "ms" to ms)
        fail(text(R.string.msg_ocr_not_installed))
        return
    }
    val read = runCatching { eng.tool("text_extract", mapOf("doc" to id)) }.onFailure { Diag.failure(DiagArea.Scan, "extract_text", it) }.getOrNull()
    val texts = read?.optJSONArray("pages")?.let { a -> (0 until a.length()).map { a.optJSONObject(it)?.optString("text").orEmpty().trim() } }.orEmpty()
    val words = io.github.artcraftandroid.pdfcraft.ui.scan.scanTextJoin(texts)
    // counts only: the words themselves never go into the log
    Diag.event(DiagArea.Scan, "extract_text", "pages" to pages, "available" to true, "skipped" to skipped, "pages_with_text" to texts.count { it.isNotEmpty() }, "chars" to words.length, "ms" to ms)
    if (words.isBlank()) {
        tell(text(R.string.scan_text_none))
        return
    }
    val name = scanName.trim().ifEmpty { text(R.string.common_tool_scan) }
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_TEXT, io.github.artcraftandroid.pdfcraft.ui.scan.scanTextForIntent(words))
        .putExtra(android.content.Intent.EXTRA_SUBJECT, name)
    intents.trySend(android.content.Intent.createChooser(send, null))
}
