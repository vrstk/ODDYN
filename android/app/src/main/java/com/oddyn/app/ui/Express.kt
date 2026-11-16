package com.oddyn.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oddyn.app.OddynViewModel
import com.oddyn.app.data.SavedExpress
import com.oddyn.app.domain.Leg
import com.oddyn.app.domain.Mode
import com.oddyn.app.domain.decimalOdds
import com.oddyn.app.domain.reviewExpress
import java.time.Instant

@Composable
internal fun ExpressScreen(model: OddynViewModel, mode: Mode, busy: Boolean, onFindMatches: () -> Unit) {
    val allDraft by model.draft.collectAsStateWithLifecycle()
    val history by model.expresses.collectAsStateWithLifecycle()
    val draft = allDraft.filter { it.mode == mode }
    val review = reviewExpress(draft, Instant.now())

    PageList {
        item { Heading("Экспрессы") }
        item {
            PanelCard {
                Text("Текущая подборка", style = MaterialTheme.typography.titleMedium)
                Text("${draft.size} из 10 плеч", color = Silver)
                if (draft.isEmpty()) {
                    Text("Добавьте рынки на экранах матчей.", color = Silver)
                    OutlinedButton(onClick = onFindMatches) { Text("Открыть матчи") }
                }
            }
        }
        items(draft, key = Leg::key) { leg ->
            PanelCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { LegDetails(leg) }
                    IconButton(onClick = { model.removeDraft(mode, leg.key) }, enabled = !busy) {
                        Icon(Icons.Filled.Close, "Удалить плечо ${leg.market.selection}", tint = Silver)
                    }
                }
            }
        }
        if (draft.isNotEmpty()) {
            item {
                PanelCard {
                    Text("Теоретический коэффициент", style = MaterialTheme.typography.titleMedium)
                    Text(review.coefficient?.let(::coefficient) ?: "—", fontFamily = digits, style = MaterialTheme.typography.headlineMedium)
                    review.notes.forEach { Text(it, color = if (review.canSave) Silver else Amber) }
                    Button(onClick = { model.saveExpress(mode) }, enabled = !busy && review.canSave) {
                        Text("Сохранить подборку")
                    }
                }
            }
        }
        item { SectionTitle("История подборок") }
        if (history.isEmpty()) {
            item { Notice("Подборок пока нет", "Сохранённые экспрессы появятся здесь.") }
        } else {
            items(history, key = SavedExpress::id) { item -> SavedExpressCard(item) }
        }
    }
}

@Composable
private fun SavedExpressCard(item: SavedExpress) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    PanelCard {
        ModeTag(item.mode)
        Text(formatStamp(item.createdAt), color = Silver, style = MaterialTheme.typography.labelMedium)
        Text("Плечей ${item.legs.size}", style = MaterialTheme.typography.titleMedium)
        val price = item.review.coefficient?.let(::coefficient)
        if (price != null) Text("Теоретический коэффициент $price", color = PaleGreen)
        item.review.notes.forEach { Text(it, color = Silver, style = MaterialTheme.typography.bodyMedium) }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Скрыть плечи" else "Показать плечи") }
        if (expanded) {
            item.legs.forEach { leg ->
                HorizontalDivider(color = Border)
                LegDetails(leg)
            }
        }
    }
}

@Composable
internal fun LegDetails(leg: Leg) {
    val offer = leg.market.quote
    val odds = offer?.decimal?.let(::decimalOdds)?.let(::coefficient)
    Text(leg.eventTitle, style = MaterialTheme.typography.titleMedium)
    Text("${leg.market.label} · ${leg.market.selection}", color = White)
    val line = leg.market.line?.let { " · линия ${it.replace('.', ',')}" }.orEmpty()
    Text("${leg.market.period}$line", color = Silver, style = MaterialTheme.typography.bodyMedium)
    Text(leg.market.rules, color = Silver, style = MaterialTheme.typography.bodyMedium)
    formatStamp(leg.startsAt)?.let { Text(it, color = Silver, style = MaterialTheme.typography.labelMedium) }
    if (offer != null && odds != null) {
        Text("$odds · ${offer.bookmaker}", color = PaleGreen, fontFamily = digits)
        EvidenceLine(offer.source)
    }
}
