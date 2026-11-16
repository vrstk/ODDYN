package com.oddyn.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oddyn.app.OddynViewModel
import com.oddyn.app.data.Ticket
import com.oddyn.app.data.TicketInput
import com.oddyn.app.domain.TicketKind
import com.oddyn.app.domain.TicketResult
import com.oddyn.app.domain.ticketStats

private enum class HistoryTab(val label: String) {
    FORECASTS("Прогнозы"), TICKETS("Купоны")
}

@Composable
internal fun HistoryScreen(model: OddynViewModel, busy: Boolean) {
    val forecasts by model.forecasts.collectAsStateWithLifecycle()
    val tickets by model.tickets.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(HistoryTab.FORECASTS) }
    var showNewTicket by rememberSaveable { mutableStateOf(false) }
    var settling by rememberSaveable { mutableStateOf<String?>(null) }
    val stats = ticketStats(tickets.map(Ticket::amounts))

    PageList {
        item { Heading("История и статистика") }
        item {
            ChoiceChips(HistoryTab.entries, tab, HistoryTab::label, onChoose = { tab = it })
        }
        if (tab == HistoryTab.FORECASTS) {
            if (forecasts.isEmpty()) {
                item { Notice("Прогнозов пока нет", "Сохраните рынок на экране анализа матча.") }
            }
            items(forecasts) { forecast ->
                PanelCard {
                    ModeTag(forecast.leg.mode)
                    LegDetails(forecast.leg)
                    Text("Сохранено ${formatStamp(forecast.createdAt)}", color = Silver, style = MaterialTheme.typography.labelMedium)
                }
            }
        } else {
            item {
                PanelCard {
                    Text("Реальные купоны", style = MaterialTheme.typography.titleMedium)
                    Text("Рассчитано ${stats.settled} из ${tickets.size}", color = Silver)
                    if (stats.settled > 0) {
                        Text("Оборот ${rubles(stats.turnover)}", color = Silver)
                        val profitColor = when (stats.profit.signum()) {
                            -1 -> Red
                            1 -> PaleGreen
                            else -> Silver
                        }
                        Text("Прибыль и убытки ${rubles(stats.profit)}", color = profitColor)
                        Text("ROI ${percent(stats.roi)} · Win Rate ${percent(stats.winRate)}", color = White)
                        Text("Win Rate по ${stats.decisions} купонам с полным выигрышем или проигрышем.", color = Silver)
                    } else {
                        Text("Статистика появится после записи фактического расчёта.", color = Silver)
                    }
                    Button(onClick = { showNewTicket = true }, enabled = !busy) { Text("Добавить купон") }
                }
            }
            items(tickets, key = Ticket::id) { ticket ->
                PanelCard {
                    Text(ticket.description, style = MaterialTheme.typography.titleMedium)
                    Text("${ticket.kind.label} · ${ticket.bookmaker} · № ${ticket.reference}", color = Silver)
                    Text("${rubles(ticket.amounts.stake)} · Кф ${ticket.odds.replace('.', ',')}")
                    Text(ticket.amounts.result.label, color = PaleGreen)
                    ticket.amounts.payout?.let {
                        Text("Выплата ${rubles(it)} · результат ${rubles(it - ticket.amounts.stake)}", color = Silver)
                    }
                    Text(formatStamp(ticket.createdAt), color = Silver, style = MaterialTheme.typography.labelMedium)
                    if (ticket.amounts.result == TicketResult.PENDING) {
                        OutlinedButton(onClick = { settling = ticket.id }, enabled = !busy) { Text("Указать расчёт") }
                    }
                }
            }
        }
    }

    if (showNewTicket) {
        AddTicketDialog(busy, onDismiss = { showNewTicket = false }) {
            model.addTicket(it) { showNewTicket = false }
        }
    }
    tickets.firstOrNull { it.id == settling }?.let { ticket ->
        SettleDialog(ticket, busy, onDismiss = { settling = null }) { result, payout ->
            model.settleTicket(ticket.id, result, payout) { settling = null }
        }
    }
}

@Composable
private fun AddTicketDialog(busy: Boolean, onDismiss: () -> Unit, onConfirm: (TicketInput) -> Unit) {
    var bookmaker by rememberSaveable { mutableStateOf("") }
    var reference by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var stake by rememberSaveable { mutableStateOf("") }
    var odds by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(TicketKind.SINGLE) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Реальный купон") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Введите данные фактически размещённой ставки.")
                ChoiceChips(TicketKind.entries, kind, TicketKind::label, onChoose = { kind = it })
                Field("Букмекер", bookmaker, { bookmaker = it })
                Field("Номер купона", reference, { reference = it })
                Field("Матч или описание", description, { description = it })
                Field("Сумма в рублях", stake, { stake = it }, KeyboardType.Decimal)
                Field("Фактический коэффициент", odds, { odds = it }, KeyboardType.Decimal)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(TicketInput(bookmaker, reference, kind, description, stake, odds)) },
                enabled = !busy && listOf(bookmaker, reference, description, stake, odds).none(String::isBlank)
            ) { Text("Подтвердить купон") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } }
    )
}

@Composable
private fun SettleDialog(ticket: Ticket, busy: Boolean, onDismiss: () -> Unit, onConfirm: (TicketResult, String) -> Unit) {
    var result by rememberSaveable { mutableStateOf<TicketResult?>(null) }
    var payout by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Расчёт купона") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${ticket.bookmaker} · № ${ticket.reference}")
                ChoiceChips(TicketResult.entries.filter { it != TicketResult.PENDING }, result, TicketResult::label, onChoose = { result = it })
                Field("Фактическая выплата в рублях", payout, { payout = it }, KeyboardType.Decimal)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { result?.let { onConfirm(it, payout) } },
                enabled = !busy && result != null && payout.isNotBlank()
            ) { Text("Сохранить расчёт") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } }
    )
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceChips(values: List<T>, selected: T?, label: (T) -> String, onChoose: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { item ->
            FilterChip(selected = selected == item, onClick = { onChoose(item) }, label = { Text(label(item)) })
        }
    }
}
