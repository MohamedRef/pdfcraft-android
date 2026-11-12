package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.plural
import io.github.artcraftandroid.pdfcraft.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent
import io.github.artcraftandroid.pdfcraft.ui.viewer.PagePoint
import kotlinx.coroutines.flow.first
import io.github.artcraftandroid.pdfcraft.core.MobileException

class LinkMeasureController internal constructor(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()

    // ---- Link authoring (#179, #159) ---------------------------------------------------------

    /** A link being made or changed in its sheet. */
    var linkEdit by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.LinkEdit?>(null)

    /** In Link mode, a tap on an existing link opens it to change or delete; returns whether there was one. */
    fun editLinkAt(doc: OpenDocument, at: PagePoint) {
        val link = doc.links[at.page]?.lastOrNull { l -> l.rect.size == 4 && at.x in l.rect[0]..l.rect[2] && at.y in l.rect[1]..l.rect[3] }
        if (link == null) {
            vm.tell(text(R.string.link_drag_hint))
            return
        }
        val eng = vm.engine ?: return
        vm.viewModelScope.launch {
            val listed = runCatching { eng.tool("link_list", mapOf("doc" to doc.id)) }.getOrNull()?.optJSONArray("links")
            val rows = (0 until (listed?.length() ?: 0)).mapNotNull { i ->
                val o = listed!!.optJSONObject(i) ?: return@mapNotNull null
                val r = o.optJSONArray("rect") ?: return@mapNotNull null
                Triple(o.optInt("page"), o.optInt("index"), (0 until r.length()).map { r.optDouble(it).toFloat() })
            }
            val index = io.github.artcraftandroid.pdfcraft.ui.viewer.linkIndexFor(rows, at.page, link.rect)
            if (index == null || vm.document !== doc) {
                vm.tell(text(R.string.link_not_editable))
                return@launch
            }
            linkEdit = io.github.artcraftandroid.pdfcraft.ui.viewer.LinkEdit(
                doc, at.page, link.rect.toFloatArray(), index, link.uri, link.page?.toInt() ?: link.file?.let { link.filePage.toInt() }, file = link.file,
            )
        }
    }

    /**
     * Make the link in [e] go to [url], page [toPage] (0-based), or page [toPage] of the PDF named
     * [file] (#334): a new link, or a change to it.
     */
    fun saveLink(e: io.github.artcraftandroid.pdfcraft.ui.viewer.LinkEdit, url: String?, toPage: Int?, file: String? = null) {
        linkEdit = null
        val target: Map<String, Any?> = when {
            url != null -> mapOf("url" to url)
            file != null -> mapOf("file" to file, "to_page" to (toPage ?: 0) + 1)
            else -> mapOf("to_page" to (toPage ?: return) + 1)
        }
        val kind = if (file != null) "file" else if (url == null) "page" else if (url.startsWith("mailto:")) "mail" else "web"
        // a link to another PDF opens its page fitted: the engine takes no zoom with it
        val zoom: Map<String, Any?> = if (file == null) mapOf("zoom" to "inherit") else emptyMap()
        if (e.index == null) {
            vm.runTool(e.doc, "link_add", mapOf("page" to e.page + 1, "rect" to e.rect.map { it.toDouble() }) + zoom + target) {
                io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.link(e.doc, "add", kind, e.from)
                vm.tell(text(R.string.link_added))
            }
        } else {
            vm.runTool(e.doc, "link_edit", mapOf("page" to e.page + 1, "index" to e.index) + target) { io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.link(e.doc, "edit", kind, e.from) }
        }
    }

    fun deleteLink(e: io.github.artcraftandroid.pdfcraft.ui.viewer.LinkEdit) {
        linkEdit = null
        val index = e.index ?: return
        vm.runTool(e.doc, "link_delete", mapOf("page" to e.page + 1, "index" to index)) { io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.link(e.doc, "delete", null, e.from); vm.tell(text(R.string.link_deleted)) }
    }

    /** Make every web address written on the pages a link, and say how many (one undo step). */
    fun linksFromUrls(doc: OpenDocument) {
        val eng = vm.engine ?: return
        vm.viewModelScope.launch {
            // the engine refuses when there is nothing to link: that is an answer, not a failure
            val start = android.os.SystemClock.elapsedRealtime()
            val r = try { eng.tool("links_from_urls", mapOf("doc" to doc.id)) } catch (e: MobileException) { null }
            vm.refresh(doc)
            val n = r?.optJSONArray("created")?.length() ?: 0
            io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.linksFromUrls(doc, n, android.os.SystemClock.elapsedRealtime() - start)
            vm.tell(if (n == 0) text(R.string.link_from_urls_none) else plural(R.plurals.link_from_urls_done, n, n))
        }
    }

    /** Remove every link in the document (one undo step). */
    fun removeAllLinks(doc: OpenDocument) {
        vm.runTool(doc, "links_remove") { io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.linksRemoved(doc); vm.tell(text(R.string.link_removed_all)) }
    }

    // ---- Measure (#135, #180) ------------------------------------------------------------------

    internal var measure: io.github.artcraftandroid.pdfcraft.ui.viewer.MeasureSession? = null

    /** The measurement being placed on [doc] (one per document, kept while the tool is open). */
    fun measureSession(doc: OpenDocument): io.github.artcraftandroid.pdfcraft.ui.viewer.MeasureSession =
        measure?.takeIf { it.doc === doc } ?: io.github.artcraftandroid.pdfcraft.ui.viewer.MeasureSession(doc).also { measure = it }

    fun setMeasureMode(m: io.github.artcraftandroid.pdfcraft.ui.viewer.MeasureSession, mode: io.github.artcraftandroid.pdfcraft.ui.viewer.MeasureMode) {
        m.calibrating = false
        if (m.mode != mode) m.clear()
        m.mode = mode
    }

    /**
     * A tap in Measure at [at]: the point snaps to the drawing's nearest endpoint, midpoint,
     * intersection or line (within about 8 dp), then the reading updates. A distance is saved
     * at its second point; a perimeter or area with Done.
     */
    fun measureTap(m: io.github.artcraftandroid.pdfcraft.ui.viewer.MeasureSession, at: PagePoint) {
        val doc = m.doc
        if (m.page != null && m.page != at.page) return
        val eng = vm.engine ?: return
        vm.viewModelScope.launch {
            val tolerance = (8f * app.resources.displayMetrics.density / doc.viewer.pixelsPerPoint(at.page).coerceAtLeast(0.01f)).toDouble()
            val snapStart = android.os.SystemClock.elapsedRealtime()
            val snapped = runCatching {
                eng.tool("measure_snap", mapOf("doc" to doc.id, "page" to at.page + 1, "at" to listOf(at.x.toDouble(), at.y.toDouble()), "tolerance" to tolerance))
            }.getOrNull()?.optJSONObject("snap")?.optJSONArray("point")
            val point = if (snapped != null && snapped.length() == 2) androidx.compose.ui.geometry.Offset(snapped.optDouble(0).toFloat(), snapped.optDouble(1).toFloat())
            else androidx.compose.ui.geometry.Offset(at.x, at.y)
            m.page = at.page
            m.points.add(point)
            io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.measurePoint(doc, if (m.calibrating) "calibrate" else m.mode.kind, snapped != null && snapped.length() == 2, m.points.size, android.os.SystemClock.elapsedRealtime() - snapStart)
            if (m.calibrating) {
                if (m.points.size == 2) {
                    m.calibration = m.points[0] to m.points[1]
                }
                return@launch
            }
            updateReading(m)
            if (m.mode == io.github.artcraftandroid.pdfcraft.ui.viewer.MeasureMode.Distance && m.points.size >= 2) finishMeasurement(m)
        }
    }

    private suspend fun updateReading(m: io.github.artcraftandroid.pdfcraft.ui.viewer.MeasureSession) {
        val eng = vm.engine ?: return
        val page = m.page ?: return
        if (m.points.size < 2) {
            m.reading = null
            return
        }
        val r = runCatching {
            eng.tool("measure_info", mapOf("doc" to m.doc.id, "page" to page + 1, "type" to m.mode.kind, "points" to m.points.map { listOf(it.x.toDouble(), it.y.toDouble()) }))
        }.getOrNull()
        m.reading = r?.optJSONObject("reading")?.optString("label")?.takeIf { it.isNotBlank() }
    }

    fun undoMeasurePoint(m: io.github.artcraftandroid.pdfcraft.ui.viewer.MeasureSession) {
        if (m.points.isEmpty()) return
        m.points.removeAt(m.points.lastIndex)
        if (m.points.isEmpty()) m.clear()
        vm.viewModelScope.launch { updateReading(m) }
    }

    /** Save the measurement as a measurement annotation (readable on desktop), then start a new one. */
    fun finishMeasurement(m: io.github.artcraftandroid.pdfcraft.ui.viewer.MeasureSession) {
        val page = m.page ?: return
        if (m.points.size < m.mode.minPoints) return
        val label = m.reading
        val args = mapOf(
            "page" to page + 1, "points" to m.points.map { listOf(it.x.toDouble(), it.y.toDouble()) }, "author" to vm.commenting.author,
        )
        val kind = m.mode.kind
        val points = m.points.size
        m.clear()
        vm.runTool(m.doc, m.mode.tool, args) {
            io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.measureSaved(m.doc, kind, points)
            if (label != null) vm.tell(text(R.string.measure_saved, label))
        }
    }

    /** Set the page's scale to "1 : [ratio]" in [unit]. */
    fun setMeasureRatio(m: io.github.artcraftandroid.pdfcraft.ui.viewer.MeasureSession, ratio: Double, unit: String) {
        val page = m.page ?: m.doc.viewer.currentPage
        val args = io.github.artcraftandroid.pdfcraft.ui.viewer.ratioScaleArgs(page, ratio, unit) ?: return
        vm.runTool(m.doc, "measure_scale", args) { io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.measureScale(m.doc, "ratio", unit); vm.tell(text(R.string.measure_scale_set, page + 1)) }
    }

    /** The next two taps mark a length of known size. */
    fun startCalibration(m: io.github.artcraftandroid.pdfcraft.ui.viewer.MeasureSession) {
        m.clear()
        m.calibrating = true
        vm.tell(text(R.string.measure_calibrate_tap))
    }

    fun finishCalibration(m: io.github.artcraftandroid.pdfcraft.ui.viewer.MeasureSession, pts: Pair<androidx.compose.ui.geometry.Offset, androidx.compose.ui.geometry.Offset>, distance: Double, unit: String) {
        val page = m.page ?: return
        m.calibration = null
        m.calibrating = false
        m.clear()
        val args = io.github.artcraftandroid.pdfcraft.ui.viewer.calibrationScaleArgs(page, pts.first, pts.second, distance, unit) ?: return
        vm.runTool(m.doc, "measure_scale", args) { io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.measureScale(m.doc, "calibrate", unit); vm.tell(text(R.string.measure_scale_set, page + 1)) }
    }

    /** Every saved measurement as CSV (values, labels, authors and scales), through the share sheet. */
    fun exportMeasurements(doc: OpenDocument) = vm.job(text(R.string.measure_exporting)) { eng ->
        val out = vm.tempFile("csv")
        try {
            val r = eng.tool("measure_export", mapOf("doc" to doc.id, "out" to out.absolutePath))
            io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.measureExport(doc, r.optInt("count", 0))
            if (r.optInt("count", 0) == 0) {
                vm.tell(text(R.string.measure_export_none))
                return@job
            }
            val uri = withContext(Dispatchers.IO) { vm.files.shareable(out, "${doc.title} measurements.csv") }
            vm.intents.trySend(Intent.createChooser(vm.sharing.sendIntent(listOf(uri), "text/csv"), null))
        } finally {
            out.delete()
        }
    }
}
