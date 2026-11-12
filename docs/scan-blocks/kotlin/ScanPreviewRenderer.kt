package io.github.artcraftandroid.pdfcraft.ui.scan

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.artcraftandroid.pdfcraft.core.ScanPageRequest
import io.github.artcraftandroid.pdfcraft.core.scanPreviewPage
import io.github.artcraftandroid.pdfcraft.core.scanReleasePreviews
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bytes per pixel in the engine's preview: red, green, blue and alpha. */
private const val BYTES_PER_PIXEL: Int = 4

/** Nanoseconds in a millisecond, to turn the engine's timing into the log's unit. */
private const val NANOS_PER_MS: Long = 1_000_000L

/** Degrees in a quarter turn: the rotation a press of Rotate adds to a page. */
private const val QUARTER_TURN_DEGREES: Float = 90f

/** What one drawn preview cost and how it was made, for the diagnostics log (issue #301). */
data class ScanPreviewInfo(
    /** Milliseconds the engine and the bitmap took for this render, not counting the wait in the queue. */
    val ms: Long,
    /** Whether the engine reused its straightened base from an earlier render (only the look or strength changed). */
    val reused: Boolean,
    /** Quarter turns clockwise the engine gave the page to stand it upright, 0 to 3. */
    val quarterTurns: Int,
    /** Whether the engine flattened the page before the look. */
    val flattened: Boolean,
)

/**
 * Draws the scan screen's previews of a page as it will be saved (issue #301, spec S15 section 3).
 *
 * Every preview has a target: the main preview, or one tile of the looks strip. Each target has its
 * own [ScanPreviewQueue], so a slow render of one never holds up another, and a request that is no
 * longer wanted is dropped before it is drawn (latest wins). The engine runs on one thread of its
 * own, never on the shared thumbnail thread (#96), and the bitmap goes back to the caller only on
 * the scope's thread, which must be the main thread: [ask] and the callbacks are called there.
 *
 * [requestFor] builds the engine's request from a key. The screen supplies it, so the testing
 * options (#288) are applied there. Nothing here writes a file.
 */
internal class ScanPreviewRenderer(
    private val scope: CoroutineScope,
    private val requestFor: (ScanPreviewKey) -> ScanPageRequest,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val worker = executor.asCoroutineDispatcher()
    private val queues: HashMap<String, ScanPreviewQueue> = HashMap()
    /** The latest key asked for each target, with the callback that wants its picture. */
    private val wanted: HashMap<String, Wanted> = HashMap()
    private var closed: Boolean = false

    private class Wanted(val key: ScanPreviewKey, val onImage: (ImageBitmap, ScanPreviewInfo) -> Unit)

    private class Drawn(val image: ImageBitmap, val info: ScanPreviewInfo)

    /**
     * Asks for [key] to be drawn for [target] and handed to [onImage]. Asking again while it is
     * running does nothing; a different key waits, and replaces any key that was waiting. Call on
     * the main thread.
     */
    fun ask(target: String, key: ScanPreviewKey, onImage: (ImageBitmap, ScanPreviewInfo) -> Unit) {
        if (closed) return
        wanted[target] = Wanted(key, onImage)
        val queue = queues.getOrPut(target) { ScanPreviewQueue() }
        val ticket = queue.ask(key) ?: return
        start(target, key, ticket)
    }

    /** Starts one render on the engine's thread; its result comes back on the scope's thread. */
    private fun start(target: String, key: ScanPreviewKey, ticket: Int) {
        if (closed) return
        scope.launch {
            val drawn: Drawn? = runCatching { withContext(worker) { draw(key) } }.getOrNull()
            finish(target, key, ticket, drawn)
        }
    }

    /**
     * Records that [ticket] has been drawn. Starts the waiting key first, then hands the picture
     * over if it is still the one wanted.
     */
    private fun finish(target: String, key: ScanPreviewKey, ticket: Int, drawn: Drawn?) {
        if (closed) return
        val queue = queues[target] ?: return
        val result = queue.done(ticket)
        val next = result.next
        val nextTicket = result.nextTicket
        if (next != null && nextTicket != null) start(target, next, nextTicket)
        val want = wanted[target]
        if (result.show && drawn != null && want != null && want.key == key) {
            want.onImage(drawn.image, drawn.info)
        }
    }

    /** Runs on the engine's thread: the engine's preview, then the bitmap. Null when the engine fails. */
    private fun draw(key: ScanPreviewKey): Drawn? {
        val started = System.nanoTime()
        val preview = runCatching { scanPreviewPage(requestFor(key), key.maxSide.toUInt()) }.getOrNull() ?: return null
        val width = preview.width.toInt()
        val height = preview.height.toInt()
        val pixels = preview.rgba
        if (width <= 0 || height <= 0 || pixels.size < width * height * BYTES_PER_PIXEL) return null
        val upright = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        upright.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
        val turns = userQuarterTurns(key.look.userTurns)
        val bitmap = if (turns == 0) upright else turned(upright, turns)
        val info = ScanPreviewInfo(
            ms = (System.nanoTime() - started) / NANOS_PER_MS,
            reused = preview.reused,
            quarterTurns = preview.quarterTurns.toInt(),
            flattened = preview.flattened,
        )
        return Drawn(bitmap.asImageBitmap(), info)
    }

    /** Stops the renderer: no more renders start, and the engine's cached previews are released. */
    fun close() {
        if (closed) return
        closed = true
        wanted.clear()
        queues.clear()
        executor.execute { runCatching { scanReleasePreviews() } }
        executor.shutdown()
    }
}

/** The presses of Rotate as quarter turns clockwise from 0 to 3, for any count, negative too. */
private fun userQuarterTurns(userTurns: Int): Int = ((userTurns % 4) + 4) % 4

/** [source] turned [turns] quarter turns clockwise. */
private fun turned(source: Bitmap, turns: Int): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(QUARTER_TURN_DEGREES * turns)
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}
