package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.artcraftandroid.pdfcraft.core.TileRect
import io.github.artcraftandroid.pdfcraft.engine.Engine
import io.github.artcraftandroid.pdfcraft.engine.OpenTiming
import io.github.artcraftandroid.pdfcraft.engine.toBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What to draw: a whole page at the zoom-1 scale ([tile] null), or one tile of it at a deeper
 * zoom level. [gen] is the document's edit generation, so an edit asks for new pixels.
 */
data class ImageKey(val page: Int, val gen: Long, val level: Int, val tile: Int = -1) {
    val isBase get() = tile < 0
}

/** A rendered image and where it goes: a page, or a tile's rectangle in device pixels at its scale. */
class PageImage(val key: ImageKey, val bitmap: ImageBitmap, val scale: Float, val x: Int, val y: Int, val allocationBytes: Int = bitmap.width * bitmap.height * 4) {
    val bytes: Int get() = allocationBytes
}

/** A request: the key, and how to render it (page, scale, tile). */
data class ImageRequest(val key: ImageKey, val scale: Float, val tile: TileRect?, val prefetch: Boolean = false)

/** Renders at least this long also report their size in the open timings (#236). */
internal const val SLOW_RENDER_MS = 250L

/** The open-timings stage of a render: whole page or tile, zoom-1 or low-resolution base, prefetch. */
internal fun renderStage(r: ImageRequest): String {
    val kind = when {
        r.tile != null -> "tile_render"
        r.key.level != 0 -> "page_render_lowres"
        else -> "page_render"
    }
    return if (r.prefetch) "${kind}_prefetch" else kind
}

/**
 * The rendered pages and tiles of one document, and the workers that render what the view asks
 * for, most wanted first. The view calls [want] with every frame's list; whatever is no longer
 * wanted when a worker gets to it is skipped. Images are kept up to [budgetBytes] (least
 * recently used go first), and a page's latest whole-page image is kept as a stand-in while its
 * pixels for a newer generation render.
 */
