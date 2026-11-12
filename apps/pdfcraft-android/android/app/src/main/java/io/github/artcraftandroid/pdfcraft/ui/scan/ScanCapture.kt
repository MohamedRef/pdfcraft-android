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
    /** The live outline at the shutter (#295): whether it was on, showed a page, was steady, the advice shown, and the outline (8 shares of the upright frame). */
    val live: Boolean = false,
    val liveFound: Boolean = false,
    val liveStable: Boolean = false,
    val liveHint: String = "none",
    val previewQuad: FloatArray? = null,
    /** Auto-capture (#296): whether it was on, and whether it took this photo (not the shutter). */
    val autoOn: Boolean = false,
    val autoShot: Boolean = false,
    /** The photo's sharpness (#389), measured after the shot; -1 until measured or when it failed. Its "blurred" is unverified and only recorded. */
    val sharpness: Double = -1.0,
    val sharpnessBlurredUnverified: Boolean = false,
    val sharpnessMs: Long = 0L,
) {
    /** The facts as diagnostics fields: short lowercase words, booleans and numbers only. */
    fun diag(): Array<Pair<String, Any?>> = arrayOf(
        "source" to source, "mp10" to megapixelsTenths, "width" to width, "height" to height,
        "exposure_index" to exposureIndex, "exposure_step_milli" to exposureStepMilli, "torch" to torch,
        "focused" to focused, "rotation" to rotation, "capture_ms" to captureMs,
        "live" to live, "live_found" to liveFound, "live_stable" to liveStable, "live_hint" to liveHint,
        "auto_on" to autoOn, "auto_shot" to autoShot,
    )

    /** The facts as JSON, for a kept scan's page file. */
    fun json(): JSONObject = JSONObject()
        .put("source", source).put("mp10", megapixelsTenths).put("width", width).put("height", height)
        .put("exposure_index", exposureIndex).put("exposure_step_milli", exposureStepMilli).put("torch", torch)
        .put("focused", focused).put("rotation", rotation).put("capture_ms", captureMs)
        .put("live", live).put("live_found", liveFound).put("live_stable", liveStable).put("live_hint", liveHint)
        .put("auto_on", autoOn).put("auto_shot", autoShot)
        .put("sharpness", if (sharpness >= 0 && sharpness.isFinite()) Math.round(sharpness * 1000.0) / 1000.0 else JSONObject.NULL)
        .put("sharpness_blurred_unverified", sharpnessBlurredUnverified).put("sharpness_ms", sharpnessMs)
        .put(
            "preview_quad",
            previewQuad?.takeIf { it.size == 8 }?.let { q -> org.json.JSONArray().also { a -> q.forEach { v -> a.put(if (v.isFinite()) Math.round(v * 100000.0) / 100000.0 else 0.0) } } } ?: JSONObject.NULL,
        )
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
