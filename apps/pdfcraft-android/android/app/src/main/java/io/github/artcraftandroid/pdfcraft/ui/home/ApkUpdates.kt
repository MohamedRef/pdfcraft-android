package io.github.artcraftandroid.pdfcraft.ui.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
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
import io.github.artcraftandroid.pdfcraft.R
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

/**
 * Published release APKs are single-ABI arm64-v8a builds, whose versionCode ends in 2. A build for
 * another ABI (1, 3) or a multi-ABI build (0), such as one made with -Ppdfcraft.abis, must not be
 * offered them: the same version would look newer, and installing it fails or switches ABI (#110).
 */
internal fun receivesGitHubApkUpdates(installedVersionCode: Long): Boolean =
    installedVersionCode > 0L && installedVersionCode % 10L == 2L

/** Compare version bases, not raw codes, so the ABI digit never makes a release look newer. */
internal fun isUpdateFor(releaseBase: Long, installedVersionCode: Long): Boolean =
    receivesGitHubApkUpdates(installedVersionCode) && releaseBase > installedVersionCode / 10L

internal fun parseUpdates(json: String, installedVersionCode: Long): List<ApkUpdate> {
    if (!receivesGitHubApkUpdates(installedVersionCode)) return emptyList()
    val releases = JSONArray(json)
    val candidates = mutableListOf<ApkUpdate>()
    for (i in 0 until releases.length()) {
        val release = releases.optJSONObject(i) ?: continue
        if (release.optBoolean("draft", false)) continue
        val tag = release.optString("tag_name")
        val version = tag.removePrefix("v")
        val base = updateVersionBase(version) ?: continue
        if (!isUpdateFor(base, installedVersionCode)) continue
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

/**
 * An updater failure that the app can show in the user's language: [messageRes] is the translated
 * text, and the English message stays for logs and tests.
 */
class UpdateException(@StringRes val messageRes: Int, english: String, cause: Throwable? = null) : IOException(english, cause)

/** The translated text for an updater failure, or [fallback] for anything else (never raw English). */
fun Throwable.updaterMessage(context: Context, @StringRes fallback: Int): String =
    context.getString((this as? UpdateException)?.messageRes ?: fallback)

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
        throw UpdateException(R.string.updater_err_checksum, "The APK checksum does not match its published GitHub Release.")
    }
    val manager = context.packageManager
    @Suppress("DEPRECATION")
    val archive: PackageInfo = manager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        ?: throw UpdateException(R.string.updater_err_invalid_apk, "The downloaded file is not a valid signed Android APK.")
    @Suppress("DEPRECATION")
    val installed: PackageInfo = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    if (archive.packageName != context.packageName || archive.longVersionCode != update.apkVersionCode) {
        throw UpdateException(R.string.updater_err_wrong_build, "The downloaded APK package or build number is not the expected update.")
    }
    val newSigners = archive.signingInfo?.apkContentsSigners?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }?.toSet()
    val existingSigners = installed.signingInfo?.apkContentsSigners?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }?.toSet()
    if (newSigners.isNullOrEmpty() || newSigners != existingSigners) {
        throw UpdateException(R.string.updater_err_certificate, "The APK certificate is not the installed app's signing certificate.")
    }
    val minSdk = archive.applicationInfo?.minSdkVersion ?: 0
    if (minSdk > Build.VERSION.SDK_INT) throw UpdateException(R.string.updater_err_android_version, "This APK requires a newer Android version.")
    ZipFile(apk).use { zip ->
        if (zip.getEntry("lib/arm64-v8a/libpdfcraft_android.so") == null) {
            throw UpdateException(R.string.updater_err_abi, "The APK is missing PdfCraft's ARM64 native engine.")
        }
    }
}

/** Private persistent storage is not auto-evicted like cacheDir; interrupted APKs can resume later. */
internal fun apkUpdateDirectory(context: Context): File = File(context.filesDir, "updates")

/**
 * Where verified APKs go: the only update folder the FileProvider shares (file_paths.xml), so a
 * partial download is never reachable by another app (#109).
 */
internal fun verifiedApkDirectory(context: Context): File = File(apkUpdateDirectory(context), "verified")

/** What start-up cleaning does with one file in the update folders (#109). */
internal enum class UpdateFileAction { KEEP, DELETE, MOVE_TO_VERIFIED }

/**
 * The fate of update file [name]: an APK ([archiveVersionCode] read from it, null when
 * unreadable) is deleted once the installed build ([installed]) is the same or newer, and one in
 * the old location ([legacy]) moves to the verified folder; a partial download is deleted when
 * a check found a different release ([keepSha256]: the candidate's digest, "" for no update,
 * null when no check has run).
 */
internal fun updateFileAction(name: String, legacy: Boolean, archiveVersionCode: Long?, installed: Long, keepSha256: String?): UpdateFileAction = when {
    name.endsWith(".apk") -> when {
        archiveVersionCode == null || archiveVersionCode <= installed -> UpdateFileAction.DELETE
        legacy -> UpdateFileAction.MOVE_TO_VERIFIED
        else -> UpdateFileAction.KEEP
    }
    name.startsWith("download-") && name.endsWith(".part") ->
        if (keepSha256 != null && name != "download-$keepSha256.part") UpdateFileAction.DELETE else UpdateFileAction.KEEP
    else -> UpdateFileAction.KEEP
}

