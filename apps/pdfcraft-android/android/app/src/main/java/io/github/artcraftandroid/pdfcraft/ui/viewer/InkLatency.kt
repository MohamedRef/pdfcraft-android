package io.github.artcraftandroid.pdfcraft.ui.viewer

/**
 * App-side ink latency (#128): from a pen sample's event time to the moment the app issues the
 * draw that shows it, for the normal Compose path and for the front-buffered (low-latency) path.
 * It leaves out what the display adds afterwards (composition, scan-out, the panel), so it is a
 * before/after comparison of the two paths, not touch-to-photon latency.
 */
internal class LatencyStats(private val capacity: Int = 512, private val onSample: (Long) -> Unit = {}) {
    private val samples = ArrayDeque<Long>(capacity)

    @Synchronized
    fun add(ms: Long) {
        // a clock mix-up or a stall far beyond a frame is not a latency sample
        if (ms !in 0..1000) return
        if (samples.size >= capacity) samples.removeFirst()
        samples.addLast(ms)
        onSample(ms)
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
    /** The stroke in progress, for the diagnostics log's line per stroke. */
    val stroke = StrokeTrace()
    val normal = LatencyStats { stroke.latency(front = false, ms = it) }
    val lowLatency = LatencyStats { stroke.latency(front = true, ms = it) }
}

/** One pen stroke, as the diagnostics log shows it (#128): no positions, only times and counts. */
internal data class StrokeSummary(
    val durationMs: Long,
    val events: Int,
    val samples: Int,
    val eraser: Boolean,
    val cancelled: Boolean,
    val normal: LatencySummary?,
    val normalMaxMs: Long,
    val front: LatencySummary?,
    val frontMaxMs: Long,
    /** What wrote it: `pen`, `eraser` or `finger`. */
    val pointer: String = "pen",
    /** Compose draws of the ink layer while the stroke was being written. */
    val draws: Int = 0,
    /** Time between those draws: about 8 ms at 120 frames a second; more means dropped frames. */
    val frameGap: LatencySummary? = null,
    val frameGapMaxMs: Long = 0L,
    /** Time the ink layer's draw code took per draw, in microseconds (recording only, not the GPU). */
    val drawUs: LatencySummary? = null,
    val drawMaxUs: Long = 0L,
    /** How often the whole tool layer was recomposed during the stroke. */
    val recompositions: Int = 0,
) {
    /** Pen samples per second Android delivered (historical ones included). */
    val sampleHz: Long get() = if (durationMs > 0) samples * 1000L / durationMs else 0L
}

/**
 * Collects one stroke's event and sample counts and its app-side latencies, between the pen's
 * down and up. Only active while the diagnostics log records ([down] with `active = true`).
 */
internal class StrokeTrace {
    private var active = false
    private var downAt = 0L
    private var events = 0
    private var samples = 0
    private var eraser = false
    private var pointer = "pen"
    private val normal = ArrayList<Long>()
    private val front = ArrayList<Long>()
    private val drawUs = ArrayList<Long>()
    private val frameGaps = ArrayList<Long>()
    private var lastDrawNs = 0L
    private var recompositions = 0

    @Synchronized
    fun down(time: Long, eraser: Boolean, active: Boolean, pointer: String = if (eraser) "eraser" else "pen") {
        this.active = active
        downAt = time
        events = 0
        samples = 0
        this.eraser = eraser
        this.pointer = pointer
        normal.clear()
        front.clear()
        drawUs.clear()
        frameGaps.clear()
        lastDrawNs = 0L
        recompositions = 0
    }

    /** The ink layer drew the stroke in progress at [nowNs], its draw code taking [tookUs]. */
    @Synchronized
    fun drawn(nowNs: Long, tookUs: Long) {
        if (!active || drawUs.size >= 4096) return
        drawUs.add(tookUs)
        if (lastDrawNs > 0L) frameGaps.add((nowNs - lastDrawNs) / 1_000_000)
        lastDrawNs = nowNs
    }

    /** The tool layer recomposed while the stroke was written. */
    @Synchronized
    fun recomposed() {
        if (active) recompositions++
    }

    /** A move event carrying [count] samples (its historical ones and its own). */
    @Synchronized
    fun move(count: Int) {
        if (!active) return
        events++
        samples += count
    }

    @Synchronized
    fun latency(front: Boolean, ms: Long) {
        if (!active || normal.size + this.front.size >= 4096) return
        (if (front) this.front else normal).add(ms)
    }

    /** The pen lifted (or the stroke was cancelled): the stroke's summary, or null when inactive. */
    @Synchronized
    fun up(time: Long, cancelled: Boolean): StrokeSummary? {
        if (!active) return null
        active = false
        return StrokeSummary(
            (time - downAt).coerceAtLeast(0), events, samples, eraser, cancelled,
            latencySummary(normal), normal.maxOrNull() ?: 0L, latencySummary(front), front.maxOrNull() ?: 0L,
            pointer, drawUs.size, latencySummary(frameGaps), frameGaps.maxOrNull() ?: 0L,
            latencySummary(drawUs), drawUs.maxOrNull() ?: 0L, recompositions,
        )
    }
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
