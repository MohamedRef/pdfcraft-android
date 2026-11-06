package io.github.artcraftandroid.pdfcraft.ui.home

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Build
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** Only public, published artifacts from the app owner's GitHub Releases are trusted. */
internal const val UPDATE_RELEASES_URL =
    "https://api.github.com/repos/MohamedRef/pdfcraft-android/releases?per_page=100"

internal data class ApkUpdate(
    val version: String,
    val channel: String,
    val versionBase: Long,
    val assetName: String,
    val downloadUrl: String,
    val publishedAt: String,
    val notes: String,
    val size: Long,
)

internal fun updateVersionBase(version: String): Long? {
    // Match the Gradle versionCode calculation. The final digit is ABI-specific.
    val match = Regex("""(\d+)\.(\d+)\.(\d+)(-[0-9A-Za-z-]+?(\.(\d+))?)?""")
        .matchEntire(version) ?: return null
    val major = match.groupValues[1].toLongOrNull() ?: return null
    val minor = match.groupValues[2].toLongOrNull() ?: return null
    val patch = match.groupValues[3].toLongOrNull() ?: return null
    val pre = if (match.groupValues[4].isEmpty()) 99L
        else match.groupValues[6].toLongOrNull() ?: 1L
    if (minor !in 0..99 || patch !in 0..99 || (pre != 99L && pre !in 1..98)) return null
    return ((major * 100 + minor) * 100 + patch) * 100 + pre
}

internal fun updateChannel(version: String): String = when {
    version.contains("-alpha", true) -> "Alpha"
    version.contains("-beta", true) -> "Beta"
    version.contains("-preview", true) || version.contains("-rc", true) -> "Preview"
    else -> "Stable"
}

internal fun parseUpdates(json: String, installedVersionCode: Long): List<ApkUpdate> {
    val releases = JSONArray(json)
    val candidates = mutableListOf<ApkUpdate>()
    val installedBase = installedVersionCode / 10L
    for (i in 0 until releases.length()) {
        val release = releases.optJSONObject(i) ?: continue
        if (release.optBoolean("draft", false)) continue
        val version = release.optString("tag_name").removePrefix("v")
        val base = updateVersionBase(version) ?: continue
        if (base <= installedBase) continue
        val assets = release.optJSONArray("assets") ?: continue
        for (j in 0 until assets.length()) {
            val asset = assets.optJSONObject(j) ?: continue
            val name = asset.optString("name")
            // Only the arm64 APK produced by this repository's release workflow.
            if (!Regex("""pdfcraft-android-[0-9][A-Za-z0-9.\-]*\.apk""").matches(name)) continue
            if (name != "pdfcraft-android-$version.apk") continue
            val url = asset.optString("browser_download_url")
            val expectedPrefix = "https://github.com/MohamedRef/pdfcraft-android/releases/download/"
            if (!url.startsWith(expectedPrefix) || !url.endsWith("/$name")) continue
            candidates.add(ApkUpdate(
                version, updateChannel(version), base, name, url,
                release.optString("published_at"), release.optString("body"), asset.optLong("size"),
            ))
        }
    }
    return candidates.sortedWith(compareByDescending<ApkUpdate> { it.versionBase }.thenByDescending { it.publishedAt })
}

internal suspend fun fetchApkUpdates(installedVersionCode: Long): List<ApkUpdate> =
    withContext(Dispatchers.IO) {
        val connection = URL(UPDATE_RELEASES_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "PdfCraft-Android-UpdateChecker")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("GitHub releases returned HTTP ${connection.responseCode}")
            }
            val data = connection.inputStream.bufferedReader().use { it.readText() }
            parseUpdates(data, installedVersionCode)
        } finally {
            connection.disconnect()
        }
    }

internal fun installedVersionCode(context: Context): Long {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    @Suppress("DEPRECATION")
    return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}

/**
 * The system Download Manager owns the download and displays its progress/notification.
 * Installation remains user-initiated via Android's Downloads app/package installer.
 */
internal fun downloadApkUpdate(context: Context, update: ApkUpdate): Long {
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
        .setTitle("PdfCraft ${update.version} (${update.channel})")
        .setDescription("APK update — open Downloads to install after completion")
        .setMimeType("application/vnd.android.package-archive")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, update.assetName)
    return manager.enqueue(request)
}
