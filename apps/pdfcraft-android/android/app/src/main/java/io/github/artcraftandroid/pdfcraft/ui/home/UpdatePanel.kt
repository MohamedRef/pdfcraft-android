package io.github.artcraftandroid.pdfcraft.ui.home

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class UpdateNoteSection(val title: String, val entries: List<String>)

/** A bounded, user-friendly view of the authored release notes, not GitHub's auto-generated PR appendix. */
internal fun updateNoteSections(markdown: String): List<UpdateNoteSection> {
    val groups = linkedMapOf<String, MutableList<String>>()
    var current: String? = null
    for (line in markdown.lineSequence()) {
        val heading = line.trim()
        if (heading.startsWith("## ")) {
            val title = heading.removePrefix("## ").trim()
            current = when (title.lowercase(java.util.Locale.ROOT)) {
                "new features", "features" -> "New features"
                "fixes", "bug fixes" -> "Fixes"
                "improvements" -> "Improvements"
                "known limits and testing", "known limitations", "known issues" -> "Known limits"
                else -> null
            }
            if (current != null) groups.getOrPut(current) { mutableListOf() }
            continue
        }
        val section = current ?: continue
        val content = line.trim()
        if (content.startsWith("- ") && groups.getValue(section).size < 25) {
            val plain = content.removePrefix("- ")
                .replace(Regex("""\[([^]]+)]\([^)]*\)"""), "$1")
                .replace("**", "").replace("`", "")
                .trim().take(800)
            if (plain.isNotBlank()) groups.getValue(section).add(plain)
        }
    }
    return groups.map { UpdateNoteSection(it.key, it.value) }.filter { it.entries.isNotEmpty() }
}

