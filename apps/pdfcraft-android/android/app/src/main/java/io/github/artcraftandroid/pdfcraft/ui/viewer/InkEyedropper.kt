package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset

/** A saved comment the eyedropper can sample: its page rectangle, colour and whether it is ink. */
internal data class EyedropperTarget(val rect: List<Float>, val rgb: Int, val ink: Boolean)

/** An unsaved draft stroke: its page points, half width and colour. */
internal data class EyedropperStroke(val points: List<Offset>, val halfWidth: Float, val argb: Int)

/**
 * The colour under [at] (page points) for the pen eyedropper (#149): the nearest draft stroke
 * within [tolerance] of its edge wins; otherwise the saved comment whose rectangle contains the
 * point, ink before other kinds and the smallest rectangle first. Saved colours are opaque.
 */
internal fun eyedropperColor(drafts: List<EyedropperStroke>, saved: List<EyedropperTarget>, at: Offset, tolerance: Float): Int? {
    val draft = drafts.mapNotNull { s ->
        val d = s.points.minOfOrNull { (it - at).getDistance() } ?: return@mapNotNull null
        (d - s.halfWidth).takeIf { it <= tolerance }?.let { it to s.argb }
    }.minByOrNull { it.first }
    if (draft != null) return draft.second
    return saved.filter { t ->
        t.rect.size == 4 && t.rect.all { it.isFinite() } &&
            at.x in minOf(t.rect[0], t.rect[2]) - tolerance..maxOf(t.rect[0], t.rect[2]) + tolerance &&
            at.y in minOf(t.rect[1], t.rect[3]) - tolerance..maxOf(t.rect[1], t.rect[3]) + tolerance
    }.sortedWith(compareBy({ !it.ink }, { kotlin.math.abs((it.rect[2] - it.rect[0]) * (it.rect[3] - it.rect[1])) }))
        .firstOrNull()?.let { (0xFF000000.toInt()) or (it.rgb and 0xFFFFFF) }
}
