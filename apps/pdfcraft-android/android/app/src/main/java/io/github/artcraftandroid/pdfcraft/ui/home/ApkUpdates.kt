package io.github.artcraftandroid.pdfcraft.ui.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Only published assets from this owner's public GitHub Releases are valid update sources. */
internal const val UPDATE_RELEASES_URL =
    "https://api.github.com/repos/MohamedRef/pdfcraft-android/releases?per_page=100"

private const val RELEASE_DOWNLOAD_ROOT =
    "https://github.com/MohamedRef/pdfcraft-android/releases/download/"
private const val APK_MIME = "application/vnd.android.package-archive"
private const val MAX_APK_BYTES = 300L * 1024L * 1024L

internal data class ApkUpdate(
    val version: String,
    val channel: String,
    val versionBase: Long,
    val assetName: String,
    val downloadUrl: String,
    val publishedAt: String,
    val notes: String,
    val size: Long,
    val sha256: String,
) {
    // Gradle's single-ABI code: multiply the base by 10, append 2 for arm64-v8a.
    val apkVersionCode: Long get() = versionBase * 10L + 2L
}

internal fun updateVersionBase(version: String): Long? {
    val match = Regex("""(\d+)\.(\d+)\.(\d+)(-[0-9A-Za-z-]+?(\.(\d+))?)?""")
        .matchEntire(version) ?: return null
    val major = match.groupValues[1].toLongOrNull() ?: return null
    val minor = match.groupValues[2].toLongOrNull() ?: return null
    val patch = match.groupValues[3].toLongOrNull() ?: return null
    val prerelease = match.groupValues[4].isNotEmpty()
    // Gradle reserves 99 exclusively for a stable release; 1..98 are prereleases.
    val pre = if (prerelease) match.groupValues[6].toLongOrNull() ?: 1L else 99L
    if (minor !in 0..99 || patch !in 0..99 || (prerelease && pre !in 1..98)) return null
    return try {
        val base = Math.addExact(
            Math.multiplyExact(Math.addExact(Math.multiplyExact(major, 100L), minor), 10000L),
            Math.addExact(Math.multiplyExact(patch, 100L), pre),
        )
        base.takeIf { it > 0 && it <= (Int.MAX_VALUE - 2L) / 10L }
    } catch (_: ArithmeticException) {
        null
    }
}

internal fun updateChannel(version: String): String = when {
    version.contains("-alpha", true) -> "Alpha"
    version.contains("-beta", true) -> "Beta"
    version.contains("-preview", true) || version.contains("-rc", true) -> "Preview"
    else -> "Stable"
}