class PageImages(
    private val engine: Engine,
    private val doc: ULong,
    private val scope: CoroutineScope,
    private val budgetBytes: Int,
    workers: Int = 2,
    private val openedAt: Long = android.os.SystemClock.elapsedRealtime(),
    private val compact: Boolean = false,
) {
    fun elapsedOpenMs(): Long = android.os.SystemClock.elapsedRealtime() - openedAt
    private val firstCanvas = CompletableDeferred<Unit>()
    suspend fun awaitFirstCanvas() { withTimeoutOrNull(1000L) { firstCanvas.await() } }

    private var firstDrawReported = false
    private var firstRequestReported = false
    private var firstRasterReported = false

    /** First whole-page raster submitted to Canvas; GPU presentation still needs a trace. */
    fun reportFirstDraw() {
        if (!firstDrawReported) {
            firstDrawReported = true
            firstCanvas.complete(Unit)
            scope.launch(job) {
                kotlinx.coroutines.delay(120L)
                synchronized(lock) { allowPrefetch = true }
                signal.trySend(Unit)
            }
            if (Log.isLoggable("PdfCraftOpen", Log.DEBUG)) Log.d("PdfCraftOpen", "first_page_canvas_ms=${android.os.SystemClock.elapsedRealtime() - openedAt}")
        }
    }

    private val lock = Any()
    private val cache = LinkedHashMap<ImageKey, PageImage>(64, 0.75f, true)
    private val fallback = HashMap<Int, PageImage>()
    /** The newest cached tile at each (page, level, tile), whatever its generation. */
    private val tileFallback = HashMap<Triple<Int, Int, Int>, PageImage>()
    private var allowPrefetch = false
    private var wanted: List<ImageRequest> = emptyList()
    private val inFlight = HashSet<ImageKey>()
    private val failed = HashSet<ImageKey>()
    private var bytes = 0
    /** Bumped by [invalidate]: a render started before it is of the old view and is dropped. */
    private var viewEpoch = 0
    private val signal = Channel<Unit>(Channel.CONFLATED)

    /** Bumped whenever an image arrives: the canvas reads it to redraw. */
    var version by mutableIntStateOf(0)
        private set

    /** The workers' job: cancelled when the document closes. */
    private val job = SupervisorJob(scope.coroutineContext[Job])

    init {
        repeat(workers) { scope.launch(job) { work() } }
    }

    /** Stop the workers and drop the images (the document closed). */
    fun close() {
        firstCanvas.cancel()
        job.cancel()
        clear()
    }

    /** The images the view wants now, most wanted first. */
    fun want(requests: List<ImageRequest>) {
        // Distinguish waiting for Compose layout from waiting for the engine's first image.
        if (!firstRequestReported && requests.isNotEmpty()) {
            firstRequestReported = true
            OpenTiming.milestone("first_page_requested", openedAt)
        }
        synchronized(lock) { wanted = requests }
        signal.trySend(Unit)
    }

    fun get(key: ImageKey): PageImage? = synchronized(lock) { cache[key] }

    /**
     * The same generation now draws differently (a layer was shown or hidden): render what is in
     * view again. The old pixels stay as the fallback until the new ones replace them.
     */
    fun invalidate() {
        synchronized(lock) {
            viewEpoch++
            cache.clear()
            failed.clear()
            bytes = 0
        }
        signal.trySend(Unit)
    }

    /** The newest whole-page image of [page], whatever its generation. */
    fun latestBase(page: Int): PageImage? = synchronized(lock) { fallback[page] }

    /** The newest cached tile [tile] of [page] at [level], whatever its generation. */
    fun latestTile(page: Int, level: Int, tile: Int): PageImage? = synchronized(lock) { tileFallback[Triple(page, level, tile)] }

    /** Forget everything (the document was replaced). */
    fun clear() = synchronized(lock) {
        cache.clear()
        fallback.clear()
        tileFallback.clear()
        failed.clear()
        bytes = 0
    }

    /** Drop everything but what is on screen (the system is short of memory). */
    fun trim(keep: Set<ImageKey>) = synchronized(lock) {
        val it = cache.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in keep) {
                bytes -= e.value.bytes
                it.remove()
            }
        }
        fallback.values.retainAll { it.key in keep }
        tileFallback.values.retainAll { it.key in keep }
    }

    private fun next(): ImageRequest? = synchronized(lock) {
        val r = nextRenderRequest(wanted, cache.keys, inFlight, failed, allowPrefetch) ?: return null
        inFlight.add(r.key)
        r
    }

    private suspend fun work() {
        while (true) {
            val r = next()
            if (r == null) {
                signal.receive()
                continue
            }
            val epoch = synchronized(lock) { viewEpoch }
            val image = try {
                withContext(engine.renders) {
                    // #236: name what was drawn, and how many pixels a slow render had, so a report
                    // tells a deep-zoom tile from a page or a prefetch.
                    val stage = renderStage(r)
                    val started = android.os.SystemClock.elapsedRealtime()
                    val img = io.github.artcraftandroid.pdfcraft.engine.OpenTiming.measure(stage) { engine.session.renderPageCompact(doc, r.key.page.toUInt(), r.scale, r.tile, compact) }
                    if (android.os.SystemClock.elapsedRealtime() - started >= SLOW_RENDER_MS) {
                        io.github.artcraftandroid.pdfcraft.engine.OpenTiming.count("slow_render_pixels", img.width.toLong() * img.height.toLong())
                    }
                    val bitmap = img.toBitmap()
                    PageImage(r.key, bitmap.asImageBitmap(), r.scale, r.tile?.x?.toInt() ?: 0, r.tile?.y?.toInt() ?: 0, bitmap.allocationByteCount)
                }
            } catch (e: Exception) {
                Log.w("PdfCraft", "render of ${r.key} failed: ${e.message}")
                synchronized(lock) { failed.add(r.key) }
                null
            } finally {
                synchronized(lock) { inFlight.remove(r.key) }
            }
            if (image != null && synchronized(lock) { epoch != viewEpoch }) {
                // drawn with the view before invalidate(): ask for it again
                signal.trySend(Unit)
                continue
            }
            if (image != null) {
                synchronized(lock) {
                    cache.put(r.key, image)?.let { bytes -= it.bytes }
                    bytes += image.bytes
                    if (r.key.isBase) {
                        val old = fallback[r.key.page]
                        if (old == null || old.key.gen <= r.key.gen) fallback[r.key.page] = image
                    } else {
                        val slot = Triple(r.key.page, r.key.level, r.key.tile)
                        val old = tileFallback[slot]
                        if (old == null || old.key.gen <= r.key.gen) tileFallback[slot] = image
                    }
                    evict()
                }
                if (r.key.isBase && !firstRasterReported) {
                    firstRasterReported = true
                    OpenTiming.milestone("first_page_raster_ready", openedAt, bytes = image.bytes.toLong())
                }
                version++
            }
            // let the other workers and the next frame's wishes in
            signal.trySend(Unit)
        }
    }

    /** Drop least recently used images over budget, never the ones wanted now. */
    private fun evict() {
        if (bytes <= budgetBytes) return
        val keep = wanted.mapTo(HashSet()) { it.key }
        val it = cache.entries.iterator()
        while (bytes > budgetBytes && it.hasNext()) {
            val e = it.next()
            if (e.key in keep) continue
            bytes -= e.value.bytes
            it.remove()
            if (fallback[e.key.page]?.key == e.key) fallback.remove(e.key.page)
            val slot = Triple(e.key.page, e.key.level, e.key.tile)
            if (tileFallback[slot]?.key == e.key) tileFallback.remove(slot)
        }
    }
}
