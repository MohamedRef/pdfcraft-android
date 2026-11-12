package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.Lecture
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.SheetRow
import io.github.artcraftandroid.pdfcraft.ui.SheetTitle
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlinx.coroutines.delay

/**
 * Over the viewer's bottom: while a lecture is recorded, its time with Pause and Stop; while one
 * plays, play/pause, back and forward 10 s, the position, the speed and Close. Tapping a comment
 * written during the lecture plays from just before it.
 */
@Composable
fun LectureBar(vm: AppViewModel, doc: OpenDocument, bottom: Dp, modifier: Modifier = Modifier) {
    val c = pdfColors
    val rec = vm.noteTaking.lectureRecording
    val play = vm.noteTaking.lecturePlayback?.takeIf { it.uri == doc.source?.toString() }
    if (rec == null && play == null) return
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(rec, play, vm.noteTaking.lecturePlaying, vm.noteTaking.lecturePaused) {
        while (true) {
            now = if (rec != null) rec.clock.elapsed() else vm.noteTaking.lecturePosition()
            delay(250)
        }
    }
    Box(modifier.padding(bottom = bottom + 12.dp, start = 12.dp, end = 12.dp).widthIn(max = 520.dp)) {
        Column(
            Modifier.fillMaxWidth().shadow(3.dp, RoundedCornerShape(16.dp)).background(c.surface, RoundedCornerShape(16.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            if (rec != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.padding(start = 8.dp).size(10.dp).clip(CircleShape).background(if (vm.noteTaking.lecturePaused) c.textSecondary else Color(0xFFD7373F)))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(if (vm.noteTaking.lecturePaused) R.string.lecture_paused else R.string.lecture_recording) + " " + timerClock(now),
                        color = c.label, fontSize = 14.sp, modifier = Modifier.weight(1f),
                    )
                    if (vm.noteTaking.lecturePaused) BarIcon(LucideIcons.Mic, stringResource(R.string.lecture_resume), { vm.noteTaking.resumeLecture() })
                    else BarIcon(LucideIcons.Pause, stringResource(R.string.lecture_pause), { vm.noteTaking.pauseLecture() })
                    BarIcon(LucideIcons.Square, stringResource(R.string.lecture_stop), { vm.noteTaking.stopLecture() })
                }
            } else if (play != null) {
                val duration = play.lecture.durationMs.coerceAtLeast(runCatching { play.player.duration.toLong() }.getOrDefault(0)).coerceAtLeast(1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BarIcon(LucideIcons.RotateCcw, stringResource(R.string.lecture_back_10), { vm.noteTaking.skipLecture(-10_000) })
                    BarIcon(if (vm.noteTaking.lecturePlaying) LucideIcons.Pause else LucideIcons.Play, stringResource(if (vm.noteTaking.lecturePlaying) R.string.lecture_pause else R.string.lecture_play), { vm.noteTaking.toggleLecturePlay() })
                    BarIcon(LucideIcons.RotateCw, stringResource(R.string.lecture_forward_10), { vm.noteTaking.skipLecture(10_000) })
                    Text(timerClock(now) + " / " + timerClock(duration), color = c.label, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    Text(
                        "${vm.noteTaking.lectureSpeed}×".replace(".0×", "×"), color = c.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { vm.noteTaking.cycleLectureSpeed() }.heightIn(min = 40.dp).padding(horizontal = 10.dp, vertical = 10.dp),
                    )
                    BarIcon(LucideIcons.X, stringResource(R.string.lecture_close_player), { vm.noteTaking.closeLecturePlayback() })
                }
                Slider(
                    value = (now.toFloat() / duration).coerceIn(0f, 1f), onValueChange = { vm.noteTaking.seekLecture((it * duration).toLong()) },
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                    modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/** The document's lecture recordings: record a new one, play one, or delete one. */
@Composable
fun LecturesSheet(vm: AppViewModel, doc: OpenDocument, onDismiss: () -> Unit) {
    val c = pdfColors
    val context = LocalContext.current
    var list by remember { mutableStateOf<List<Lecture>?>(null) }
    var deleting by remember { mutableStateOf<Lecture?>(null) }
    LaunchedEffect(doc, vm.noteTaking.lectureRecording) { list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.noteTaking.lecturesOf(doc) } }
    PdfSheet(onDismiss = onDismiss, dim = true) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SheetTitle(stringResource(R.string.lecture_title))
            if (vm.noteTaking.lectureRecording == null) {
                SheetRow(LucideIcons.Mic, stringResource(R.string.lecture_record), { onDismiss(); vm.noteTaking.startLecture(doc) })
            } else {
                SheetRow(LucideIcons.Square, stringResource(R.string.lecture_stop), { vm.noteTaking.stopLecture() })
            }
            for (l in list.orEmpty().filter { it.id != vm.noteTaking.lectureRecording?.id }) {
                val label = DateUtils.formatDateTime(context, l.started, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME) + " • " + timerClock(l.durationMs) +
                    if (l.marks.isNotEmpty()) " • " + androidx.compose.ui.res.pluralStringResource(R.plurals.lecture_notes, l.marks.size, l.marks.size) else ""
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SheetRow(LucideIcons.Play, label, { onDismiss(); vm.noteTaking.playLecture(doc, l) }) }
                    BarIcon(LucideIcons.Trash2, stringResource(R.string.common_delete), { deleting = l })
                }
            }
            Text(stringResource(R.string.lecture_body), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
    }
    deleting?.let { l ->
        PdfDialog(
            onDismiss = { deleting = null }, title = stringResource(R.string.lecture_delete_title),
            confirm = stringResource(R.string.common_delete), destructive = true,
            onConfirm = { deleting = null; vm.noteTaking.deleteLecture(doc, l); list = list?.filter { it.id != l.id } },
        )
    }
}
