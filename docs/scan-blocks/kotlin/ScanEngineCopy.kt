package io.github.artcraftandroid.pdfcraft.ui.scan

import java.io.File
import java.io.InputStream

/**
 * The suffix of a page copy while it is being written (#307). A copy is written under this name
 * first and renamed only when whole, so a copy that fails never leaves a file that looks finished.
 */
internal const val PAGE_PART_SUFFIX: String = ".part"

/** The name of a page copied from Google's scanner, from [nanoTime] (spec S21 section 3). */
fun googlePageFileName(nanoTime: Long): String = "scan-$nanoTime.jpg"

/**
 * Copy the whole of [input] into [target] (#307). Returns true when [target] now holds every byte.
 * A null [input], or a stream that fails part way, returns false and leaves no file behind. [input]
 * is closed in every case.
 */
fun copyPageStream(input: InputStream?, target: File): Boolean {
    if (input == null) return false
    val part = File(target.parentFile, target.name + PAGE_PART_SUFFIX)
    return try {
        input.use { source -> part.outputStream().use { sink -> source.copyTo(sink) } }
        if (part.renameTo(target)) {
            true
        } else {
            part.delete()
            false
        }
    } catch (e: Exception) {
        part.delete()
        false
    }
}
