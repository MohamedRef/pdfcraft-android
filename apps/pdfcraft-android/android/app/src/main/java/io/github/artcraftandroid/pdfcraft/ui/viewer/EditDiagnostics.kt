package io.github.artcraftandroid.pdfcraft.ui.viewer

import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument

/**
 * One `[edit]` line in the diagnostics log about [doc]: a fixed event name with counts, kinds and
 * times from the content-editing tools (#161 #162 #163 #164 #166 #167 #171 #182). Never the words,
 * names or text the user typed or the document holds; see docs/ANDROID_DIAGNOSTICS_LOG.md.
 */
internal fun editEvent(doc: OpenDocument, name: String, vararg fields: Pair<String, Any?>) {
    if (Diag.enabled()) Diag.event(DiagArea.Edit, name, "doc" to doc.diagNumber, *fields)
}

/** Times [block] as an `[edit]` line (`ms`, `ok`), also when it throws. */
internal inline fun <T> editMeasure(doc: OpenDocument, name: String, block: () -> T): T =
    Diag.measure(DiagArea.Edit, name, "doc" to doc.diagNumber, block = block)

/** How a redaction was marked, from its redact_mark arguments: never the words or expression. */
internal fun redactHow(args: Map<String, Any?>): String = when {
    args["whole_pages"] == true -> "page"
    "rect" in args -> "area"
    "pattern" in args -> "pattern"
    "regex" in args -> "regex"
    "words" in args -> "words"
    else -> "phrase"
}
