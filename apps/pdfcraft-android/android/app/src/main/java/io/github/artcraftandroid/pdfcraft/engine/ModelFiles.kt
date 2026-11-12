package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** One file of the app's bundled models, as the build listed it: name, size and SHA-256 (lowercase hex). */
internal data class ModelEntry(val name: String, val bytes: Long, val sha256: String)

/** Why a model file was copied out of the app, or kept as it was. */
internal enum class ModelCopyReason {
    /** No file of that name in the models folder yet. */
    New,

    /** The app holds a different file under that name than the one copied last time (an update replaced it). */
    Changed,

    /** The file in the folder is not the size the app's file is (a copy cut short, or changed on disk). */
    Size,

    /** A file is there, but nothing says which one: it was copied by a build before this list existed. */
    Unrecorded,

    /** Asked for: every file is copied again (the testing option). */
    Forced,
}

/** What [ModelFiles.install] did: the files copied and why, the files kept, the files whose copy did not match. */
internal data class ModelInstallReport(
    val copied: List<Pair<String, ModelCopyReason>>,
    val kept: List<String>,
    val failed: List<String>,
    val ms: Long,
) {
    /** Diagnostics fields: counts and the reasons, as short words only (never a path). */
    fun diag(): Array<Pair<String, Any?>> = arrayOf(
        "copied" to copied.size, "kept" to kept.size, "failed" to failed.size, "ms" to ms,
        "new" to copied.count { it.second == ModelCopyReason.New },
        "changed" to copied.count { it.second == ModelCopyReason.Changed },
        "size" to copied.count { it.second == ModelCopyReason.Size },
        "unrecorded" to copied.count { it.second == ModelCopyReason.Unrecorded },
        "forced" to copied.count { it.second == ModelCopyReason.Forced },
    )
}

/**
 * Copies the models bundled in the app into a folder of their own, where the engine reads them
 * (text recognition and the scanner, #289). Before this, a file was copied only when no file of
 * its name was there yet: a model *replaced* under the same name by an app update was never copied,
 * failed its pinned SHA-256 check in the engine and switched its feature off without a word.
 *
 * The build writes [INDEX] beside the models: one line per file with its size and SHA-256. After
 * copying, the same lines go into [INSTALLED] in the folder. A file is copied again when its line
 * in the app differs from the line recorded for it, when the file on disk is not that size, or
 * when there is no record. Each copy is hashed as it is written and renamed into place only when it
 * matches, so a half-written file is never left under the model's name.
 */
internal object ModelFiles {
    /** The list the build writes into the app's `models` assets. */
    const val INDEX = "models.index"

    /** The list of what was copied, kept in the models folder. */
    const val INSTALLED = ".installed.index"

    /** Lines of `name<TAB>bytes<TAB>sha256`; anything else is skipped. */
    fun parseIndex(text: String): List<ModelEntry> = text.lineSequence().mapNotNull { line ->
        val parts = line.trim().split('\t')
        val bytes = parts.getOrNull(1)?.toLongOrNull()
        val sha = parts.getOrNull(2)?.lowercase()
        if (parts.size == 3 && parts[0].isNotEmpty() && !parts[0].contains('/') && bytes != null && bytes >= 0 && sha != null && SHA.matches(sha)) {
            ModelEntry(parts[0], bytes, sha)
        } else {
            null
        }
    }.toList()

    fun formatIndex(entries: List<ModelEntry>): String = entries.joinToString("") { "${it.name}\t${it.bytes}\t${it.sha256}\n" }

    /**
     * Which of the app's files [bundled] must be copied, and why; the rest are kept. [installed] is
     * what was recorded last time and [sizeOnDisk] the size of a file in the folder (null when absent).
     */
    fun plan(bundled: List<ModelEntry>, installed: List<ModelEntry>, sizeOnDisk: (String) -> Long?, force: Boolean = false): List<Pair<ModelEntry, ModelCopyReason?>> {
        val recorded = installed.associateBy { it.name }
        return bundled.map { entry ->
            val size = sizeOnDisk(entry.name)
            val record = recorded[entry.name]
            entry to when {
                size == null -> ModelCopyReason.New
                force -> ModelCopyReason.Forced
                record == null -> ModelCopyReason.Unrecorded
                record != entry -> ModelCopyReason.Changed
                size != entry.bytes -> ModelCopyReason.Size
                else -> null
            }
        }
    }

    /**
     * Bring [dir] up to date with the app's files [bundled], read through [open]. Blocking: call
     * it off the main thread. A file whose copy does not match its listed size and SHA-256 is
     * left out (and out of the record, so the next run tries again).
     */
    fun install(dir: File, bundled: List<ModelEntry>, open: (String) -> InputStream, force: Boolean = false): ModelInstallReport {
        val started = System.nanoTime()
        dir.mkdirs()
        val installedFile = File(dir, INSTALLED)
        val installed = runCatching { parseIndex(installedFile.readText()) }.getOrDefault(emptyList())
        val copied = mutableListOf<Pair<String, ModelCopyReason>>()
        val kept = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val record = mutableListOf<ModelEntry>()
        for ((entry, reason) in plan(bundled, installed, { name -> File(dir, name).takeIf { it.isFile }?.length() }, force)) {
            if (reason == null) {
                kept += entry.name
                record += entry
            } else if (copy(dir, entry, open)) {
                copied += entry.name to reason
                record += entry
            } else {
                failed += entry.name
            }
        }
        // what is recorded for files the app no longer has is dropped with them from the list
        if (copied.isNotEmpty() || failed.isNotEmpty() || installed.toSet() != record.toSet()) {
            val tmp = File(dir, "$INSTALLED.part")
            tmp.writeText(formatIndex(record))
            if (!tmp.renameTo(installedFile)) {
                installedFile.delete()
                tmp.renameTo(installedFile)
            }
        }
        return ModelInstallReport(copied, kept, failed, (System.nanoTime() - started) / 1_000_000)
    }

    /** Copy one file through a `.part` file, hashing it on the way; true when it matched and is in place. */
    private fun copy(dir: File, entry: ModelEntry, open: (String) -> InputStream): Boolean {
        val target = File(dir, entry.name)
        val tmp = File(dir, "${entry.name}.part")
        val ok = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            open(entry.name).use { input ->
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        bytes += n
                    }
                }
            }
            bytes == entry.bytes && hex(digest.digest()) == entry.sha256
        }.getOrDefault(false)
        if (!ok) {
            tmp.delete()
            return false
        }
        // renameTo does not replace an existing file on every file system
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return false
            }
        }
        return true
    }

    /**
     * The entries for the files [names] when the build wrote no [INDEX] (an older build script):
     * each file is hashed once from the app. Blocking.
     */
    fun indexFromFiles(names: List<String>, open: (String) -> InputStream): List<ModelEntry> = names.filter { it != INDEX && !it.endsWith(".part") }.mapNotNull { name ->
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            open(name).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    bytes += n
                }
            }
            ModelEntry(name, bytes, hex(digest.digest()))
        }.getOrNull()
    }

    private val SHA = Regex("[0-9a-f]{64}")

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
