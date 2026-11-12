package io.github.artcraftandroid.pdfcraft.engine

import io.github.artcraftandroid.pdfcraft.ui.home.ApkResumeAction
import io.github.artcraftandroid.pdfcraft.ui.home.MAX_APK_NETWORK_RETRIES
import io.github.artcraftandroid.pdfcraft.ui.home.apkResumeAction
import io.github.artcraftandroid.pdfcraft.ui.home.apkRetryBudgetResets
import io.github.artcraftandroid.pdfcraft.ui.home.apkRetryDelayMillis
import io.github.artcraftandroid.pdfcraft.ui.home.apkRetryable
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** An optional model (#289, section 4): its file name in the models folder, where it comes from, and what it must be. */
internal data class ModelPack(val id: String, val file: String, val url: String, val bytes: Long, val sha256: String)

/** One HTTP answer, as [ModelDownload] needs it. */
internal class PackResponse(
    val status: Int,
    val contentRange: String?,
    /** Content-Length, or -1 when not given. */
    val length: Long,
    val encoding: String?,
    /** Where the request ended up after redirects. */
    val finalUrl: String,
    val body: () -> InputStream,
    val close: () -> Unit = {},
)

/** How a download ended. */
internal enum class PackOutcome { Done, Cancelled, Failed }

/** What a download did, for the Models list and the diagnostics log. */
internal data class PackResult(
    val outcome: PackOutcome,
    /** A short lowercase word for the log: `ok`, `cancelled`, `untrusted`, `network`, `server`, `mismatch`, `storage`. */
    val reason: String,
    val bytes: Long,
    /** Bytes that were already saved from an earlier attempt when this one started. */
    val resumed: Long,
    val retries: Int,
    val ms: Long,
) {
    fun diag(id: String): Array<Pair<String, Any?>> = arrayOf(
        "id" to id, "outcome" to outcome.name.lowercase(), "reason" to reason, "bytes" to bytes, "resumed" to resumed, "retries" to retries, "ms" to ms,
    )
}

/**
 * Downloads an optional model into the models folder (#289, section 4). Nothing is moved to a
 * download yet (the owner chose on 12 November 2026 to keep every model in the app); this is the
 * code a pack will use.
 *
 * It follows the in-app updater's rules and reuses its pieces ([apkResumeAction], [apkRetryable],
 * the retry delays and the retry budget that resets when bytes are saved): HTTPS only, from this
 * repository's `scan-models-*` releases, redirected only to GitHub's own hosts; the download
 * goes to `<file>.part` and resumes with an HTTP Range that must match exactly; the whole file
 * must have the pinned size and SHA-256 before it is renamed into place. A cancelled or
 * interrupted download keeps its `.part` for next time; one that does not match is deleted.
 */
internal object ModelDownload {
    /** Every optional model is an asset of a release of this repository with a `scan-models-` tag (`scan/src/models.rs` has the same). */
    const val URL_PREFIX = "https://github.com/MohamedRef/pdfcraft-android/releases/download/scan-models-"

    /** The largest optional model accepted, so a wrong size in a build cannot fill the phone. */
    const val MAX_BYTES = 200L * 1024 * 1024

    private val SHA = Regex("[0-9a-f]{64}")
    private val FILE = Regex("[a-z0-9][a-z0-9._-]{0,63}")

    /** Whether [pack] may be fetched at all: its address, name, size and hash are of the expected form. */
    fun trusted(pack: ModelPack): Boolean {
        if (!pack.url.startsWith(URL_PREFIX) || !FILE.matches(pack.file) || pack.file.endsWith(".part")) return false
        val rest = pack.url.removePrefix(URL_PREFIX).split('/')
        return rest.size == 2 && Regex("[0-9A-Za-z.-]{1,32}").matches(rest[0]) && rest[1] == pack.file &&
            pack.bytes in 1..MAX_BYTES && SHA.matches(pack.sha256)
    }

    /** A redirect may only land on GitHub's own HTTPS hosts. */
    fun trustedHost(url: String): Boolean = runCatching {
        val u = URL(url)
        u.protocol == "https" && (u.host == "github.com" || u.host.endsWith(".githubusercontent.com"))
    }.getOrDefault(false)

    fun partFile(dir: File, pack: ModelPack) = File(dir, "${pack.file}.part")

    /** Bytes saved by an earlier attempt (for "Resume"), 0 when none. */
    fun savedBytes(dir: File, pack: ModelPack): Long = partFile(dir, pack).takeIf { it.isFile && it.length() in 1..pack.bytes }?.length() ?: 0L

    /** Delete the model and anything saved of it. True when nothing of it is left. */
    fun remove(dir: File, pack: ModelPack): Boolean {
        File(dir, pack.file).delete()
        partFile(dir, pack).delete()
        return !File(dir, pack.file).exists() && !partFile(dir, pack).exists()
    }

    /**
     * Fetch [pack] into [dir]. Blocking: call off the main thread. [open] makes one request from
     * an offset; [cancelled] is polled between reads; [progress] gets 0–100; [sleep] waits
     * between retries (tests pass a no-op).
     */
    fun download(
        dir: File,
        pack: ModelPack,
        open: (String, Long) -> PackResponse = ::httpsOpen,
        cancelled: () -> Boolean = { false },
        progress: (Int) -> Unit = {},
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): PackResult {
        val started = System.nanoTime()
        fun result(outcome: PackOutcome, reason: String, resumed: Long, retries: Int) =
            PackResult(outcome, reason, partFile(dir, pack).length().takeIf { outcome != PackOutcome.Done } ?: pack.bytes, resumed, retries, (System.nanoTime() - started) / 1_000_000)
        if (!trusted(pack)) return result(PackOutcome.Failed, "untrusted", 0, 0)
        if (!dir.isDirectory && !dir.mkdirs()) return result(PackOutcome.Failed, "storage", 0, 0)
        val part = partFile(dir, pack)
        if (part.length() > pack.bytes) part.delete()
        val resumedAtStart = part.length()
        var retries = 0
        var mark = -1L
        var freshRetryUsed = false
        while (part.length() < pack.bytes) {
            if (cancelled()) return result(PackOutcome.Cancelled, "cancelled", resumedAtStart, retries)
            try {
                val offset = part.length()
                val response = open(pack.url, offset)
                try {
                    if (!trustedHost(response.finalUrl)) return result(PackOutcome.Failed, "untrusted", resumedAtStart, retries)
                    val action = apkResumeAction(offset, response.status, response.contentRange, pack.bytes)
                    if (action == ApkResumeAction.RETRY_WITHOUT_RANGE) {
                        if (freshRetryUsed) return result(PackOutcome.Failed, "server", resumedAtStart, retries)
                        freshRetryUsed = true
                        part.delete()
                        continue
                    }
                    val from = if (action == ApkResumeAction.APPEND) offset else 0L
                    if (response.length > pack.bytes - from || response.length < -1L || response.encoding?.lowercase() !in listOf(null, "identity")) {
                        return result(PackOutcome.Failed, "server", resumedAtStart, retries)
                    }
                    var received = from
                    var percent = (received * 100 / pack.bytes).toInt()
                    progress(percent)
                    response.body().use { input ->
                        FileOutputStream(part, from > 0L).buffered().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (cancelled()) return result(PackOutcome.Cancelled, "cancelled", resumedAtStart, retries)
                                val n = input.read(buffer)
                                if (n < 0) break
                                received += n
                                if (received > pack.bytes) return result(PackOutcome.Failed, "server", resumedAtStart, retries).also { part.delete() }
                                out.write(buffer, 0, n)
                                val now = (received * 100 / pack.bytes).toInt()
                                if (now > percent) {
                                    percent = now
                                    progress(now)
                                }
                            }
                        }
                    }
                    if (received != pack.bytes) throw EOFException("the download ended early")
                } finally {
                    response.close()
                }
            } catch (e: IOException) {
                val saved = part.length()
                if (apkRetryBudgetResets(mark, saved)) {
                    mark = saved
                    retries = 0
                }
                if (!apkRetryable(e)) return result(PackOutcome.Failed, if (e is java.io.FileNotFoundException) "storage" else "server", resumedAtStart, retries)
                if (retries >= MAX_APK_NETWORK_RETRIES) return result(PackOutcome.Failed, "network", resumedAtStart, retries)
                retries++
                sleep(apkRetryDelayMillis(retries))
            }
        }
        // the whole file, hashed, before it takes the model's name
        if (part.length() != pack.bytes || sha256(part) != pack.sha256) {
            part.delete()
            return result(PackOutcome.Failed, "mismatch", resumedAtStart, retries)
        }
        val target = File(dir, pack.file)
        if (!part.renameTo(target)) {
            target.delete()
            if (!part.renameTo(target)) return result(PackOutcome.Failed, "storage", resumedAtStart, retries)
        }
        progress(100)
        return result(PackOutcome.Done, "ok", resumedAtStart, retries)
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** One HTTPS request, as the updater makes it. */
    private fun httpsOpen(url: String, offset: Long): PackResponse {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 40_000
        connection.setRequestProperty("User-Agent", "PdfCraft-Android-Models")
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")
        val status = connection.responseCode
        return PackResponse(
            status, connection.getHeaderField("Content-Range"), connection.contentLengthLong, connection.contentEncoding,
            connection.url.toString(), { connection.inputStream }, { connection.disconnect() },
        )
    }
}