/** Remove update files that can no longer be installed or resumed (see [updateFileAction]). */
internal fun cleanUpdateFiles(context: Context, installed: Long, keepSha256: String?) {
    val root = apkUpdateDirectory(context)
    val verified = verifiedApkDirectory(context)
    fun versionOf(f: File): Long? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageArchiveInfo(f.absolutePath, 0)?.longVersionCode
    }.getOrNull()
    for ((dir, legacy) in listOf(root to true, verified to false)) {
        for (f in dir.listFiles().orEmpty()) {
            if (!f.isFile) continue
            val version = if (f.name.endsWith(".apk")) versionOf(f) else null
            when (updateFileAction(f.name, legacy, version, installed, keepSha256)) {
                UpdateFileAction.DELETE -> f.delete()
                UpdateFileAction.MOVE_TO_VERIFIED -> if (!(verified.isDirectory || verified.mkdirs()) || !f.renameTo(File(verified, f.name))) f.delete()
                UpdateFileAction.KEEP -> Unit
            }
        }
    }
}

/** [update]'s APK when it was already downloaded and still verifies; otherwise null. */
internal fun verifiedApkIfPresent(context: Context, update: ApkUpdate): File? {
    val apk = File(verifiedApkDirectory(context), update.assetName)
    if (!apk.isFile || apk.length() != update.size) return null
    return try {
        verifyDownloadedApk(context, apk, update)
        apk
    } catch (_: Exception) {
        null
    }
}

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
    if (!dir.isDirectory && !dir.mkdirs()) throw UpdateException(R.string.updater_err_storage, "Cannot create the update download folder.")
    if (update.size !in 1..MAX_APK_BYTES ||
        update.assetName != "pdfcraft-android-${update.version}.apk" ||
        !Regex("""pdfcraft-android-[0-9][0-9A-Za-z.\-]*\.apk""").matches(update.assetName)
    ) throw UpdateException(R.string.updater_err_untrusted, "Untrusted update metadata.")

    val partial = File(dir, apkPartialFileName(update.sha256))
    val verifiedDir = verifiedApkDirectory(context)
    if (!verifiedDir.isDirectory && !verifiedDir.mkdirs()) throw IOException("Cannot create the update download folder.")
    val result = File(verifiedDir, update.assetName)
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
        if (!result.delete()) throw UpdateException(R.string.updater_err_storage, "Cannot remove an invalid cached APK.")
    }

    if (partial.length() > update.size) {
        if (!partial.delete()) throw UpdateException(R.string.updater_err_storage, "Cannot reset an invalid partial update.")
    }
    if (partial.length() == update.size && partial.isFile) {
        // A previous download might have completed its last byte before losing the process.
        try {
            verifyDownloadedApk(context, partial, update)
            if (!partial.renameTo(result)) throw UpdateException(R.string.updater_err_storage, "Cannot finalize a verified APK.")
            withContext(Dispatchers.Main) { onProgress(100) }
            return@withContext result
        } catch (e: IOException) {
            if (!partial.delete()) throw UpdateException(R.string.updater_err_storage, "Cannot reset a corrupted APK.", e)
        }
    }

    val url = URL(update.downloadUrl)
    if (url.protocol != "https" || url.host != "github.com" ||
        url.toString() != "$RELEASE_DOWNLOAD_ROOT" + "v${update.version}/${update.assetName}"
    ) throw UpdateException(R.string.updater_err_untrusted, "Untrusted APK URL.")

    var freshRetryUsed = false
    var networkRetries = 0
    // Bytes saved when the retry budget was last reset: an attempt that made progress earns a
    // fresh budget, so a slow, often-interrupted connection still finishes instead of failing
    // after three interruptions of an otherwise advancing download.
    var progressMark = -1L
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
                ) throw UpdateException(R.string.updater_err_untrusted, "APK download redirected outside trusted HTTPS hosts.")
                val action = apkResumeAction(offset, status, connection.getHeaderField("Content-Range"), update.size)
                if (action == ApkResumeAction.RETRY_WITHOUT_RANGE) {
                    if (freshRetryUsed) throw UpdateException(R.string.updater_err_server, "APK server rejected the download twice.")
                    freshRetryUsed = true
                    if (!partial.delete()) throw UpdateException(R.string.updater_err_storage, "Cannot reset a rejected APK range.")
                    continue
                }
                val resumed = if (action == ApkResumeAction.APPEND) offset else 0L
                val transferLength = connection.contentLengthLong
                if (transferLength > update.size - resumed || transferLength < -1L) {
                    throw UpdateException(R.string.updater_err_server, "APK response is larger than the published release.")
                }
                if (connection.contentEncoding?.lowercase() !in listOf(null, "identity")) {
                    throw UpdateException(R.string.updater_err_server, "Compressed APK response cannot be safely resumed.")
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
                            if (received > update.size) throw UpdateException(R.string.updater_err_server, "APK exceeds the published release size.")
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
            val saved = partial.length()
            if (apkRetryBudgetResets(progressMark, saved)) {
                progressMark = saved
                networkRetries = 0
            }
            if (!apkRetryable(e) || networkRetries >= MAX_APK_NETWORK_RETRIES) {
                throw when {
                    e is UpdateException -> e
                    apkRetryable(e) -> UpdateException(
                        R.string.updater_interrupted,
                        "Download interrupted after automatic retries. Tap Resume to continue saved bytes.",
                        e,
                    )
                    else -> UpdateException(R.string.updater_err_server, e.message ?: "APK download failed.", e)
                }
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
    if (!partial.renameTo(result)) throw UpdateException(R.string.updater_err_storage, "Could not save the verified APK.")
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
    // only the system's package installer gets the file, never another app that claims APKs (#109)
    val installer = systemInstallerPackage(context, intent) ?: throw IOException("Android's package installer is not available.")
    context.startActivity(intent.setPackage(installer))
    return true
}

/** The preinstalled app that installs APKs (its package), or null when none is visible. */
private fun systemInstallerPackage(context: Context, intent: Intent): String? {
    @Suppress("DEPRECATION")
    val handlers = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY)
    return handlers.firstOrNull { (it.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 }?.activityInfo?.packageName
}
