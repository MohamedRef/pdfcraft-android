package io.github.artcraftandroid.pdfcraft.engine

import android.os.ParcelFileDescriptor
import java.io.File

/**
 * A PDF another app's provider lends, opened in place (#453): the engine maps the provider's
 * descriptor instead of a copy of the file in the app's storage. [file] is the working file the
 * engine saves to; it does not exist until the first save, and it is deleted when the document
 * closes with its changes written back.
 */
class LentSource(
    /** The provider's descriptor; the engine keeps its own copy, so this is closed after opening. */
    val descriptor: ParcelFileDescriptor,
    /** The file's size when it was opened. */
    val size: Long,
    /** The file's identity, to ask the engine whether it still maps it. */
    val dev: Long,
    val ino: Long,
    /** Where saves go (see the class). */
    val file: File,
)

/**
 * What an in-place document's saves have done to its original (#453). The engine maps the
 * original, so PdfCraft never shrinks or rewrites it while the document is open: an incremental
 * save is appended to it, and a save that rewrote the whole file is written over the original
 * once the document is closed and nothing maps it any more.
 */
class LentState(val dev: Long, val ino: Long, providerLength: Long) {
    /** How long the original is now: as opened, then after each revision appended to it. */
    var providerLength = providerLength
        internal set

    /** Every save since [providerLength] was written appended a revision to what it had. */
    var chainIntact = true
        internal set

    /** A save rewrote the file: it is written over the original once the document has closed. */
    var writeBackAtClose = false
        internal set

    /** Record a save's result: [incremental] as `doc_save` reported it. */
    fun saved(incremental: Boolean) {
        if (!incremental) chainIntact = false
    }

    /** The saved revision was appended to the original, which is now [length] bytes long. */
    fun appended(length: Long) {
        providerLength = length
        chainIntact = true
    }
}

/** Why [DocumentFiles.lend] copies a document in instead of opening it in place (the log's `reason`). */
internal object InPlaceReason {
    const val SETTING_OFF = "setting_off"
    const val NOT_CONTENT = "not_content"
    const val PENDING_WRITE_BACK = "pending_write_back"
    const val NO_DESCRIPTOR = "no_descriptor"
    const val ASSET_OFFSET = "asset_offset"
    const val NOT_REGULAR_FILE = "not_regular_file"
    const val EMPTY_OR_UNKNOWN_SIZE = "unknown_size"
    const val TOO_LARGE = "too_large"
    const val ENGINE_REFUSED = "engine_refused"
}

/** The largest file the engine opens (the same 512 MiB limit as a copied one). */
internal const val MAX_IN_PLACE_BYTES = 512L * 1024 * 1024

/**
 * Why a document can't be opened in place, or null when it can (#453). [startOffset] and
 * [declaredLength] are the provider's asset bounds; [statSize] and [regular] its descriptor's.
 */
internal fun inPlaceRefusal(
    enabled: Boolean,
    content: Boolean,
    pendingWriteBack: Boolean,
    startOffset: Long,
    declaredLength: Long,
    statSize: Long,
    regular: Boolean,
): String? = when {
    !enabled -> InPlaceReason.SETTING_OFF
    !content -> InPlaceReason.NOT_CONTENT
    // a failed write-back may have damaged the original: its recovery copy opens instead
    pendingWriteBack -> InPlaceReason.PENDING_WRITE_BACK
    !regular -> InPlaceReason.NOT_REGULAR_FILE
    statSize <= 0 -> InPlaceReason.EMPTY_OR_UNKNOWN_SIZE
    // a part of a larger file (an asset inside a package): the engine maps whole files only
    startOffset != 0L || (declaredLength >= 0 && declaredLength != statSize) -> InPlaceReason.ASSET_OFFSET
    statSize > MAX_IN_PLACE_BYTES -> InPlaceReason.TOO_LARGE
    else -> null
}

/** How a save of an in-place document reaches its original (the log's `mode`). */
internal enum class InPlaceWriteBack { Append, Full, AtClose }

/**
 * How to write [workingLength] saved bytes back to an in-place original (#453): appended when
 * every save since the original was last written added a revision to it ([chainIntact]) and the
 * original still has the [providerLength] bytes it had then ([originalLength]); written over it
 * when the engine no longer maps it ([mapped] false); otherwise once the document has closed.
 */
internal fun inPlaceWriteBack(chainIntact: Boolean, providerLength: Long, originalLength: Long?, workingLength: Long, mapped: Boolean): InPlaceWriteBack = when {
    chainIntact && originalLength == providerLength && workingLength >= providerLength -> InPlaceWriteBack.Append
    !mapped -> InPlaceWriteBack.Full
    else -> InPlaceWriteBack.AtClose
}

/**
 * Where [DocumentFiles.appendWriteBack] compares a saved file with the original it extends (#453):
 * windows of [window] bytes at the start, the end and 14 evenly spaced places of [length] bytes.
 */
internal fun appendCheckOffsets(length: Long, window: Long): List<Long> {
    if (length <= 0) return emptyList()
    val last = (length - window).coerceAtLeast(0)
    return (0..15).map { i -> last * i / 15 }.distinct()
}

/** Why a document may or may not be written where it is ([DocumentFiles.writeAccess], #491). */
internal object WriteAccess {
    const val FILE_WRITABLE = "file_writable"
    const val FILE_NOT_WRITABLE = "file_not_writable"
    const val PROVIDER_READ_ONLY = "provider_read_only"
    const val WRITE_GRANT = "write_grant"
    const val WRITE_PROBE = "write_probe"
    const val READ_GRANT_ONLY = "read_grant_only"
    const val PROBE_FAILED = "probe_failed"
    /** Its real file is written directly, with All files access ([AllFilesAccess]). */
    const val ALL_FILES = "all_files"
    val WRITABLE = setOf(FILE_WRITABLE, WRITE_GRANT, WRITE_PROBE, ALL_FILES)

    /** A reason that All files access would overcome (not a write that was tried and failed). */
    fun allFilesHelps(reason: String?): Boolean = reason == PROVIDER_READ_ONLY || reason == READ_GRANT_ONLY || reason == PROBE_FAILED
}
