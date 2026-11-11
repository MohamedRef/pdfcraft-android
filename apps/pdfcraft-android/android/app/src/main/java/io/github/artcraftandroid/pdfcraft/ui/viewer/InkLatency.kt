package io.github.artcraftandroid.pdfcraft.ui.viewer

/**
 * App-side ink latency (#128): from a pen sample's event time to the moment the app issues the
 * draw that shows it, for the normal Compose path and for the front-buffered (low-latency) path.
 * It leaves out what the display adds afterwards (composition, scan-out, the panel), so it is a
 * before/after comparison of the two paths, not touch-to-photon latency.
 */
internal class LatencyStats(private val capacity: Int = 512) {
    private val samples = ArrayDeque<Long>(capacity)

    @Synchronized
    fun add(ms: Long) {
        // a clock mix-up or a stall far beyond a frame is not a latency sample
        if (ms !in 0..1000) return
        if (samples.size >= capacity) samples.removeFirst()
        samples.addLast(ms)
    }

    @Synchronized
    fun clear() = samples.clear()

    @Synchronized
    fun summary(): LatencySummary? = latencySummary(samples.toList())
}

internal data class LatencySummary(val count: Int, val medianMs: Long, val p95Ms: Long)

/** Median and 95th percentile (nearest rank) of [samples]; null when there are none. */
internal fun latencySummary(samples: List<Long>): LatencySummary? {
    if (samples.isEmpty()) return null
    val sorted = samples.sorted()
    fun rank(p: Double) = sorted[(Math.ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
    return LatencySummary(sorted.size, rank(0.5), rank(0.95))
}

internal object InkLatency {
    val normal = LatencyStats()
    val lowLatency = LatencyStats()
}

/** The newest pen sample not yet shown by a Compose draw (event uptime in ms, 0 when none). */
internal class PendingInput {
    @Volatile var at: Long = 0L

    /** A draw shows it now: records its latency once. */
    fun drawn(now: Long, stats: LatencyStats) {
        val t = at
        if (t > 0L) {
            at = 0L
            stats.add(now - t)
        }
    }
}
