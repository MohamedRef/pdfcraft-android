package io.github.artcraftandroid.pdfcraft.ui.viewer

import kotlin.math.pow

/**
 * Distinct pen behaviors. The engine stores width-run PDF Ink, while freehand highlighter
 * uses the engine's dedicated highlight annotation. No proprietary brush assets are used.
 */
internal enum class InkStyle(val key: String) {
    Ballpoint("ballpoint"),
    Fountain("fountain"),
    Pencil("pencil"),
    Brush("brush"),
    Highlighter("highlighter");

    companion object {
        fun fromKey(value: String?): InkStyle? = entries.firstOrNull { it.key == value }
    }
}

/** Snapshots are taken on pen-down, so changing style cannot recolor a prior stroke. */
internal fun inkStyleOpacity(opacity: Float, style: InkStyle): Float {
    val safe = opacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    return when (style) {
        InkStyle.Pencil -> safe * 0.8f
        InkStyle.Highlighter -> safe * 0.4f
        else -> safe
    }
}

/**
 * Width and pressure profiles differ even with touch input:
 * Ballpoint = existing behavior, Fountain = pronounced pressure variation,
 * Pencil = fine/soft, Brush = bold and pressure-responsive, Highlighter = flat broad marker.
 * Output uses the existing bounded, deterministic native PDF width-run representation.
 */
internal fun inkStyleWidthRuns(
    pressures: List<Float>,
    width: Float,
    stylus: Boolean,
    style: InkStyle,
): List<InkWidthRun> {
    val safeWidth = width.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.1f, 30f) ?: 1f
    val factor = when (style) {
        InkStyle.Ballpoint -> 1f
        InkStyle.Fountain -> 1.12f
        InkStyle.Pencil -> 0.7f
        InkStyle.Brush -> 1.55f
        InkStyle.Highlighter -> 2.25f
    }
    val effectivePressure = if (stylus) when (style) {
        InkStyle.Fountain -> pressures.map { p -> p.takeIf { it.isFinite() && it > 0f }?.coerceIn(0f, 1f)?.pow(1.6f) ?: 1f }
        InkStyle.Brush -> pressures.map { p -> p.takeIf { it.isFinite() && it > 0f }?.coerceIn(0f, 1f)?.pow(1.35f) ?: 1f }
        else -> pressures
    } else pressures
    return inkWidthRuns(effectivePressure, safeWidth * factor, stylus && style != InkStyle.Highlighter)
}

internal fun inkStyleEngineType(style: InkStyle): String =
    if (style == InkStyle.Highlighter) "freehand_highlight" else "ink"

/**
 * One width per point for pressure ink saved as a single drawing (engine `comment_add` `widths`).
 * Same profile as [inkStyleWidthRuns], unquantized: base × style factor × (0.35 + 0.65 × pressure).
 */
internal fun inkStylePointWidths(pressures: List<Float>, width: Float, style: InkStyle): List<Float> {
    val safeWidth = width.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.1f, 30f) ?: 1f
    val factor = when (style) {
        InkStyle.Ballpoint -> 1f
        InkStyle.Fountain -> 1.12f
        InkStyle.Pencil -> 0.7f
        InkStyle.Brush -> 1.55f
        InkStyle.Highlighter -> 2.25f
    }
    return pressures.map { raw ->
        val p = raw.takeIf { it.isFinite() && it > 0f }?.coerceIn(0f, 1f) ?: 1f
        val shaped = when (style) {
            InkStyle.Fountain -> p.pow(1.6f)
            InkStyle.Brush -> p.pow(1.35f)
            else -> p
        }
        safeWidth * factor * (0.35f + 0.65f * shaped)
    }
}
