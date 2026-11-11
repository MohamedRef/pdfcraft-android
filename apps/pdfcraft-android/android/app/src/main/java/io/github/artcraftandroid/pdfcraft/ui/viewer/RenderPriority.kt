package io.github.artcraftandroid.pdfcraft.ui.viewer

/** Visible requests always precede speculative work, even when the caller order changes. */
internal fun nextRenderRequest(requests: List<ImageRequest>, cached: Set<ImageKey>, inFlight: Set<ImageKey>, failed: Set<ImageKey>, allowPrefetch: Boolean): ImageRequest? {
    fun eligible(r: ImageRequest) = r.key !in cached && r.key !in inFlight && r.key !in failed
    return requests.firstOrNull { !it.prefetch && eligible(it) }
        ?: if (allowPrefetch) requests.firstOrNull { eligible(it) } else null
}
