package io.github.artcraftandroid.pdfcraft.ui

/** Reserve most of the actual heap for UI, native transfers and concurrent temporary rasters. */
internal fun runtimeImageBudget(maxHeap: Long): Int = (maxHeap.coerceAtLeast(0) / 8)
    .coerceAtMost(192L * 1024 * 1024).toInt()
