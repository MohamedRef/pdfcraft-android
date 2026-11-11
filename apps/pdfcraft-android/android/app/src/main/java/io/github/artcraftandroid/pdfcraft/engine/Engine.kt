package io.github.artcraftandroid.pdfcraft.engine

import android.graphics.Bitmap
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.InternalException
import io.github.artcraftandroid.pdfcraft.core.MobileException
import io.github.artcraftandroid.pdfcraft.core.MobileSession
import io.github.artcraftandroid.pdfcraft.core.RenderedImage
import io.github.artcraftandroid.pdfcraft.core.initLogging
import io.github.artcraftandroid.pdfcraft.text
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import org.json.JSONArray
import org.json.JSONObject

/**
 * The PdfCraft engine (Rust: `apps/pdfcraft-android`) as the app uses it: one [MobileSession]
 * and the threads its blocking calls run on. Never call the session from the main thread: tools
 * are quick, renders and saves are not.
 */
class Engine private constructor(val session: MobileSession) {
    /** Tools run one at a time, in the order the UI issued them. */
    val commands: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Page renders: the view's pages and tiles. */
    val renders: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)

    private val preparation = java.util.concurrent.ThreadPoolExecutor(
        0, 1, 15L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>(),
        java.util.concurrent.ThreadFactory { task ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "PdfCraft preparation").apply { isDaemon = true }
        },
    ).asCoroutineDispatcher()

    suspend fun completeOpen(doc: ULong) = withContext(preparation) { engineFailuresAsMobile { session.completeOpen(doc) } }

    /** Thumbnails (page grids, recent files). */
    val thumbnails: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /**
     * Run [block] on the session, after the commands issued before it. A panic in the engine
     * reaches here as [InternalException]; it is turned into a [MobileException] so that every
     * caller's existing error handling shows it instead of the app crashing (#114).
     */
    suspend fun <T> command(block: MobileSession.() -> T): T = withContext(commands) { engineFailuresAsMobile { session.block() } }

    /**
     * Run the engine tool [name] (the tools of `pdfcraft-cli run` and MCP; pages are 1-based)
     * with [args], after the commands issued before it. Throws [MobileException] with a message
     * for the user when the tool refuses or fails.
     */
    suspend fun tool(name: String, args: Map<String, Any?> = emptyMap()): JSONObject = command {
        val out = execute(name, toJson(args).toString())
        if (out.trimStart().startsWith("{")) JSONObject(out) else JSONObject().put("result", out)
    }

    /**
     * Run [groups] of related edit commands on the same serial dispatcher, with no other UI
     * command between them. A group is all or nothing: when one of its commands fails, the ones
     * of that group already applied are undone again, and the groups after it are not run. The
     * groups before it stay applied. Throws [ToolBatchException] with how many groups were applied.
     */
    suspend fun toolBatch(name: String, groups: List<List<Map<String, Any?>>>): Int = command {
        // Each command maps a panic to a MobileException itself, so the refused group says it plainly.
        runToolBatch(groups, execute = { args -> engineFailuresAsMobile { execute(name, toJson(args).toString()) } }, undo = { args ->
            engineFailuresAsMobile { execute("edit_undo", toJson(mapOf("doc" to args["doc"])).toString()) }
        })
    }

    fun trimMemory() = session.releaseMemory()

    /**
     * Unpack the text-recognition models shipped in the app's assets (once) and point the engine
     * at them. Returns false when this build has no models. Blocking: call off the main thread.
     */
    fun prepareOcrModels(context: android.content.Context): Boolean {
        val names = runCatching { context.assets.list("models")?.toList() }.getOrNull().orEmpty()
        if (names.none { it.endsWith(".rten") }) return false
        val dir = java.io.File(context.filesDir, "models").apply { mkdirs() }
        for (name in names) {
            val target = java.io.File(dir, name)
            if (target.isFile && target.length() > 0) continue
            val tmp = java.io.File(dir, "$name.part")
            context.assets.open("models/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(target)
        }
        android.system.Os.setenv("PDFCRAFT_MODELS", dir.absolutePath, true)
        return true
    }

    companion object {
        fun open(context: android.content.Context): Engine {
            initLogging()
            val session = MobileSession()
            session.setPrivateInputRoot(context.filesDir.absolutePath)
            return Engine(session)
        }

        /** Maps, lists, arrays and plain values as JSON (nulls are left out of objects). */
        fun toJson(value: Any?): Any = when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().apply { for ((k, v) in value) if (v != null) put(k.toString(), toJson(v)) }
            is Iterable<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
            is IntArray -> JSONArray().apply { value.forEach { put(it) } }
            is DoubleArray -> JSONArray().apply { value.forEach { put(it) } }
            is FloatArray -> JSONArray().apply { value.forEach { put(it.toDouble()) } }
            is Array<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
            is Float -> value.toDouble()
            is UInt -> value.toLong()
            is ULong -> value.toLong()
            else -> value
        }
    }
}

