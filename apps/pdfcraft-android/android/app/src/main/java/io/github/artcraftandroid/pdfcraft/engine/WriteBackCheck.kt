package io.github.artcraftandroid.pdfcraft.engine

/**
 * What a provider stored after a write-back, as read back through a fresh descriptor (#102).
 * [length] is null when the provider can't say (a pipe or an unknown-size asset); [tail] is null
 * when the end could not be read.
 */
internal class WrittenBack(val length: Long?, val tail: ByteArray?)

/** How many bytes at the end of the file a write-back compares. */
internal const val WRITE_BACK_TAIL_BYTES = 4096

/**
 * Whether the provider holds what was written: the same length as the local copy and, when its
 * end could be read, the same last bytes. A provider that ignores truncation leaves a longer
 * file, and one that cut the write short leaves a shorter one; both fail. When the provider
 * can't report anything ([written] null or its length unknown), the write is taken as it was
 * before this check existed, because nothing contradicts it.
 */
internal fun writeBackMatches(localLength: Long, localTail: ByteArray, written: WrittenBack?): Boolean {
    val length = written?.length ?: return true
    if (length != localLength) return false
    val tail = written.tail ?: return true
    return tail.contentEquals(localTail)
}
