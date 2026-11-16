package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * What the picture of one finished draft depends on: the stroke itself and what it is drawn from.
 * A change in any of it (points cut by the eraser or moved by the lasso, the end taper, a new
 * colour) and the picture no longer stands for the stroke.
 */
internal data class InkDraftKey<S>(
    val stroke: S, val count: Int, val first: Offset?, val last: Offset?, val version: Int, val appearance: InkAppearance, val preview: Boolean,
)

internal fun inkDraftKey(s: InkStroke) =
    InkDraftKey(s, s.points.size, s.points.firstOrNull(), s.points.lastOrNull(), s.widthVersion, s.appearance, s.preview != null)

/** Whether [key] still stands for [s] as it is now, without making a key for it. */
internal fun inkDraftKeyMatches(key: InkDraftKey<InkStroke>, s: InkStroke): Boolean =
    key.stroke === s && key.count == s.points.size && key.version == s.widthVersion && key.appearance == s.appearance &&
        key.preview == (s.preview != null) && key.first == s.points.firstOrNull() && key.last == s.points.lastOrNull()

/** How the pages lie on the screen and are shown: a picture of drafts made for one view fits no other. */
internal data class InkPictureView(
    val width: Int, val height: Int, val zoom: Float, val scrollX: Float, val scrollY: Float,
    /** The viewer's layout of the pages (page mode, sizes, padding). */
    val layout: Any?,
    val night: Boolean,
)

/** Fewer finished drafts than this are drawn as they always were: a picture would save nothing. */
internal const val INK_PICTURE_MIN_STROKES = 3

/** How long the view and the drafts must have stood still before their picture is made, so it is never made while the pen is moving the page or writing. */
internal const val INK_PICTURE_SETTLE_MS = 120L

/**
 * Which finished drafts are kept as one picture (#609): the run of neighbours without a marker
 * that holds the most points, or none when no run has [INK_PICTURE_MIN_STROKES]. A marker is
 * blended with the page under it (Darken, or Lighten on dark paper), which a picture apart from the
 * page cannot do, and drafts are painted in order, so the picture stops at a marker: what comes
 * before the run and after it is drawn stroke by stroke as before.
 */
internal fun inkPictureRun(markers: List<Boolean>, weights: List<Int>): IntRange? {
    var best: IntRange? = null
    var bestWeight = -1
    var start = 0
    for (i in 0..markers.size) {
        if (i < markers.size && !markers[i]) continue
        if (i - start >= INK_PICTURE_MIN_STROKES) {
            val weight = (start until i).sumOf { weights[it] }
            if (weight > bestWeight) {
                bestWeight = weight
                best = start until i
            }
        }
        start = i + 1
    }
    return best
}

/**
 * How many strokes of [run] a picture already made for [pictured] at [picturedView] holds, when
 * the rest can be painted onto it: the view is the same and the picture's strokes are the first
 * of the run, unchanged. `null` when the picture has to be made anew.
 */
internal fun <K> inkPictureKeeps(picturedView: InkPictureView?, pictured: List<K>, view: InkPictureView, run: List<K>): Int? =
    if (picturedView == view && pictured.isNotEmpty() && pictured.size <= run.size && run.subList(0, pictured.size) == pictured) pictured.size else null

/**
 * Where in [finished] the strokes of a picture lie, when the picture can be shown: it was made for
 * this [view] and its strokes are there, next to each other, in order and unchanged ([matches]).
 * Strokes written since are simply not in it and are drawn after it.
 */
internal fun <K, S> inkPictureCovers(
    picturedView: InkPictureView?, pictured: List<K>, view: InkPictureView, finished: List<S>, isFirst: (K, S) -> Boolean, matches: (K, S) -> Boolean,
): IntRange? {
    if (pictured.isEmpty() || picturedView != view) return null
    val at = finished.indexOfFirst { isFirst(pictured[0], it) }
    if (at < 0 || at + pictured.size > finished.size) return null
    for (i in pictured.indices) if (!matches(pictured[i], finished[at + i])) return null
    return at until at + pictured.size
}

/**
 * The finished drafts of a Draw session as one picture at the current view (#609). Before, every
 * draft was filled again on every frame for the whole session, so a frame of the two-hundredth
 * stroke cost two hundred strokes; with the picture it costs the picture and the stroke being
 * written. The picture is made only while nothing moves ([INK_PICTURE_SETTLE_MS]), a stroke just
 * finished is painted onto it, and whenever it does not stand for exactly what is on the page
 * ([covers]) the drafts are drawn stroke by stroke as before.
 */
internal class InkDraftPicture {
    private var view: InkPictureView? = null
    private var keys: List<InkDraftKey<InkStroke>> = emptyList()
    var image: ImageBitmap? = null
        private set

    /** Read while drawing, so a new picture is shown on the next frame. */
    var generation by mutableIntStateOf(0)
        private set

    /** Where the picture's strokes lie in [finished], or `null` when it cannot be shown now. */
    fun covers(view: InkPictureView, finished: List<InkStroke>): IntRange? {
        if (image == null) return null
        return inkPictureCovers(this.view, keys, view, finished, { k, s -> k.stroke === s }, ::inkDraftKeyMatches)
    }

    fun clear() {
        if (image == null && keys.isEmpty()) return
        view = null
        keys = emptyList()
        image = null
        generation++
    }

    /**
     * Makes the picture of [finished] (in paint order, without the stroke being written) for [view],
     * or adds what is new to the one there is. [draw] paints one stroke as the screen does.
     */
    fun update(view: InkPictureView, finished: List<InkStroke>, density: Density, draw: DrawScope.(InkStroke) -> Unit) {
        val run = inkPictureRun(finished.map { it.appearance.style == InkStyle.Highlighter }, finished.map { it.points.size })
        if (run == null || view.width <= 0 || view.height <= 0) {
            clear()
            return
        }
        val strokes = finished.subList(run.first, run.last + 1)
        val runKeys = strokes.map(::inkDraftKey)
        val kept = inkPictureKeeps(this.view, keys, view, runKeys).takeIf { image != null }
        if (kept == runKeys.size) return
        val started = System.nanoTime()
        val bitmap = try {
            image?.takeIf { it.width == view.width && it.height == view.height } ?: ImageBitmap(view.width, view.height)
        } catch (_: OutOfMemoryError) {
            clear()
            PenDiag.draftPicture(strokes.size, 0, 0, view.width, view.height, made = false)
            return
        }
        if (kept == null) bitmap.asAndroidBitmap().eraseColor(0)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(view.width.toFloat(), view.height.toFloat())) {
            for (s in strokes.drop(kept ?: 0)) draw(s)
        }
        this.view = view
        keys = runKeys
        image = bitmap
        generation++
        PenDiag.draftPicture(strokes.size, strokes.size - (kept ?: 0), (System.nanoTime() - started) / 1_000_000, view.width, view.height, made = true)
    }
}
