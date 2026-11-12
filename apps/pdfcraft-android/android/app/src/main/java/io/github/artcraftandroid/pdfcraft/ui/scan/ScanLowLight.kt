package io.github.artcraftandroid.pdfcraft.ui.scan

import java.io.File
import org.json.JSONObject

/*
 * Low-light merge (#297), as pure code tested on the JVM: when the camera takes a burst of four
 * stills instead of one, which files the merge gets, and what is kept when it fails. The merge
 * itself is the native `scan_merge_frames` (#389), which straightens each frame by its own
 * corners, aligns what is left and averages robustly.
 */

/**
 * The setting: merge when the scene is dark (Auto), every time (Always), or never (Off). [DEFAULT]
 * is Off (#426): a merged page enters the scan already cropped by the detector's outlines, with
 * the photos deleted, so Crop cannot widen it. Reason in `docs/ANDROID_SCAN_MERGE.md`.
 */
internal enum class ScanMergeSetting(val id: String) {
    Auto("auto"),
    Always("always"),
    Off("off"),
    ;

    companion object {
        /** Off until the merge has been tried on the owner's phone (#426). */
        val DEFAULT = Off

        fun decode(id: String?): ScanMergeSetting = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Low-light merge's thresholds: the issue's starting values. */
internal object ScanMergeRules {
    /** Stills in a burst. */
    const val FRAMES = 4

    /** Fewer frames whose page was found than this, and nothing is merged. */
    const val MIN_FRAMES = 2

    /** The live frame's mean brightness under which the scene is dark. */
    const val DARK_LUMA = 70f

    /** The sensor sensitivity from which the scene is dark. */
    const val DARK_ISO = 800

    /** The memory class (MB) a phone needs for a merge: the reference is held twice at 12 MP. */
    const val MEMORY_MB = 256

    /** A burst slower than this is worth noting (the issue's 2.5 s). */
    const val SLOW_BURST_MS = 2_500L
}

/** Why a burst was or was not taken; [burst] says which. */
internal enum class ScanBurstWhy(val id: String, val burst: Boolean) {
    Off("off", false),
    Memory("memory", false),
    Bright("bright", false),
    Unknown("unknown", false),
    Always("always", true),
    DarkLuma("dark_luma", true),
    DarkIso("dark_iso", true),
}

/**
 * Whether to take a burst: never Off or on a phone under [ScanMergeRules.MEMORY_MB]; always when
 * asked; on Auto when the live frame is darker than [ScanMergeRules.DARK_LUMA] or the sensor is at
 * [ScanMergeRules.DARK_ISO] or more. [luma] is null without the live outline, [iso] without a
 * capture result; with neither known, Auto takes one photo.
 */
internal fun scanBurstWhy(setting: ScanMergeSetting, memoryClassMb: Int, luma: Float?, iso: Int?): ScanBurstWhy = when {
    setting == ScanMergeSetting.Off -> ScanBurstWhy.Off
    memoryClassMb < ScanMergeRules.MEMORY_MB -> ScanBurstWhy.Memory
    setting == ScanMergeSetting.Always -> ScanBurstWhy.Always
    luma != null && luma < ScanMergeRules.DARK_LUMA -> ScanBurstWhy.DarkLuma
    iso != null && iso >= ScanMergeRules.DARK_ISO -> ScanBurstWhy.DarkIso
    luma == null && iso == null -> ScanBurstWhy.Unknown
    else -> ScanBurstWhy.Bright
}

/** The burst's photo files: the first is the camera's own target, the others sit beside it. */
internal fun scanBurstFiles(first: File, count: Int = ScanMergeRules.FRAMES): List<File> =
    listOf(first) + (2..count).map { File(first.parentFile, "${first.nameWithoutExtension}-b$it.${first.extension.ifEmpty { "jpg" }}") }

/** Where the merged page is written, beside the burst. */
internal fun scanMergedFile(first: File): File = File(first.parentFile, "${first.nameWithoutExtension}-merged.jpg")

/**
 * The frames to merge: each photo with the corners its own detection found, in burst order. A
 * frame whose page was not found is left out. Null when fewer than [ScanMergeRules.MIN_FRAMES]
 * remain.
 */
internal fun scanMergeFrames(photos: List<File>, corners: List<FloatArray?>): List<Pair<File, FloatArray>>? {
    val frames = photos.zip(corners).mapNotNull { (f, c) -> c?.takeIf { it.size == 8 && it.all(Float::isFinite) }?.let { f to it } }
    return frames.takeIf { it.size >= ScanMergeRules.MIN_FRAMES }
}

/**
 * What the scan gets after a burst: the merged page when it was written, else the first photo
 * (the issue: "if the merge fails, keep the reference frame"). [delete] are the files no longer
 * needed: every burst photo after a merge, the extra photos otherwise.
 */
internal data class ScanBurstOutcome(val keep: File, val merged: Boolean, val delete: List<File>)

internal fun scanBurstOutcome(photos: List<File>, merged: File?): ScanBurstOutcome =
    if (merged != null) ScanBurstOutcome(merged, true, photos) else ScanBurstOutcome(photos.first(), false, photos.drop(1))

/** A burst's facts, for the log and a kept scan's page file. */
internal data class ScanMergeFacts(
    val why: String,
    val frames: Int = 0,
    val found: Int = 0,
    val merged: Boolean = false,
    val used: Int = 0,
    val rejectedBlur: Int = 0,
    val rejectedAlign: Int = 0,
    val psnrGainTenthsDb: Int = 0,
    val burstMs: Long = 0L,
    val findMs: Long = 0L,
    val mergeMs: Long = 0L,
    /** Why nothing was merged: `none`, `few_found`, `failed`, or `shots_failed`. */
    val fallback: String = "none",
) {
    fun diag(): Array<Pair<String, Any?>> = arrayOf(
        "why" to why, "frames" to frames, "found" to found, "merged" to merged, "used" to used,
        "rejected_blur" to rejectedBlur, "rejected_align" to rejectedAlign, "psnr_gain_tenths_db" to psnrGainTenthsDb,
        "burst_ms" to burstMs, "find_ms" to findMs, "merge_ms" to mergeMs, "fallback" to fallback,
        "slow_burst" to (burstMs > ScanMergeRules.SLOW_BURST_MS),
    )

    fun json(): JSONObject = JSONObject()
        .put("why", why).put("frames", frames).put("found", found).put("merged", merged).put("used", used)
        .put("rejected_blur", rejectedBlur).put("rejected_align", rejectedAlign).put("psnr_gain_tenths_db", psnrGainTenthsDb)
        .put("burst_ms", burstMs).put("find_ms", findMs).put("merge_ms", mergeMs).put("fallback", fallback)
}
