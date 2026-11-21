package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.ScanPage
import io.github.artcraftandroid.pdfcraft.ui.saveScan

/*
 * The review of pages whose borders need a check (#783) as the view model runs it: which pages
 * those are now, the question before Save PDF or Share stores one as it is, and the log lines of a
 * review. The pass itself is #515's (ScanBatchFlow.kt).
 */

/** The page's review state now. */
internal fun AppViewModel.scanReviewOf(page: ScanPage): ScanReviewState = scanPageReview(page, page.file.absolutePath in scanSteps.searched)

/** The pages that need a check, in the scan's order: by their review state with the review on, else #758's unsure pages. */
internal fun AppViewModel.scanReviewPages(): List<ScanPage> {
    val on = scanLab.options.reviewBorders
    return scanPages.filter { scanPageNeedsReview(it, it.file.absolutePath in scanSteps.searched, on) }
}

/** Whether the review's offer is shown in place of the offer after an import (#515). */
internal fun AppViewModel.scanReviewOfferShown(): Boolean {
    val lab = scanLab.options
    if (!lab.reviewBorders) return false
    return scanReviewOfferFirst(true, lab.checkUnsure, lab.batchBorders, lab.steps, scanReviewPages().map { it.key }.toSet(), scanBatch.unsureDismissed)
}

/** A review opened: its log line. */
internal fun AppViewModel.scanReviewOpened(pass: ScanBatchPass) {
    val pages = scanPages.filter { it.key in pass.keys }
    val states = pages.map { scanReviewOf(it) }
    val first = scanPages.indexOfFirst { it.key == pass.keys.firstOrNull() } + 1
    Diag.event(
        DiagArea.Scan, "review_open",
        *scanReviewOpenDiag(pass.keys.size, states.count { it == ScanReviewState.NotFound }, states.count { it == ScanReviewState.Failed }, first, scanPages.size, pass.from),
    )
}

/**
 * Save PDF or Share was pressed: with pages that still need a check, the question is put and true is
 * returned, and the save waits for [answerScanReviewSave]. Print and Extract text store nothing and do not ask.
 */
internal fun AppViewModel.scanReviewAsksSave(share: Boolean, size: String?, out: ScanOut): Boolean {
    if (out != ScanOut.Save && out != ScanOut.Share) return false
    val lab = scanLab.options
    val keys = scanReviewSaveAsks(lab.reviewBorders, lab.checkUnsure, lab.batchBorders, lab.steps, scanReviewPages().map { it.key }, scanBatch.saveAccepted)
    if (keys.isEmpty()) return false
    scanBatch.saveAsk = ScanReviewSaveAsk(keys, share, size, out)
    Diag.event(DiagArea.Scan, "review_save_ask", *scanReviewSaveDiag(keys.size, scanPages.size, out.id))
    return true
}

/**
 * The answer to Save's question: [check] opens the review on those pages and nothing is saved;
 * [save] stores them as they are and does not ask about them again; neither closes the question.
 */
internal fun AppViewModel.answerScanReviewSave(check: Boolean, save: Boolean) {
    val ask = scanBatch.saveAsk ?: return
    scanBatch.saveAsk = null
    val answer = if (check) "check" else if (save) "save" else "cancel"
    Diag.event(DiagArea.Scan, "review_save_answer", *scanReviewSaveDiag(ask.keys.size, scanPages.size, ask.out.id, answer))
    when {
        check -> startScanBatch(ScanBatchWhat.Borders, ask.keys.toSet(), "save")
        save -> {
            scanBatch.saveAccepted = scanBatch.saveAccepted + ask.keys
            saveScan(share = ask.share, size = ask.size, out = ask.out)
        }
    }
}