/** Release assets uploaded by GitHub have a digest. Missing/invalid digest fails closed. */
internal fun releaseSha256(digest: String?): String? {
    if (digest?.startsWith("sha256:") != true) return null
    val raw = digest.removePrefix("sha256:")
    if (raw.length != 64 || !raw.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return raw.lowercase()
}

internal fun parseUpdates(json: String, installedVersionCode: Long): List<ApkUpdate> {
    val releases = JSONArray(json)
    val candidates = mutableListOf<ApkUpdate>()
    for (i in 0 until releases.length()) {
        val release = releases.optJSONObject(i) ?: continue
        if (release.optBoolean("draft", false)) continue
        val tag = release.optString("tag_name")
        val version = tag.removePrefix("v")
        val base = updateVersionBase(version) ?: continue
        val versionCode = base * 10L + 2L
        if (versionCode <= installedVersionCode) continue
        val assets = release.optJSONArray("assets") ?: continue
        for (j in 0 until assets.length()) {
            val asset = assets.optJSONObject(j) ?: continue
            val name = asset.optString("name")
            if (name != "pdfcraft-android-$version.apk") continue
            if (!Regex("""pdfcraft-android-[0-9][0-9A-Za-z.\-]*\.apk""").matches(name)) continue
            val url = asset.optString("browser_download_url")
            // An asset path must belong to the advertised release tag and have the exact filename.
            if (url != "$RELEASE_DOWNLOAD_ROOT$tag/$name") continue
            val sha256 = releaseSha256(asset.optString("digest")) ?: continue
            val size = asset.optLong("size")
            if (size <= 0 || size > MAX_APK_BYTES) continue
            candidates += ApkUpdate(
                version, updateChannel(version), base, name, url,
                release.optString("published_at"), release.optString("body"), size, sha256,
            )
        }
    }
    return candidates.sortedWith(compareByDescending<ApkUpdate> { it.apkVersionCode }.thenByDescending { it.publishedAt })
}

internal suspend fun fetchApkUpdates(installedVersionCode: Long): List<ApkUpdate> =
    withContext(Dispatchers.IO) {
        val connection = URL(UPDATE_RELEASES_URL).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "PdfCraft-Android-UpdateChecker")
            if (connection.responseCode != HttpsURLConnection.HTTP_OK) {
                throw IOException("GitHub Releases returned HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader().use { parseUpdates(it.readText(), installedVersionCode) }
        } finally {
            connection.disconnect()
        }
    }

internal fun installedVersionCode(context: Context): Long {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return info.longVersionCode
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

/** Check both the downloaded APK's SHA-256 and its Android upgrade identity/signers. */
internal fun verifyDownloadedApk(context: Context, apk: File, update: ApkUpdate) {
    val digest = MessageDigest.getInstance("SHA-256")
    apk.inputStream().buffered().use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    if (!digest.digest().toHex().equals(update.sha256, ignoreCase = true)) {
        throw IOException("The APK checksum does not match its published GitHub Release.")
    }
    val manager = context.packageManager
    @Suppress("DEPRECATION")
    val archive: PackageInfo = manager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        ?: throw IOException("The downloaded file is not a valid signed Android APK.")
    @Suppress("DEPRECATION")
    val installed: PackageInfo = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    if (archive.packageName != context.packageName || archive.longVersionCode != update.apkVersionCode) {
        throw IOException("The downloaded APK package or build number is not the expected update.")
    }
    val newSigners = archive.signingInfo?.apkContentsSigners?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }?.toSet()
    val existingSigners = installed.signingInfo?.apkContentsSigners?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }?.toSet()
    if (newSigners.isNullOrEmpty() || newSigners != existingSigners) {
        throw IOException("The APK certificate is not the installed app's signing certificate.")
    }
    val minSdk = archive.applicationInfo?.minSdkVersion ?: 0
    if (minSdk > Build.VERSION.SDK_INT) throw IOException("This APK requires a newer Android version.")
    ZipFile(apk).use { zip ->
        if (zip.getEntry("lib/arm64-v8a/libpdfcraft_android.so") == null) {
            throw IOException("The APK is missing PdfCraft's ARM64 native engine.")
        }
    }
}

/** Private persistent storage is not auto-evicted like cacheDir; interrupted APKs can resume later. */
internal fun apkUpdateDirectory(context: Context): File = File(context.filesDir, "updates")

internal fun apkCachedBytes(context: Context, update: ApkUpdate): Long {
    val partial = File(apkUpdateDirectory(context), apkPartialFileName(update.sha256))
    return partial.takeIf { it.isFile && it.length() in 1L..update.size }?.length() ?: 0L
}

/**
 * Continue a content-addressed partial APK using HTTP Range; never install or expose partial data.
 * Exact metadata, HTTPS, SHA-256, package ID, ABI, version and signing identity are all mandatory.
 * On transient failure or coroutine cancellation, the partial file is retained for next attempt.
 */
