package io.github.artcraftandroid.pdfcraft.ui.scan

import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * How one scan photo was taken (#294): written to the diagnostics log's `camera` line and into a
 * kept scan's page file (`capture`), so a page that comes out wrong can be traced to its capture.
 */
internal data class ScanCapture(
    /** `in_app`, `phone_app` or `import`. */
    val source: String,
    /** The still's size in tenths of a megapixel; 0 when not known (the phone's camera app, an import). */
    val megapixelsTenths: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    /** The exposure compensation index asked for, and the camera's step in thousandths of a stop. */
    val exposureIndex: Int = 0,
    val exposureStepMilli: Int = 0,
    /** Whether the torch was on. */
    val torch: Boolean = false,
    /** Whether the last focus before the shot reported the page in focus. */
    val focused: Boolean = false,
    /** The device's turn when the photo was taken, in degrees (0, 90, 180, 270). */
    val rotation: Int = 0,
    /** From the shutter press to the file written, in milliseconds. */
    val captureMs: Long = 0L,
) {
    /** The facts as diagnostics fields: short lowercase words, booleans and numbers only. */
    fun diag(): Array<Pair<String, Any?>> = arrayOf(
        "source" to source, "mp10" to megapixelsTenths, "width" to width, "height" to height,
        "exposure_index" to exposureIndex, "exposure_step_milli" to exposureStepMilli, "torch" to torch,
        "focused" to focused, "rotation" to rotation, "capture_ms" to captureMs,
    )

    /** The facts as JSON, for a kept scan's page file. */
    fun json(): JSONObject = JSONObject()
        .put("source", source).put("mp10", megapixelsTenths).put("width", width).put("height", height)
        .put("exposure_index", exposureIndex).put("exposure_step_milli", exposureStepMilli).put("torch", torch)
        .put("focused", focused).put("rotation", rotation).put("capture_ms", captureMs)
}

/**
 * The captures of the photos in the scan in progress, by the photo's path. Kept in memory only: a
 * scan that Android restores after closing the app keeps its photos, and their page files then
 * say `"capture": null`.
 */
internal object ScanCaptures {
    private val byPath = ConcurrentHashMap<String, ScanCapture>()

    fun put(path: String, capture: ScanCapture) {
        byPath[path] = capture
        // a scan is a few dozen photos at most; this only stops a very long session from growing
        if (byPath.size > MAX) byPath.keys.firstOrNull()?.let(byPath::remove)
    }

    fun of(path: String): ScanCapture? = byPath[path]

    private const val MAX = 500
}
