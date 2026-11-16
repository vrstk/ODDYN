package com.oddyn.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.oddyn.app.OddynViewModel
import com.oddyn.app.domain.Event
import com.oddyn.app.domain.Evidence
import com.oddyn.app.domain.Leg
import com.oddyn.app.domain.Market
import com.oddyn.app.domain.Mode
import com.oddyn.app.domain.SettlementRules
import com.oddyn.app.domain.breakEven
import com.oddyn.app.domain.decimalOdds
import com.oddyn.app.domain.expectedValue
import com.oddyn.app.domain.isPublicSource
import com.oddyn.app.domain.marketCatalog
import com.oddyn.app.domain.modelProbability
import com.oddyn.app.domain.usable
import java.time.Instant

@Composable
internal fun MatchScreen(event: Event?, model: OddynViewModel, busy: Boolean) {
    PageList {
        if (event == null) {
            item { Notice("Матч отсутствует в расписании", "Вернитесь к списку и обновите поиск.") }
        } else {
            val now = Instant.now()
            val upcoming = event.mode == Mode.DEMO || event.kickoff > now
            item {
                PanelCard {
                    Text(event.sport.label, color = PaleGreen, style = MaterialTheme.typography.labelMedium)
                    Heading(event.title, event.tournament)
                    Text(formatStamp(event.kickoff), color = Silver, fontFamily = digits)
                    if (!upcoming) Text("Время начала прошло. Обновите расписание.", color = Amber)
                }
            }
            if (event.mode == Mode.DEMO) {
                item { Notice("Демо", "Участники, котировки и предпосылки относятся к условному сценарию.", warning = true) }
            }
            item {
                PanelCard {
                    Text("Данные матча", style = MaterialTheme.typography.titleMedium)
                    event.facts.forEach { Text(it, color = Silver) }
                    if (event.mode == Mode.REAL && event.markets.isEmpty()) {
                        Text("В этом источнике доступны только данные расписания.", color = Silver)
                    }
                }
            }
            item {
                PanelCard {
                    Text("Источники расписания", style = MaterialTheme.typography.titleMedium)
                    event.sources.forEach { EvidenceLine(it) }
                }
            }
            item { SectionTitle(if (event.mode == Mode.DEMO) "Демонстрационные рынки" else "Рынки и котировки") }
            if (event.markets.isEmpty()) {
                item {
                    Notice(
                        "Доступных котировок нет",
                        "Для прогноза требуются цена, правила расчёта и источник. Для EV дополнительно нужна вероятностная модель."
                    )
                }
            }
            items(event.markets, key = Market::id) { market ->
                MarketCard(
                    event, market, now, busy || !upcoming,
                    onForecast = { model.saveForecast(Leg(event, market)) },
                    onExpress = { model.addDraft(Leg(event, market)) }
                )
            }
            item {
                PanelCard {
                    Text("Каталог рынков", style = MaterialTheme.typography.titleMedium)
                    Text(marketCatalog(event.sport).joinToString(" · ") { it.name }, color = Silver)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarketCard(
    event: Event,
    market: Market,
    now: Instant,
    disabled: Boolean,
    onForecast: () -> Unit,
    onExpress: () -> Unit
) {
    val quote = market.quote
    val odds = quote?.usable(event.mode, now)
    val probability = modelProbability(market, event.mode, now)
    val ev = expectedValue(market, event.mode, now)

    PanelCard {
        Text(market.label, style = MaterialTheme.typography.labelMedium, color = PaleGreen)
        Text(market.selection, style = MaterialTheme.typography.titleLarge)
        Text(
            listOfNotNull(market.period, market.line?.let { "линия ${it.replace('.', ',')}" }).joinToString(" · "),
            color = Silver
        )
        Text(market.rules, color = Silver, style = MaterialTheme.typography.bodyMedium)
        if (odds != null) {
            Text(
                "${coefficient(odds)}   ${quote.bookmaker}",
                color = PaleGreen, fontFamily = digits, style = MaterialTheme.typography.titleMedium
            )
            EvidenceLine(quote.source)
        } else if (quote == null) {
            Text("Котировка отсутствует", color = Amber)
        } else {
            val archived = decimalOdds(quote.decimal)?.let(::coefficient) ?: "—"
            Text("Наблюдение $archived · ${quote.bookmaker}. Нужна повторная проверка.", color = Amber)
            EvidenceLine(quote.source)
        }
        if (odds != null) {
            if (market.settlement == SettlementRules.BINARY) {
                Text("Безубыточный порог ${percent(breakEven(odds))}", color = Silver)
            } else {
                Text(market.settlement.label, color = Silver)
            }
        }
        if (probability == null) {
            Text("Вероятность и EV · недостаточно данных", color = Silver)
        } else {
            Text("Вероятность модели ${percent(probability)} · EV ${percent(ev)}", color = Silver)
            market.model?.let { estimate ->
                Text("${estimate.method} · выборка ${estimate.sampleSize}", color = Silver)
                Text(estimate.validation, color = Silver)
                estimate.evidence.forEach { EvidenceLine(it) }
            }
        }
        market.rationale.forEach { Text(it, color = White, style = MaterialTheme.typography.bodyMedium) }
        Text("Риск · ${market.risk}", color = Amber, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onForecast, enabled = !disabled && odds != null) { Text("Сохранить прогноз") }
            TextButton(onClick = onExpress, enabled = !disabled && odds != null) { Text("В экспресс") }
        }
    }
}

@Composable
internal fun EvidenceLine(source: Evidence) {
    val browser = LocalUriHandler.current
    val checked = source.checkedAt?.let(::formatStamp)
    val label = listOfNotNull(source.name, checked).joinToString(" · ")
    val url = source.url
    if (url != null && isPublicSource(url)) {
        TextButton(onClick = { runCatching { browser.openUri(url) } }) { Text(label) }
    } else {
        Text(label, color = Silver, style = MaterialTheme.typography.labelMedium)
    }
}