internal suspend fun downloadAndVerifyApk(
    context: Context, update: ApkUpdate, onProgress: suspend (Int) -> Unit,
): File = withContext(Dispatchers.IO) {
    val dir = apkUpdateDirectory(context)
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create the update download folder.")
    if (update.size !in 1..MAX_APK_BYTES ||
        update.assetName != "pdfcraft-android-${update.version}.apk" ||
        !Regex("""pdfcraft-android-[0-9][0-9A-Za-z.\-]*\.apk""").matches(update.assetName)
    ) throw IOException("Untrusted update metadata.")

    val partial = File(dir, apkPartialFileName(update.sha256))
    val result = File(dir, update.assetName)
    // A downloaded and verified APK survives activity recreation and process death.
    if (result.isFile) {
        try {
            if (result.length() == update.size) {
                verifyDownloadedApk(context, result, update)
                withContext(Dispatchers.Main) { onProgress(100) }
                return@withContext result
            }
        } catch (_: IOException) {
            // Failed verification: a stale cached APK is never offered to the installer.
        }
        if (!result.delete()) throw IOException("Cannot remove an invalid cached APK.")
    }

    if (partial.length() > update.size) {
        if (!partial.delete()) throw IOException("Cannot reset an invalid partial update.")
    }
    if (partial.length() == update.size && partial.isFile) {
        // A previous download might have completed its last byte before losing the process.
        try {
            verifyDownloadedApk(context, partial, update)
            if (!partial.renameTo(result)) throw IOException("Cannot finalize a verified APK.")
            withContext(Dispatchers.Main) { onProgress(100) }
            return@withContext result
        } catch (e: IOException) {
            if (!partial.delete()) throw IOException("Cannot reset a corrupted APK.", e)
        }
    }

    val url = URL(update.downloadUrl)
    if (url.protocol != "https" || url.host != "github.com" ||
        url.toString() != "$RELEASE_DOWNLOAD_ROOT" + "v${update.version}/${update.assetName}"
    ) throw IOException("Untrusted APK URL.")

    var freshRetryUsed = false
    var networkRetries = 0
    var previousPercent = -1
    while (true) {
        currentCoroutineContext().ensureActive()
        // A timeout or disconnected CDN is recoverable; only verified response
        // metadata may append to the existing SHA-addressed partial APK.
        try {
            val offset = partial.length()
            val connection = url.openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 20_000
                connection.readTimeout = 40_000
                connection.setRequestProperty("User-Agent", "PdfCraft-Android-UpdateChecker")
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")
                val status = connection.responseCode
                if (connection.url.protocol != "https" ||
                    !(connection.url.host == "github.com" || connection.url.host.endsWith(".githubusercontent.com"))
                ) throw IOException("APK download redirected outside trusted HTTPS hosts.")
                val action = apkResumeAction(offset, status, connection.getHeaderField("Content-Range"), update.size)
                if (action == ApkResumeAction.RETRY_WITHOUT_RANGE) {
                    if (freshRetryUsed) throw IOException("APK server rejected the download twice.")
                    freshRetryUsed = true
                    if (!partial.delete()) throw IOException("Cannot reset a rejected APK range.")
                    continue
                }
                val resumed = if (action == ApkResumeAction.APPEND) offset else 0L
                val transferLength = connection.contentLengthLong
                if (transferLength > update.size - resumed || transferLength < -1L) {
                    throw IOException("APK response is larger than the published release.")
                }
                if (connection.contentEncoding?.lowercase() !in listOf(null, "identity")) {
                    throw IOException("Compressed APK response cannot be safely resumed.")
                }
                if (resumed > 0L) {
                    val initial = (resumed * 100L / update.size).toInt().coerceIn(0, 100)
                    withContext(Dispatchers.Main) { onProgress(initial) }
                    previousPercent = initial
                } else {
                    // HTTP 200 after Range means CDN ignored it. Truncate, never append.
                    previousPercent = 0
                    withContext(Dispatchers.Main) { onProgress(0) }
                }
                var received = resumed
                connection.inputStream.use { stream ->
                    java.io.FileOutputStream(partial, resumed > 0L).buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val bytes = stream.read(buffer)
                            if (bytes < 0) break
                            received += bytes
                            if (received > update.size) throw IOException("APK exceeds the published release size.")
                            output.write(buffer, 0, bytes)
                            val percent = (received * 100L / update.size).toInt().coerceIn(0, 100)
                            if (percent > previousPercent) {
                                previousPercent = percent
                                withContext(Dispatchers.Main) { onProgress(percent) }
                            }
                        }
                    }
                }
                if (received != update.size || partial.length() != update.size) {
                    // EOF is recoverable; preserve bytes and request the exact missing suffix.
                    throw java.io.EOFException("APK transfer ended before all published bytes arrived.")
                }
            } finally {
                connection.disconnect()
            }
            break
        } catch (e: IOException) {
            // Never retry malformed HTTP metadata, disk writes, or trust failures.
            if (!apkRetryable(e) || networkRetries >= MAX_APK_NETWORK_RETRIES) {
                throw IOException(
                    if (apkRetryable(e)) "Download interrupted after automatic retries. Tap Resume to continue saved bytes."
                    else e.message ?: "APK download failed.",
                    e,
                )
            }
            networkRetries++
            delay(apkRetryDelayMillis(networkRetries))
        }
    }

    try {
        verifyDownloadedApk(context, partial, update)
    } catch (e: Exception) {
        // Corrupt or wrong-signature APK bytes must never be resumed or installed.
        partial.delete()
        throw e
    }
    if (!partial.renameTo(result)) throw IOException("Could not save the verified APK.")
    withContext(Dispatchers.Main) { onProgress(100) }
    result
}

/** No silent installs: the owner must grant permission and confirm Android's install dialog. */
internal fun openVerifiedApkInstaller(context: Context, apk: File): Boolean {
    if (!context.packageManager.canRequestPackageInstalls()) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return false
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(intent)
    return true
}
