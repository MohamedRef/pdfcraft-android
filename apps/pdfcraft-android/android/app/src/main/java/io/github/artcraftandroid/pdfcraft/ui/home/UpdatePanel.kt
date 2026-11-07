package io.github.artcraftandroid.pdfcraft.ui.home

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import java.io.File
import kotlinx.coroutines.CancellationException
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
internal fun UpdatePanel() {
    val colors = pdfColors
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var candidate by remember { mutableStateOf<ApkUpdate?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var cachedBytes by remember { mutableLongStateOf(0L) }
    var verifiedApk by remember { mutableStateOf<File?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var showAllNotes by remember { mutableStateOf(false) }
    var showKnownLimits by remember { mutableStateOf(false) }

    Text("App updates", color = colors.accent, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))
    Text(
        "Download updates directly from published GitHub Releases. Interrupted downloads can resume.",
        color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    TextButton(
        enabled = !checking && !downloading,
        modifier = Modifier.padding(horizontal = 12.dp),
        onClick = {
            checking = true
            message = null
            scope.launch {
                try {
                    val found = fetchApkUpdates(installedVersionCode(context)).firstOrNull()
                    candidate = found
                    verifiedApk = null
                    showAllNotes = false
                    showKnownLimits = false
                    cachedBytes = found?.let { apkCachedBytes(context, it) } ?: 0L
                    progress = if (found == null) 0 else (cachedBytes * 100L / found.size).toInt()
                    message = if (found == null) "You're up to date. No newer published APK found." else null
                } catch (e: Exception) {
                    message = "Unable to check GitHub Releases. Check your connection and retry."
                } finally {
                    checking = false
                }
            }
        },
    ) { Text(if (checking) "Checking for updates…" else "Check for updates", color = colors.accent) }

    candidate?.let { update ->
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.divider, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Update available", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.accent)
            Text("${update.version} (${update.apkVersionCode})", fontSize = 22.sp,
                fontWeight = FontWeight.Bold, color = colors.text)
            Text(
                "${update.channel} · Published ${update.publishedAt.take(10)} · ${readableMegabytes(update.size)} MB",
                fontSize = 12.sp, color = colors.textSecondary,
            )
            val sections = remember(update.notes) { updateNoteSections(update.notes) }
            if (sections.isNotEmpty()) {
                Text("What's new", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.text, modifier = Modifier.padding(top = 6.dp))
                for (section in sections) {
                    if (section.title == "Known limits" && !showKnownLimits) continue
                    Text(section.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.accent)
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
                        Text(if (showAllNotes) "Show fewer changes" else "Show all changes", color = colors.accent)
                    }
                }
                if (sections.any { it.title == "Known limits" }) {
                    TextButton(onClick = { showKnownLimits = !showKnownLimits }) {
                        Text(if (showKnownLimits) "Hide known limits" else "Read known limits", color = colors.accent)
                    }
                }
            } else if (update.notes.isNotBlank()) {
                Text(update.notes.take(1000), color = colors.textSecondary, fontSize = 13.sp)
            }
            TextButton(onClick = {
                uriHandler.openUri("https://github.com/MohamedRef/pdfcraft-android/releases/tag/v${update.version}")
            }) {
                Text("Full release details on GitHub", color = colors.accent)
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
                        "$progress% · ${readableMegabytes(if (downloading) update.size * progress / 100L else cachedBytes)} / ${readableMegabytes(update.size)} MB",
                        color = colors.textSecondary, fontSize = 12.sp,
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !downloading && !checking,
                        onClick = {
                            downloading = true
                            message = null
                            downloadJob = scope.launch {
                                try {
                                    verifiedApk = downloadAndVerifyApk(context, update) { percent ->
                                        progress = percent
                                    }
                                    cachedBytes = update.size
                                    message = "Download complete and verified. Confirm installation below."
                                } catch (_: CancellationException) {
                                    cachedBytes = apkCachedBytes(context, update)
                                    progress = (cachedBytes * 100L / update.size).toInt()
                                    message = "Download paused. Your progress was kept."
                                } catch (e: Exception) {
                                    cachedBytes = apkCachedBytes(context, update)
                                    progress = (cachedBytes * 100L / update.size).toInt()
                                    message = e.message ?: "Download interrupted. Tap Resume to retry."
                                } finally {
                                    downloading = false
                                    downloadJob = null
                                }
                            }
                        },
                    ) {
                        Text(if (cachedBytes > 0L) "Resume download" else "Download APK", color = colors.accent)
                    }
                    if (downloading) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { downloadJob?.cancel() }) {
                            Text("Pause", color = colors.text)
                        }
                    }
                }
            } else {
                Text("Verified APK ready", color = colors.accent, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = {
                    try {
                        val launched = openVerifiedApkInstaller(context, verifiedApk!!)
                        message = if (launched) "Confirm installation in Android's installer."
                            else "Allow PdfCraft to install updates in Settings, then tap Install again."
                    } catch (e: Exception) {
                        message = e.message ?: "Android couldn't open the installer."
                    }
                }) {
                    Text("Install verified APK", color = colors.accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    message?.let {
        Text(it, fontSize = 13.sp, color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
    Text("Installing always requires your approval. APK signature and SHA-256 are checked first.",
        color = colors.textSecondary, fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}
