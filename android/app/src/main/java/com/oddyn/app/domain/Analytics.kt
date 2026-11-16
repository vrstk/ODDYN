package com.oddyn.app.domain

import java.math.BigDecimal
import java.math.MathContext
import java.net.URI
import java.time.Duration
import java.time.Instant

private val decimalContext = MathContext.DECIMAL128
private val probabilityPattern = Regex("""(?:0(?:\.[0-9]{1,20})?|1(?:\.0{1,20})?)""")
private val oddsPattern = Regex("[0-9]{1,6}(?:[.,][0-9]{1,6})?")
private val moneyPattern = Regex("[0-9]{1,10}(?:[.,][0-9]{1,2})?")
private val quoteLifetime: Duration = Duration.ofHours(1)

fun decimalOdds(raw: String): BigDecimal? =
    raw.trim().takeIf(oddsPattern::matches)?.replace(',', '.')?.toBigDecimalOrNull()
        ?.takeIf { it > BigDecimal.ONE }

fun money(raw: String): BigDecimal? =
    raw.trim().takeIf(moneyPattern::matches)?.replace(',', '.')?.toBigDecimalOrNull()

fun breakEven(odds: BigDecimal): BigDecimal {
    require(odds > BigDecimal.ONE)
    return BigDecimal.ONE.divide(odds, decimalContext)
}

fun isPublicSource(url: String?): Boolean {
    val uri = runCatching { URI(url ?: return false) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) && uri.host != null && uri.userInfo == null
}

fun OddsQuote.usable(mode: Mode, now: Instant): BigDecimal? {
    val checked = source.checkedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    if (bookmaker.isBlank() || source.name.isBlank()) return null

    when (mode) {
        Mode.DEMO -> if (source.kind != "demo") return null
        Mode.REAL -> if (
            source.kind != "odds" || !isPublicSource(source.url) ||
            checked > now || checked.plus(quoteLifetime) < now
        ) return null
    }
    return decimalOdds(decimal)
}

fun modelProbability(market: Market, mode: Mode, now: Instant = Instant.now()): BigDecimal? {
    if (mode != Mode.REAL) return null
    val model = market.model ?: return null
    if (model.method.isBlank() || model.validation.isBlank() || model.sampleSize <= 0 ||
        model.evidence.isEmpty() || model.evidence.any {
            val checked = it.checkedAt?.let { stamp -> runCatching { Instant.parse(stamp) }.getOrNull() }
            it.kind == "demo" || it.name.isBlank() || !isPublicSource(it.url) || checked == null || checked > now
        }
    ) return null
    return model.value.takeIf(probabilityPattern::matches)?.toBigDecimalOrNull()
}

fun expectedValue(market: Market, mode: Mode, now: Instant): BigDecimal? {
    if (market.settlement != SettlementRules.BINARY) return null
    val odds = market.quote?.usable(mode, now) ?: return null
    val probability = modelProbability(market, mode, now) ?: return null
    return probability.multiply(odds, decimalContext).subtract(BigDecimal.ONE)
}

data class ExpressReview(
    val canSave: Boolean,
    val coefficient: BigDecimal?,
    val notes: List<String>
)

fun reviewExpress(legs: List<Leg>, now: Instant): ExpressReview {
    if (legs.size !in 2..10) {
        return ExpressReview(false, null, listOf("Для экспресса выберите от 2 до 10 плеч."))
    }
    if (legs.map { it.mode }.distinct().size != 1 || legs.map { it.key }.distinct().size != legs.size) {
        return ExpressReview(false, null, listOf("Все плечи должны быть уникальны и относиться к одному режиму."))
    }
    if (legs.any { it.mode == Mode.REAL && Instant.parse(it.startsAt) <= now }) {
        return ExpressReview(false, null, listOf("Один из матчей уже начался. Уберите его из подборки."))
    }
    val sameEvents = legs.groupBy { it.eventId }.values.filter { it.size > 1 }
    val exclusive = sameEvents.firstOrNull { sameEvent ->
        val groups = sameEvent.mapNotNull { it.market.exclusiveGroup }
        groups.size != groups.distinct().size
    }
    if (exclusive != null) {
        return ExpressReview(
            false, null, listOf("Взаимоисключающие исходы в матче «${exclusive.first().eventTitle}».")
        )
    }
    if (sameEvents.isNotEmpty()) {
        return ExpressReview(true, null, listOf(
            "Плечи одного матча связаны. Для коэффициента нужны условия комбинированного рынка у букмекера.",
            "Единый купон не проверен. Совместная вероятность и EV не рассчитаны."
        ))
    }

    val quotes = legs.map { it.market.quote?.usable(it.mode, now) }
    val notes = buildList {
        if (quotes.any { it == null }) add("Не хватает действительных котировок для расчёта.")
        if (legs.groupBy { it.sport to it.tournament }.values.any { it.size > 1 }) {
            add("Несколько событий из одного турнира. Их зависимость требует проверки.")
        }
        if (legs.mapNotNull { it.market.quote?.bookmaker }.distinct().size > 1) {
            add("В подборке котировки разных букмекеров.")
        }
        add("Единый купон не проверен. Совместная вероятность и EV не рассчитаны.")
    }
    val coefficient = if (quotes.any { it == null }) null
    else quotes.filterNotNull().fold(BigDecimal.ONE, BigDecimal::multiply)
    return ExpressReview(true, coefficient, notes)
}

enum class TicketKind(val label: String) {
    SINGLE("Ординар"), EXPRESS("Экспресс")
}

enum class TicketResult(val label: String) {
    PENDING("В ожидании"),
    WIN("Выигрыш"),
    LOSS("Проигрыш"),
    VOID("Возврат"),
    HALF_WIN("Половина выигрыша"),
    HALF_LOSS("Половина проигрыша"),
    CASHOUT("Досрочный расчёт")
}

data class TicketAmounts(val stake: BigDecimal, val payout: BigDecimal?, val result: TicketResult)
data class TicketStats(
    val settled: Int,
    val turnover: BigDecimal,
    val profit: BigDecimal,
    val roi: BigDecimal?,
    val winRate: BigDecimal?,
    val decisions: Int
)

fun ticketStats(tickets: List<TicketAmounts>): TicketStats {
    val settled = tickets.filter { it.payout != null && it.result != TicketResult.PENDING }
    val turnover = settled.fold(BigDecimal.ZERO) { total, ticket -> total + ticket.stake }
    val profit = settled.fold(BigDecimal.ZERO) { total, ticket -> total + checkNotNull(ticket.payout) - ticket.stake }
    val wins = settled.count { it.result == TicketResult.WIN }
    val decisions = wins + settled.count { it.result == TicketResult.LOSS }
    return TicketStats(
        settled.size, turnover, profit,
        if (turnover.signum() > 0) profit.divide(turnover, decimalContext) else null,
        if (decisions > 0) wins.toBigDecimal().divide(decisions.toBigDecimal(), decimalContext) else null,
        decisions
    )
}

fun payoutMatches(result: TicketResult, payout: BigDecimal, stake: BigDecimal): Boolean = when (result) {
    TicketResult.PENDING -> false
    TicketResult.WIN, TicketResult.HALF_WIN -> payout > stake
    TicketResult.LOSS -> payout.signum() == 0
    TicketResult.VOID -> payout.compareTo(stake) == 0
    TicketResult.HALF_LOSS -> payout.signum() > 0 && payout < stake
    TicketResult.CASHOUT -> payout.signum() >= 0
}