/** A [Engine.toolBatch] stopped at a failing group: [applied] groups before it were applied. */
class ToolBatchException(val applied: Int, val rolledBack: Boolean, override val cause: Throwable) : Exception(cause.message, cause)

/**
 * [Engine.toolBatch] without the engine, so the all-or-nothing rule is unit-testable: [execute]
 * applies one command (one undo step), [undo] takes back the last one of a document.
 */
internal fun runToolBatch(
    groups: List<List<Map<String, Any?>>>,
    execute: (Map<String, Any?>) -> Unit,
    undo: (Map<String, Any?>) -> Unit,
): Int {
    groups.forEachIndexed { index, group ->
        val done = ArrayList<Map<String, Any?>>()
        try {
            for (args in group) {
                execute(args)
                done.add(args)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val rolledBack = runCatching { done.asReversed().forEach(undo) }.isSuccess
            throw ToolBatchException(index, rolledBack, e)
        }
    }
    return groups.size
}

/**
 * Run [block], turning an engine panic ([InternalException], which UniFFI throws when Rust
 * panics) into [MobileException.Command] with a message for the user (#114).
 */
internal inline fun <T> engineFailuresAsMobile(block: () -> T): T = try {
    block()
} catch (_: InternalException) {
    throw MobileException.Command("An internal error stopped this action. If the document looks wrong, close it and open it again.")
}

/** Reject invalid native buffers *before* allocating a Bitmap on the Java heap. */
internal fun expectedBitmapByteCount(width: Long, height: Long): Int? {
    if (width <= 0L || height <= 0L || width > 8192L || height > 8192L) return null
    val bytes = width * height * 4L
    return bytes.takeIf { it <= 4L * 1024L * 1024L * 4L }?.toInt()
}

/** The pixels as a bitmap: the engine's premultiplied RGBA bytes are the `ARGB_8888` memory layout. */
fun RenderedImage.toBitmap(): Bitmap {
    val expected = expectedBitmapByteCount(width.toLong(), height.toLong())
    require(expected != null && rgba.size == expected) { "Invalid native PDF raster dimensions or buffer length" }
    val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
    return bitmap
}

/** The user-facing message of an engine failure. */
val Throwable.userMessage: String
    get() = when (this) {
        is MobileException.NeedsPassword -> text(R.string.files_error_needs_password)
        is MobileException.WrongPassword -> text(R.string.common_wrong_password)
        else -> message?.takeIf { it.isNotBlank() } ?: text(R.string.files_error_generic)
    }

/** Native compact rasters stay packed across JNI. Android may align RGB565 rows to four bytes. */
fun io.github.artcraftandroid.pdfcraft.core.CompactImage.toBitmap(): Bitmap {
    val bpp = if (rgb565) 2 else 4
    val expected = expectedBitmapByteCount(width.toLong(), height.toLong())
    require(expected != null && pixels.size == expected / 4 * bpp) { "Invalid native PDF raster" }
    val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), if (rgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
    val packedRow = width.toInt() * bpp
    val buffer = paddedRasterRows(pixels, packedRow, bitmap.rowBytes, height.toInt())
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(buffer))
    return bitmap
}

internal fun paddedRasterRows(pixels: ByteArray, packedRow: Int, rowBytes: Int, height: Int): ByteArray {
    require(packedRow > 0 && height > 0 && rowBytes >= packedRow && pixels.size.toLong() == packedRow.toLong() * height)
    if (packedRow == rowBytes) return pixels
    require(rowBytes.toLong() * height <= 32L * 1024 * 1024)
    return ByteArray(rowBytes * height).also { out ->
        repeat(height) { row -> pixels.copyInto(out, row * rowBytes, row * packedRow, (row + 1) * packedRow) }
    }
}
