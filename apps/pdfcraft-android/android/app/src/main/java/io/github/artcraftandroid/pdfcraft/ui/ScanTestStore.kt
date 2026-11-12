package io.github.artcraftandroid.pdfcraft.ui

import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The scans kept for testing (#288): one folder per scan under [root], in the app's private
 * storage. A page is its camera photo as taken (`page-01.jpg`), its facts (`page-01.json`, see
 * [scanTestPageJson]) and, for a saved scan, the page the app made of it (`page-01.out.jpg` or
 * `.png`). They hold the documents themselves, so nothing leaves the device unless the person
 * shares the zip, and nothing is kept unless they turned keeping on.
 *
 * Every call is blocking file work: call off the main thread. Nothing here throws; keeping a scan
 * for testing must never stand in the way of saving it.
 */
internal class ScanTestStore(private val root: File) {
    data class Summary(val pages: Int, val bytes: Long)

    private fun scans(): List<File> = root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }

    private fun isPage(f: File) = f.isFile && f.name.startsWith("page-") && f.name.endsWith(".json")

    /** How many pages are kept, and the size of everything kept. */
    fun summary(): Summary {
        var pages = 0
        var bytes = 0L
        for (scan in scans()) {
            for (f in scan.listFiles().orEmpty()) {
                if (isPage(f)) pages++
                if (f.isFile) bytes += f.length()
            }
        }
        return Summary(pages, bytes)
    }

    /** A new folder for one scan, named for [stamp] (a time; made unique); null when it cannot be made. */
    fun newScan(stamp: String): File? = runCatching {
        val safe = stamp.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "scan" }
        var dir = File(root, safe)
        var n = 2
        while (dir.exists()) dir = File(root, "$safe-${n++}")
        dir.takeIf { it.mkdirs() }
    }.getOrNull()

    /**
     * Keep page [index] (from 0) of the scan in [scan]: a copy of the camera's [photo], a copy of
     * the page made of it when there is one ([output]), and its facts. [json] is given the name
     * the output was kept under, or null.
     */
    fun addPage(scan: File, index: Int, photo: File, output: File?, json: (String?) -> String) {
        runCatching {
            val name = scanTestName(index)
            val photoType = photo.extension.lowercase().takeIf { it in PHOTO_TYPES } ?: "jpg"
            photo.copyTo(File(scan, "$name.$photoType"), overwrite = true)
            val kept = output?.takeIf { it.isFile }?.let { out ->
                val type = out.extension.lowercase().takeIf { it in PHOTO_TYPES } ?: "jpg"
                File(scan, "$name.out.$type").also { out.copyTo(it, overwrite = true) }.name
            }
            File(scan, "$name.json").writeText(json(kept))
        }
    }

    /** A scan folder nothing was kept in is removed. */
    fun finish(scan: File) {
        runCatching { if (scan.listFiles().isNullOrEmpty()) scan.delete() }
    }

    fun clear() {
        runCatching { root.deleteRecursively() }
    }

    /** The oldest scans go until what is kept is no more than [maxBytes]; the newest scan always stays. */
    fun trim(maxBytes: Long) {
        runCatching {
            val all = scans()
            var total = all.sumOf { scan -> scan.listFiles().orEmpty().sumOf { it.length() } }
            for (scan in all.dropLast(1)) {
                if (total <= maxBytes) break
                total -= scan.listFiles().orEmpty().sumOf { it.length() }
                scan.deleteRecursively()
            }
        }
    }

    /**
     * Everything kept as one zip at [target], each scan in its folder, with the text files of
     * [extra] (name to text) beside them. Returns the pages in it; 0 (and no file) when nothing
     * is kept or it could not be written.
     */
    fun zip(target: File, extra: Map<String, String>): Int = runCatching {
        val all = scans()
        val pages = all.sumOf { scan -> scan.listFiles().orEmpty().count(::isPage) }
        if (pages == 0) {
            target.delete()
            return 0
        }
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            // photos are compressed already: fast is enough
            zip.setLevel(Deflater.BEST_SPEED)
            for ((name, text) in extra) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            for (scan in all) {
                for (f in scan.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }) {
                    zip.putNextEntry(ZipEntry("${scan.name}/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        pages
    }.getOrElse {
        target.delete()
        0
    }

    companion object {
        /** What is kept at most: older scans make room for newer ones. */
        const val MAX_BYTES = 600L * 1024 * 1024

        private val PHOTO_TYPES = setOf("jpg", "jpeg", "png")
    }
}
