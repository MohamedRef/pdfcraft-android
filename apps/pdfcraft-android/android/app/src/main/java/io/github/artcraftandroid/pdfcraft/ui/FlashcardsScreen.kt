package io.github.artcraftandroid.pdfcraft.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.DAY_MS
import io.github.artcraftandroid.pdfcraft.engine.Flashcard
import io.github.artcraftandroid.pdfcraft.engine.Rating

/** Writing or editing a card: front (with {{ }} for a fill-in blank) and back. */
@Composable
fun CardEditor(vm: AppViewModel, draft: CardDraft) {
    val c = pdfColors
    var front by remember(draft) { mutableStateOf(draft.front) }
    var back by remember(draft) { mutableStateOf(draft.back) }
    PdfDialog(
        onDismiss = { vm.study.cardDraft = null },
        title = stringResource(if (draft.id == null) R.string.cards_new else R.string.cards_edit),
        confirm = stringResource(R.string.common_save), confirmEnabled = front.isNotBlank(),
        onConfirm = { vm.study.saveCard(draft.copy(front = front, back = back)) },
    ) {
        Text(stringResource(R.string.cards_front), color = c.textSecondary, fontSize = 13.sp)
        MultiField(front, { front = it.take(2000) })
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.cards_back), color = c.textSecondary, fontSize = 13.sp)
        MultiField(back, { back = it.take(2000) })
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.cards_cloze_hint), color = c.textSecondary, fontSize = 12.sp)
        if (draft.page >= 0) Text(stringResource(R.string.cards_from_page, draft.deckName.removeSuffix(".pdf"), draft.page + 1), color = c.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun MultiField(value: String, onValue: (String) -> Unit) {
    val c = pdfColors
    BasicTextField(
        value, onValue,
        textStyle = TextStyle(color = c.text, fontSize = 16.sp),
        cursorBrush = SolidColor(Color(0xFF378EF0)),
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 160.dp).border(1.dp, c.textSecondary, RoundedCornerShape(4.dp)).padding(10.dp),
    )
}

/** How long until a card comes back, short: "1 min", "3 d", "2 mo". */
private fun spanLabel(ms: Long, minutes: String, days: String, months: String): String = when {
    ms < DAY_MS -> minutes.format((ms / 60_000).coerceAtLeast(1))
    ms < 60 * DAY_MS -> days.format(ms / DAY_MS)
    else -> months.format(ms / (30 * DAY_MS))
}

/**
 * A review session: each due card's front, Show answer, then Again / Hard / Good / Easy with
 * when the card would come back (FSRS). Works with a finger or a pen; the source page is a tap
 * away.
 */
@Composable
fun ReviewScreen(vm: AppViewModel, session: ReviewSession) {
    val c = pdfColors
    BackHandler { vm.study.reviewing = null }
    var shown by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Flashcard?>(null) }
    val card = session.queue.firstOrNull()
    Column(Modifier.fillMaxSize().background(c.surface).safeTopPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BarIcon(LucideIcons.ArrowLeft, stringResource(R.string.common_back), { vm.study.reviewing = null })
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(stringResource(R.string.cards_title), color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.cards_progress, session.queue.size, session.all.size), color = c.textSecondary, fontSize = 12.sp)
            }
            if (session.deck != null) BarIcon(LucideIcons.Plus, stringResource(R.string.cards_new), { vm.study.newCard(session.deck, session.deckName) })
            Box {
                BarIcon(LucideIcons.EllipsisVertical, stringResource(R.string.common_more_options), { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.cards_export)) }, onClick = { menu = false; vm.study.exportCards(session.deck, session.deckName) })
                    if (session.deck != null) DropdownMenuItem(text = { Text(stringResource(R.string.cards_import)) }, onClick = { menu = false; vm.study.importCards(session.deck, session.deckName) })
                    if (card != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.cards_edit)) }, onClick = { menu = false; vm.study.editCard(card) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, onClick = { menu = false; deleting = card })
                    }
                }
            }
        }
        if (card == null) {
            Column(Modifier.fillMaxWidth().weight(1f).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(if (session.all.isEmpty()) R.string.cards_none else R.string.cards_all_done), color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(if (session.all.isEmpty()) R.string.cards_none_body else R.string.cards_all_done_body, session.reviewed), color = c.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
            return@Column
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(if (card.isCloze) card.clozeFront else card.front, color = c.text, fontSize = 20.sp, lineHeight = 28.sp)
            if (shown) {
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                Spacer(Modifier.height(16.dp))
                if (card.isCloze) Text(card.clozeBack, color = c.text, fontSize = 18.sp, lineHeight = 26.sp)
                if (card.back.isNotBlank()) Text(card.back, color = c.text, fontSize = 18.sp, lineHeight = 26.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(20.dp))
            if (card.page >= 0) Text(
                stringResource(R.string.cards_from_page, card.deckName.removeSuffix(".pdf"), card.page + 1),
                color = c.accent, fontSize = 13.sp, modifier = Modifier.clickable { vm.study.openCardSource(card) }.padding(vertical = 8.dp),
            )
        }
        if (!shown) {
            Pill(stringResource(R.string.cards_show_answer), Color(0xFF1473E6), Modifier.padding(16.dp).fillMaxWidth()) { shown = true }
        } else {
            val previews = remember(card) { vm.study.previewIntervals(card) }
            val min = stringResource(R.string.cards_span_minutes)
            val day = stringResource(R.string.cards_span_days)
            val mon = stringResource(R.string.cards_span_months)
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((rating, label, color) in listOf(
                    Triple(Rating.Again, R.string.cards_again, Color(0xFFC62828)),
                    Triple(Rating.Hard, R.string.cards_hard, Color(0xFF6D4C41)),
                    Triple(Rating.Good, R.string.cards_good, Color(0xFF2E7D32)),
                    Triple(Rating.Easy, R.string.cards_easy, Color(0xFF1565C0)),
                )) {
                    Pill(stringResource(label) + "\n" + spanLabel(previews[rating] ?: 0, min, day, mon), color, Modifier.weight(1f)) {
                        shown = false
                        vm.study.rate(session, card, rating)
                    }
                }
            }
        }
    }
    deleting?.let { d ->
        PdfDialog(
            onDismiss = { deleting = null }, title = stringResource(R.string.cards_delete_title),
            confirm = stringResource(R.string.common_delete), destructive = true,
            onConfirm = { deleting = null; vm.study.deleteCard(d.id) },
        )
    }
}

@Composable
private fun Pill(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(color).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, lineHeight = 18.sp) }
}