@Composable
internal fun UpdatePanel(showTitle: Boolean = true) {
    val colors = pdfColors
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    // The check and the download outlive this screen: leaving About must not stop a download (#193).
    val appContext = context.applicationContext
    val scope = UpdaterState.scope
    var checking by UpdaterState::checking
    var candidate by UpdaterState::candidate
    var message by UpdaterState::message
    var downloading by UpdaterState::downloading
    var progress by UpdaterState::progress
    var cachedBytes by UpdaterState::cachedBytes
    var verifiedApk by UpdaterState::verifiedApk
    var downloadJob by UpdaterState::downloadJob
    var showAllNotes by remember(candidate) { mutableStateOf(false) }
    var showKnownLimits by remember(candidate) { mutableStateOf(false) }

    if (showTitle) Text(stringResource(R.string.updater_title), color = colors.accent, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))
    Text(
        stringResource(R.string.updater_description),
        color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    TextButton(
        enabled = !checking && !downloading,
        modifier = Modifier.padding(horizontal = 12.dp),
        onClick = { UpdaterState.check(appContext) },
    ) { Text(stringResource(if (checking) R.string.updater_checking else R.string.updater_check), color = colors.accent) }

    candidate?.let { update ->
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.divider, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.updater_available), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.accent)
            Text("${update.version} (${update.apkVersionCode})", fontSize = 22.sp,
                fontWeight = FontWeight.Bold, color = colors.text)
            Text(
                stringResource(R.string.updater_build_details, update.channel, update.publishedAt.take(10), readableMegabytes(update.size)),
                fontSize = 12.sp, color = colors.textSecondary,
            )
            val sections = remember(update.notes) { updateNoteSections(update.notes) }
            if (sections.isNotEmpty()) {
                Text(stringResource(R.string.updater_whats_new), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.text, modifier = Modifier.padding(top = 6.dp))
                for (section in sections) {
                    if (section.title == "Known limits" && !showKnownLimits) continue
                    Text(stringResource(updaterSectionTitle(section.title)), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.accent)
                    val visible = if (showAllNotes || section.title == "Known limits") section.entries else section.entries.take(3)
                    for (item in visible) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", color = colors.accent, fontSize = 14.sp)
                            Text(item, color = colors.text, fontSize = 13.sp,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (sections.any { it.title != "Known limits" && it.entries.size > 3 }) {
                    TextButton(onClick = { showAllNotes = !showAllNotes }) {
                        Text(stringResource(if (showAllNotes) R.string.updater_show_less else R.string.updater_show_all), color = colors.accent)
                    }
                }
                if (sections.any { it.title == "Known limits" }) {
                    TextButton(onClick = { showKnownLimits = !showKnownLimits }) {
                        Text(stringResource(if (showKnownLimits) R.string.updater_hide_limits else R.string.updater_read_limits), color = colors.accent)
                    }
                }
            } else if (update.notes.isNotBlank()) {
                Text(update.notes.take(1000), color = colors.textSecondary, fontSize = 13.sp)
            }
            TextButton(onClick = {
                uriHandler.openUri("https://github.com/MohamedRef/pdfcraft-android/releases/tag/v${update.version}")
            }) {
                Text(stringResource(R.string.updater_release_details), color = colors.accent)
            }

            if (verifiedApk == null) {
                if (downloading || cachedBytes > 0L) {
                    val fraction = (progress / 100f).coerceIn(0f, 1f)
                    Box(
                        Modifier.fillMaxWidth().height(8.dp)
                            .clip(RoundedCornerShape(8.dp)).background(colors.divider),
                    ) {
                        Box(
                            Modifier.fillMaxWidth(fraction).height(8.dp)
                                .clip(RoundedCornerShape(8.dp)).background(colors.accent),
                        )
                    }
                    Text(
                        stringResource(R.string.updater_progress, progress, readableMegabytes(if (downloading) update.size * progress / 100L else cachedBytes), readableMegabytes(update.size)),
                        color = colors.textSecondary, fontSize = 12.sp,
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(
                        enabled = !downloading && !checking,
                        onClick = {
                            downloading = true
                            message = null
                            downloadJob = scope.launch {
                                try {
                                    verifiedApk = downloadAndVerifyApk(appContext, update) { percent ->
                                        progress = percent
                                    }
                                    cachedBytes = update.size
                                    message = appContext.getString(R.string.updater_download_verified)
                                    Diag.event(DiagArea.Update, "download", "outcome" to "verified", "bytes" to update.size)
                                } catch (_: CancellationException) {
                                    Diag.event(DiagArea.Update, "download", "outcome" to "paused", "progress" to progress)
                                    cachedBytes = apkCachedBytes(appContext, update)
                                    progress = (cachedBytes * 100L / update.size).toInt()
                                    message = appContext.getString(R.string.updater_paused)
                                } catch (e: Exception) {
                                    Diag.failure(DiagArea.Update, "download", e)
                                    cachedBytes = apkCachedBytes(appContext, update)
                                    progress = (cachedBytes * 100L / update.size).toInt()
                                    message = e.updaterMessage(appContext, R.string.updater_interrupted)
                                } finally {
                                    downloading = false
                                    downloadJob = null
                                }
                            }
                        },
                        label = stringResource(if (cachedBytes > 0L) R.string.updater_resume else R.string.updater_download),
                    )
                    if (downloading) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { downloadJob?.cancel() }) {
                            Text(stringResource(R.string.updater_pause), color = colors.text)
                        }
                    }
                }
                // A browser uses Android's regular external download path when an ISP/CDN
                // route blocks only the app's own HTTPS client. The URL was verified by
                // parseUpdates against the exact official GitHub release asset.
                TextButton(
                    enabled = !downloading,
                    onClick = {
                        try {
                            uriHandler.openUri(update.downloadUrl)
                        } catch (_: Exception) {
                            message = context.getString(R.string.updater_browser_open_error)
                        }
                    },
                ) {
                    Text(stringResource(R.string.updater_download_browser), color = colors.accent)
                }
                Text(
                    stringResource(R.string.updater_browser_notice),
                    fontSize = 12.sp, color = colors.textSecondary,
                )
            } else {
                Text(stringResource(R.string.updater_apk_ready), color = colors.accent, fontWeight = FontWeight.SemiBold)
                PrimaryButton(label = stringResource(R.string.updater_install), onClick = {
                    try {
                        val launched = openVerifiedApkInstaller(context, verifiedApk!!)
                        UpdaterState.needsInstallPermission = !launched
                        Diag.event(DiagArea.Update, "install", "launched" to launched)
                        message = context.getString(if (launched) R.string.updater_confirm_install else R.string.updater_allow_install)
                    } catch (e: Exception) {
                        Diag.failure(DiagArea.Update, "install", e)
                        message = e.updaterMessage(context, R.string.updater_installer_error)
                    }
                })
                // Android's "Install unknown apps" page for PdfCraft, in one tap (#193)
                if (UpdaterState.needsInstallPermission && !context.packageManager.canRequestPackageInstalls()) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) { Text(stringResource(R.string.updater_open_settings), color = colors.accent) }
                }
            }
        }
    }
    message?.let {
        Text(it, fontSize = 13.sp, color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
    Text(stringResource(R.string.updater_install_notice),
        color = colors.textSecondary, fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

private fun updaterSectionTitle(name: String): Int = when (name) {
    "New features" -> R.string.updater_features
    "Fixes" -> R.string.updater_fixes
    "Improvements" -> R.string.updater_improvements
    "Known limits" -> R.string.updater_limits
    else -> R.string.updater_whats_new
}

/**
 * The updater's state and its running check or download, kept for the life of the app process
 * instead of the About screen, so going back or closing Settings never cancels a download and the
 * progress is still there on return.
 */
internal object UpdaterState {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var needsInstallPermission by mutableStateOf(false)

    private fun prefs(context: Context) = context.getSharedPreferences("updater", Context.MODE_PRIVATE)

    /** Whether PdfCraft looks for an update once a day when it opens (#193); on unless turned off. */
    fun autoCheckOn(context: Context) = prefs(context).getBoolean("autoCheck", true)

    fun setAutoCheck(context: Context, on: Boolean) = prefs(context).edit().putBoolean("autoCheck", on).apply()

    /** At most once a day, and only when the switch is on: a quiet check whose only sign is an update found. */
    fun autoCheck(context: Context, now: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        if (!autoCheckOn(app) || candidate != null || !autoCheckDue(prefs(app).getLong("lastCheck", 0L), now)) return
        check(app, quiet = true)
    }

    const val RESULT_CURRENT = "current"

    /** When the last check ran (0 for never) and what it found: [RESULT_CURRENT], "found" or "error" (#463). */
    fun lastCheck(context: Context): Pair<Long, String> {
        val p = prefs(context.applicationContext)
        return p.getLong("lastCheck", 0L) to (p.getString("lastResult", null) ?: "")
    }

    private fun recordResult(context: Context, result: String) = prefs(context).edit().putString("lastResult", result).apply()

    /** Look for a newer release; [quiet] (the daily check) leaves the message alone. */
    fun check(context: Context, quiet: Boolean = false) {
        if (checking || downloading) return
        val app = context.applicationContext
        checking = true
        if (!quiet) message = null
        prefs(app).edit().putLong("lastCheck", System.currentTimeMillis()).apply()
        val started = android.os.SystemClock.elapsedRealtime()
        scope.launch {
            try {
                val installed = installedVersionCode(app)
                if (!receivesGitHubApkUpdates(installed)) {
                    Diag.event(DiagArea.Update, "check", "auto" to quiet, "other_build" to true)
                    candidate = null
                    verifiedApk = null
                    if (!quiet) message = app.getString(R.string.updater_other_build)
                    return@launch
                }
                val found = fetchApkUpdates(installed).firstOrNull()
                // a partial download of a superseded release can never finish: removed (#109)
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    runCatching { cleanUpdateFiles(app, installed, keepSha256 = found?.sha256 ?: "") }
                }
                candidate = found
                verifiedApk = null
                cachedBytes = found?.let { apkCachedBytes(app, it) } ?: 0L
                progress = if (found == null) 0 else (cachedBytes * 100L / found.size).toInt()
                recordResult(app, if (found == null) RESULT_CURRENT else "found")
                if (!quiet) message = if (found == null) app.getString(R.string.updater_no_updates) else null
                Diag.event(DiagArea.Update, "check", "auto" to quiet, "found" to (found != null), "cached_bytes" to cachedBytes, "ms" to android.os.SystemClock.elapsedRealtime() - started)
            } catch (e: Exception) {
                Diag.failure(DiagArea.Update, "check", e)
                recordResult(app, "error")
                if (!quiet) message = app.getString(R.string.updater_check_error)
            } finally {
                checking = false
            }
        }
    }

    var checking by mutableStateOf(false)
    var candidate by mutableStateOf<ApkUpdate?>(null)
    var message by mutableStateOf<String?>(null)
    var downloading by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var cachedBytes by mutableLongStateOf(0L)
    var verifiedApk by mutableStateOf<File?>(null)
    var downloadJob by mutableStateOf<Job?>(null)
}

/** The daily check is due when the last one was a day ago or more, or the clock went back. */
internal fun autoCheckDue(lastCheck: Long, now: Long): Boolean =
    now < lastCheck || now - lastCheck >= 24L * 60 * 60 * 1000

/** The updater's filled button: the one next step (Download update, Install update) (#193). */
@Composable
private fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = pdfColors
    Box(
        Modifier.padding(vertical = 4.dp).height(44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (enabled) c.accent else c.divider)
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) c.onAccent else c.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}
