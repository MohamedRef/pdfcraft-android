package io.github.artcraftandroid.pdfcraft.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.engine.Engine
import io.github.artcraftandroid.pdfcraft.plural
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanEditPage
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanEditRefusal
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanReplacePlan
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanReplaceStep
import io.github.artcraftandroid.pdfcraft.ui.scan.scanCommentsOnMoved
import io.github.artcraftandroid.pdfcraft.ui.scan.scanEditRefusal
import io.github.artcraftandroid.pdfcraft.ui.scan.scanEditSignature
import io.github.artcraftandroid.pdfcraft.ui.scan.scanLinkId
import io.github.artcraftandroid.pdfcraft.ui.scan.scanReplaceDiag
import io.github.artcraftandroid.pdfcraft.ui.scan.scanReplacePlan
import io.github.artcraftandroid.pdfcraft.ui.scan.scanViewerEditDiag
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A saved scan edited from its PDF in the viewer (#513). The PDF names the kept scan it was made
 * from (`PdfCraftScan` in its document information); a tap on one of its pages with no tool out
 * shows the page alone with Edit; Edit opens the kept scan on that page in Enhance, with every
 * screen a new scan has; Done puts the pages that changed back into the same PDF and saves it.
 * Testing option `editFromViewer`.
 */
internal class ScanViewerEditController(private val vm: AppViewModel) {
    /**
     * The PDF a scan on screen was opened from: its document, the kept scan, and the pages as they
     * were opened, in the PDF's order. Null for a scan that was not opened from the viewer.
     */
    class Target(val doc: OpenDocument, val scanId: String, val before: List<ScanEditPage>, val source: String) {
        /** The answer to "Keep the comments on changed pages?": null while not asked. */
        var removeComments: Boolean? = null
    }

    var target by mutableStateOf<Target?>(null)
        private set

    /** The page shown alone after a tap in the viewer (0-based), and its document. */
    var shown by mutableStateOf<Pair<OpenDocument, Int>?>(null)

    /** "Keep the comments on changed pages?" before Done: how many comments, and the size chosen at Save. */
    class CommentsAsk(val comments: Int, val size: String?)

    var asking by mutableStateOf<CommentsAsk?>(null)
        private set

    /** Whether the option is on. */
    private val on: Boolean get() = vm.scanLab.options.editFromViewer

    /** Read which kept scan [doc] names, once its information is read. */
    suspend fun load(doc: OpenDocument) {
        if (!on || doc.info.preparing) return
        val eng = vm.engine ?: return
        val props = runCatching { eng.command { fileProperties(doc.id) } }.getOrNull() ?: return
        doc.scanLink = scanLinkId(props.customMetadata.map { it.name to it.value })
    }

    /** A tap on [page] (0-based) of [doc] with no tool out: true when it showed the page alone. */
    fun tapPage(doc: OpenDocument, page: Int): Boolean {
        if (!on || doc.scanLink == null) return false
        shown = doc to page
        return true
    }

    /** Edit on the page shown alone: open the kept scan on it, or say why it can't be. */
    fun edit(doc: OpenDocument, page: Int) {
        val id = doc.scanLink
        vm.viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            val kept = id?.let { withContext(Dispatchers.IO) { vm.scanRecent.pagesOf(it) } }
            val refusal = scanEditRefusal(on, id, kept != null, kept?.first ?: 0, doc.pageCount, vm.scanPages.isNotEmpty())
            if (refusal != null || id == null || kept == null) {
                Diag.event(DiagArea.Scan, "viewer_edit_refused", "why" to (refusal?.id ?: "unknown"), "index" to page + 1)
                when (refusal) {
                    ScanEditRefusal.Gone -> vm.tell(text(R.string.scan_edit_gone))
                    ScanEditRefusal.PagesChanged -> vm.tell(text(R.string.scan_edit_pages_changed))
                    ScanEditRefusal.Busy -> {
                        vm.tell(text(R.string.scan_recent_busy))
                        shown = null
                        vm.tools.toolScreen = ToolScreen.Scan
                    }
                    else -> Unit
                }
                return@launch
            }
            val opened = withContext(Dispatchers.IO) { vm.scanRecent.openCopy(id) }
            if (opened == null || opened.session.pages.isEmpty() || opened.missing > 0) {
                // a page whose photo is gone can't be made again: the PDF stays as it is
                opened?.session?.pages?.forEach { File(it.path).delete() }
                vm.tell(text(R.string.scan_recent_unreadable))
                return@launch
            }
            shown = null
            vm.restoreScan(opened.session.encode(), opened.session).join()
            vm.scanRecent.openedFrom = id
            vm.scanName = opened.session.name
            target = Target(doc, id, vm.scanPages.map(::editPage), "kept")
            Diag.event(DiagArea.Scan, "viewer_edit_scan", *scanViewerEditDiag(page + 1, "kept", opened.savedAt, System.currentTimeMillis()), "pages" to vm.scanPages.size, "ms" to SystemClock.elapsedRealtime() - started)
            // Enhance on the page tapped; its Done and Back lead to the scan's pages
            vm.scanShowAt = page.coerceIn(0, vm.scanPages.lastIndex)
            vm.tools.toolScreen = ToolScreen.Scan
        }
    }

    /** The scan ended without Done: the PDF stays as it is. */
    fun dropped() {
        target = null
        asking = null
    }

    /**
     * Before Save: true when the person must first answer whether comments on pages that moved
     * stay ([asking] is then set); [size] is the size chosen, kept for the answer.
     */
    fun mustAsk(size: String?): Boolean {
        val t = target ?: return false
        if (t.removeComments != null || vm.document !== t.doc) return false
        val plan = scanReplacePlan(t.before, vm.scanPages.map(::editPage))
        val count = scanCommentsOnMoved(t.doc.comments.map { it.page.toInt() + 1 }, plan.moved)
        if (count == 0) return false
        asking = CommentsAsk(count, size)
        return true
    }

    /** The answer to the comments question: Save goes on with it. */
    fun answer(remove: Boolean) {
        val ask = asking ?: return
        asking = null
        target?.removeComments = remove
        Diag.event(DiagArea.Scan, "scan_replace_comments", "comments" to ask.comments, "removed" to remove)
        vm.saveScan(size = ask.size)
    }

    /** The answer was not given (the dialog was closed): nothing is saved. */
    fun cancelAsk() {
        asking = null
    }

    /** The pages as they are now, for the plan at Done. */
    fun pagesNow(): List<ScanEditPage> = vm.scanPages.map(::editPage)

    /**
     * Put the scan's pages, written to [out], back into the PDF it was opened from, and save it.
     * False when that PDF is no longer the one open: the caller then saves a new file.
     */
    suspend fun putBack(eng: Engine, t: Target, after: List<ScanEditPage>, out: File): Boolean {
        target = null
        val doc = t.doc
        if (vm.document !== doc) {
            Diag.event(DiagArea.Scan, "scan_replace_failed", "reason" to "closed")
            return false
        }
        val started = SystemClock.elapsedRealtime()
        val plan: ScanReplacePlan = scanReplacePlan(t.before, after)
        val bytesBefore = doc.info.fileSize.toLong()
        val commentsOnPages = scanCommentsOnMoved(doc.comments.map { it.page.toInt() + 1 }, plan.moved)
        val remove = t.removeComments == true
        if (plan.empty) {
            Diag.event(DiagArea.Scan, "scan_replace", *scanReplaceDiag(plan, bytesBefore, bytesBefore, commentsOnPages, false, SystemClock.elapsedRealtime() - started))
            vm.tell(text(R.string.scan_edit_nothing))
            return true
        }
        try {
            if (remove) {
                // the last first, so the indexes still to delete stay right
                val moved = plan.moved.toSet()
                doc.comments.filter { it.page.toInt() + 1 in moved }.sortedWith(compareByDescending<io.github.artcraftandroid.pdfcraft.core.CommentInfo> { it.page }.thenByDescending { it.index })
                    .forEach { c -> eng.tool("comment_delete", mapOf("doc" to doc.id, "page" to c.page.toInt() + 1, "index" to c.index.toInt() + 1)) }
            }
            for (step in plan.steps) when (step) {
                is ScanReplaceStep.Replace -> eng.tool("page_replace", mapOf("doc" to doc.id, "pages" to step.pages, "path" to out.absolutePath, "from_pages" to step.from))
                is ScanReplaceStep.Insert -> eng.tool("page_insert_file", mapOf("doc" to doc.id, "path" to out.absolutePath, "at" to step.at, "pages" to step.from))
                is ScanReplaceStep.Delete -> eng.tool("page_delete", mapOf("doc" to doc.id, "pages" to step.pages))
            }
            vm.refresh(doc)
        } catch (e: Exception) {
            Diag.failure(DiagArea.Scan, "scan_replace", e)
            Diag.event(DiagArea.Scan, "scan_replace_failed", "reason" to "tool", "pages" to plan.changed.size)
            vm.refresh(doc)
            throw e
        }
        // into the file it came from; one that can't be written says so and offers Save a copy (#491)
        val written = vm.save(doc, trigger = "scan_replace")
        Diag.event(
            DiagArea.Scan, "scan_replace",
            *scanReplaceDiag(plan, bytesBefore, doc.info.fileSize.toLong(), commentsOnPages, remove, SystemClock.elapsedRealtime() - started),
            "written" to written,
        )
        if (!written) Diag.event(DiagArea.Scan, "scan_replace_failed", "reason" to "write", "pages" to plan.changed.size)
        doc.source?.let { vm.scanRecent.link(t.scanId, it.toString(), doc.pageCount) }
        val n = plan.changed.size + plan.steps.filterIsInstance<ScanReplaceStep.Insert>().sumOf { it.from.size }
        if (written) vm.tell(plural(R.plurals.scan_edit_replaced, n, n))
        plan.changed.firstOrNull()?.let { doc.viewer.goToPage(it - 1) }
        return true
    }

    private fun editPage(p: ScanPage): ScanEditPage {
        val line = ScanSession.Page(p.file.path, p.corners, p.found, p.turns, p.preset, p.book?.encode(), p.look, p.flatten, p.auto, p.marks)
        return ScanEditPage(p.key, scanEditSignature(line), p.corners.copyOf())
    }
}
