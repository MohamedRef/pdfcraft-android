package io.github.artcraftandroid.pdfcraft.ui.viewer

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.SheetTitle
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlinx.coroutines.delay

/** mm:ss of [ms]. */
internal fun timerClock(ms: Long): String {
    val s = (ms + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** The current time, ticking each second while shown, moving the timer past finished phases. */
@Composable
private fun rememberNow(vm: AppViewModel): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(vm.focusTimer.running) {
        while (vm.focusTimer.running) {
            now = System.currentTimeMillis()
            vm.tickFocusTimer()
            delay(1000L - now % 1000L)
        }
        now = System.currentTimeMillis()
    }
    return now
}

/** The focus timer's pill over the page while it runs or is paused; a tap opens its sheet. */
@Composable
fun FocusTimerPill(vm: AppViewModel, top: Dp, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val t = vm.focusTimer
    if (!t.active) return
    val c = pdfColors
    val now = rememberNow(vm)
    val label = stringResource(if (t.onBreak) R.string.focus_break else R.string.focus_work) + " " + timerClock(t.left(now))
    Row(
        modifier.padding(top = top + 8.dp, start = 12.dp).height(32.dp)
            .shadow(2.dp, RoundedCornerShape(16.dp)).background(if (t.onBreak) Color(0xFF2E7D32) else c.surface, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onOpen).semantics { contentDescription = label }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LucideIcons.Clock, null, tint = if (t.onBreak) Color.White else c.icon, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (t.onBreak) Color.White else c.label, fontSize = 13.sp)
    }
}

private val PRESETS = listOf(25 to 5, 50 to 10, 15 to 3)

/** Start, pause, resume or stop the focus timer, and choose its work and break lengths. */
@Composable
fun FocusTimerSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val c = pdfColors
    val t = vm.focusTimer
    val now = rememberNow(vm)
    PdfSheet(onDismiss = onDismiss, dim = true) {
        Column(Modifier.padding(bottom = 16.dp)) {
            SheetTitle(stringResource(R.string.focus_timer))
            Text(
                if (t.active) stringResource(if (t.onBreak) R.string.focus_break else R.string.focus_work) + " " + timerClock(t.left(now)) else timerClock(t.workMs),
                color = c.label, fontSize = 40.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (!t.active) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    for ((work, rest) in PRESETS) {
                        val selected = t.workMs == work * 60_000L && t.breakMs == rest * 60_000L
                        val shape = RoundedCornerShape(16.dp)
                        Box(
                            Modifier.heightIn(min = 36.dp).clip(shape)
                                .then(if (selected) Modifier.background(Color(0xFF1473E6)) else Modifier.border(1.dp, c.textSecondary, shape))
                                .clickable(role = Role.RadioButton) { vm.updateFocusTimer(t.copy(workMs = work * 60_000L, breakMs = rest * 60_000L)) }
                                .semantics { this.selected = selected }.padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(stringResource(R.string.focus_preset, work, rest), color = if (selected) Color.White else c.label, fontSize = 14.sp) }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    !t.active -> TimerButton(stringResource(R.string.focus_start), true, Modifier.weight(1f)) { vm.updateFocusTimer(t.start(System.currentTimeMillis())) }
                    t.running -> TimerButton(stringResource(R.string.focus_pause), true, Modifier.weight(1f)) { vm.updateFocusTimer(t.pause(System.currentTimeMillis())) }
                    else -> TimerButton(stringResource(R.string.focus_resume), true, Modifier.weight(1f)) { vm.updateFocusTimer(t.resume(System.currentTimeMillis())) }
                }
                if (t.active) TimerButton(stringResource(R.string.focus_stop), false, Modifier.weight(1f)) { vm.updateFocusTimer(t.stop()) }
            }
            Text(stringResource(R.string.focus_hint), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun TimerButton(label: String, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = pdfColors
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier.heightIn(min = 48.dp).clip(shape)
            .then(if (filled) Modifier.background(Color(0xFF1473E6)) else Modifier.border(2.dp, c.textSecondary, shape))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (filled) Color.White else c.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}
