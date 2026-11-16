package io.github.artcraftandroid.pdfcraft.ui.scan

/**
 * How a scan began (#617), for the diagnostics log's `scan_start` line and each kept page's
 * `capture.started`: the camera (New scan), the photo picker (Scan from photos), pictures shared
 * from another app, or a launcher shortcut.
 */
internal enum class ScanStart(val id: String) {
    Camera("camera"),
    Import("import"),
    Shared("shared"),
    Shortcut("shortcut"),
}

/** The launcher shortcuts' actions (#617), handled by MainActivity. */
internal object ScanShortcuts {
    const val ACTION_NEW_SCAN = "io.github.artcraftandroid.pdfcraft.action.NEW_SCAN"
    const val ACTION_SCAN_PHOTOS = "io.github.artcraftandroid.pdfcraft.action.SCAN_FROM_PHOTOS"
    const val ACTION_OPEN_FILE = "io.github.artcraftandroid.pdfcraft.action.OPEN_FILE"
}

/** At most this many shared pictures start one scan; more are left out and the person is told. */
internal const val SCAN_SHARED_MAX = 100

/**
 * The pictures another app shared to start a scan (#617), in the order shared: [streams] (the
 * `EXTRA_STREAM` items) first, then [clip] items not already among them. Null when the intent is
 * not a share of pictures ([action] is not SEND or SEND_MULTIPLE, or [type] is not `image/…`), so a
 * shared PDF still opens as a document. Generic so the rule is tested without Android's Uri.
 */
internal fun <T : Any> scanSharedImages(action: String?, type: String?, streams: List<T?>, clip: List<T?>): List<T>? {
    if (action != "android.intent.action.SEND" && action != "android.intent.action.SEND_MULTIPLE") return null
    if (type == null || !type.trim().lowercase().startsWith("image/")) return null
    return (streams.filterNotNull() + clip.filterNotNull()).distinct()
}

/** Whether a copied shared file is a picture that can be read: its header gives a size. */
internal fun scanSharedReadable(width: Int, height: Int): Boolean = width > 0 && height > 0
