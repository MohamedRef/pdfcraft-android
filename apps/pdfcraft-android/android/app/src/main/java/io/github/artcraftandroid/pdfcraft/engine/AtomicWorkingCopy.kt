package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.io.IOException

/**
 * Publish a complete staged copy by same-directory rename. Never truncate an existing PDF
 * when publication fails: readers may still hold its inode, and it remains a recovery copy.
 */
internal fun publishWorkingCopy(
    staged: File,
    target: File,
    rename: (File, File) -> Boolean = { source, destination -> source.renameTo(destination) },
) {
    val source = staged.canonicalFile
    val destination = target.canonicalFile
    if (source == destination || source.parentFile != destination.parentFile) {
        throw IOException("A working copy must be staged separately in its destination directory.")
    }
    if (!rename(staged, target)) {
        throw IOException("Could not atomically publish the new working copy. The previous copy was preserved.")
    }
}
