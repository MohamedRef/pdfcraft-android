package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A comment the page dims while the comment list is filtered: its page and rectangle (points, top-left origin). */
data class DimmedComment(val page: Int, val rect: List<Float>)

/** The comments the filters leave out of [shown], with a rectangle to dim on the page (#155). */
fun <T> dimmedComments(threads: List<List<T>>, shown: List<List<T>>, page: (T) -> Int, rect: (T) -> List<Float>): List<DimmedComment> {
    val kept = shown.mapTo(HashSet()) { it.first() }
    return threads.mapNotNull { t ->
        val c = t.first()
        val r = rect(c)
        if (c in kept || r.size < 4) null else DimmedComment(page(c), r)
    }.sortedBy { it.page }
}

/** Veil each of [dimmed] on the pages in view, so the comments the list shows stand out (#155). */
fun DrawScope.drawDimmedComments(viewer: ViewerState, dimmed: List<DimmedComment>, night: Boolean = false) {
    if (dimmed.isEmpty()) return
    val visible = viewer.visiblePages()
    // the page's own colour, so the comments under it fade into the page
    val veil = if (night) Color(0xB3121212) else Color(0xB3F2F2F2)
    for (d in dimmed) {
        if (d.page !in visible) continue
        val a = viewer.pageToScreen(d.page, d.rect[0], d.rect[1])
        val b = viewer.pageToScreen(d.page, d.rect[2], d.rect[3])
        drawRect(veil, Offset(minOf(a.x, b.x) - 2f, minOf(a.y, b.y) - 2f), Size(kotlin.math.abs(b.x - a.x) + 4f, kotlin.math.abs(b.y - a.y) + 4f))
    }
}

/**
 * While the page dims comments the list's filters leave out: a chip saying so, whose tap shows
 * them all again (clears the filters).
 */
@androidx.compose.runtime.Composable
fun androidx.compose.foundation.layout.BoxScope.CommentFilterChip(doc: io.github.artcraftandroid.pdfcraft.ui.OpenDocument, top: androidx.compose.ui.unit.Dp) {
    val ctl = doc.controller
    if (ctl.dimmedComments.isEmpty() || ctl.dimmedGeneration != doc.info.generation.toLong() || doc.info.commentsHidden) return
    val c = io.github.artcraftandroid.pdfcraft.ui.pdfColors
    val label = androidx.compose.ui.res.stringResource(io.github.artcraftandroid.pdfcraft.R.string.comments_filtered_on_page)
    androidx.compose.foundation.layout.Row(
        androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopCenter)
            .padding(top = top + 10.dp)
            .shadow(3.dp, RoundedCornerShape(20.dp))
            .background(c.surface, RoundedCornerShape(20.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                ctl.commentFilters = CommentFilters()
                ctl.dimmedComments = emptyList()
            }
            .heightIn(min = 40.dp).padding(horizontal = 14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(label, color = c.text, fontSize = 14.sp)
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(10.dp))
        androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(io.github.artcraftandroid.pdfcraft.R.string.comments_show_all), color = c.accent, fontSize = 14.sp)
    }
}
