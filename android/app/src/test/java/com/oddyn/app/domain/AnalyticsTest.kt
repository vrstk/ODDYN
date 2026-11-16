package com.oddyn.app.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsTest {
    private val now = Instant.parse("2026-11-16T12:00:00Z")
    private val evidence = Evidence("Тестовый источник", "odds", "https://example.org/match", now.toString())
    private val model = ProbabilityEstimate("0.55", "Тестовая модель", 200, "Проверка по времени", listOf(
        Evidence("Тестовая выборка", "model", "https://example.org/sample", now.toString())
    ))
    private val market = Market(
        "under", "F05", "Тотал голов", "Меньше 3,5", "3.5", "90 минут", "Условие",
        SettlementRules.BINARY, "total-3.5", OddsQuote("2.00", "Тестовая БК", evidence),
        risk = "Тестовый риск"
    )

    @Test fun thresholdDoesNotSubstituteModelProbability() {
        assertDecimal("0.5", breakEven(BigDecimal("2.00")))
        assertNull(modelProbability(market, Mode.REAL, now))
        assertNull(expectedValue(market, Mode.REAL, now))
    }

    @Test fun evRequiresModelAndBinarySettlement() {
        val complete = market.copy(model = model)
        assertDecimal("0.10", expectedValue(complete, Mode.REAL, now))
        assertNull(expectedValue(complete.copy(settlement = SettlementRules.PUSH), Mode.REAL, now))
        assertNull(expectedValue(complete.copy(model = model.copy(validation = "")), Mode.REAL, now))
        assertNull(expectedValue(complete.copy(model = model.copy(evidence = emptyList())), Mode.REAL, now))
        assertNull(expectedValue(complete, Mode.DEMO, now))
    }

    @Test fun realQuoteRequiresCurrentProvenance() {
        val quote = checkNotNull(market.quote)
        assertDecimal("2.00", quote.usable(Mode.REAL, now))
        assertNull(quote.usable(Mode.REAL, now.plusSeconds(3601)))
        assertNull(quote.usable(Mode.REAL, now.minusSeconds(1)))
        assertNull(quote.copy(source = evidence.copy(url = null)).usable(Mode.REAL, now))
        assertNull(quote.copy(source = evidence.copy(kind = "demo")).usable(Mode.REAL, now))
        assertNull(decimalOdds("NaN"))
        assertNull(decimalOdds("1.00"))
        assertDecimal("1.000001", decimalOdds("1,000001"))
    }

    @Test fun checksOppositeAndLinkedSelections() {
        val first = leg("a")
        val opposite = leg("a", market.copy(id = "over", selection = "Больше 3,5"))
        assertFalse(reviewExpress(listOf(first, opposite), now).canSave)
        val sameGame = leg("a", market.copy(id = "both", exclusiveGroup = null, family = "F07"))
        val linked = reviewExpress(listOf(first, sameGame), now)
        assertNull(linked.coefficient)
        assertTrue(linked.canSave)
    }

    @Test fun distinctEventsProduceOnlyTheoreticalPrice() {
        val other = market.copy(quote = market.quote?.copy(decimal = "1.65", bookmaker = "Другой источник"))
        val result = reviewExpress(listOf(leg("a"), leg("b", other)), now)
        assertDecimal("3.30", result.coefficient)
        assertTrue(result.notes.any { it.contains("Единый купон не проверен") })
        assertTrue(result.notes.any { it.contains("разных букмекеров") })
        assertFalse(reviewExpress(listOf(leg("a"), leg("b").copy(mode = Mode.DEMO)), now).canSave)
        assertFalse(reviewExpress(listOf(leg("a"), leg("b").copy(startsAt = now.toString())), now).canSave)
        assertFalse(reviewExpress((0..10).map { leg("e$it") }, now).canSave)
    }

    @Test fun statisticsUseOnlySettledTicketsAndWinRateUsesFullDecisions() {
        val stats = ticketStats(listOf(
            ticket("100", "180", TicketResult.WIN), ticket("100", "0", TicketResult.LOSS),
            ticket("100", "100", TicketResult.VOID), ticket("100", "70", TicketResult.CASHOUT),
            ticket("900", null, TicketResult.PENDING)
        ))
        assertEquals(4, stats.settled)
        assertEquals(2, stats.decisions)
        assertDecimal("400", stats.turnover)
        assertDecimal("-50", stats.profit)
        assertDecimal("-0.125", stats.roi)
        assertDecimal("0.5", stats.winRate)
        assertNull(ticketStats(emptyList()).roi)
        assertFalse(payoutMatches(TicketResult.LOSS, BigDecimal.ONE, BigDecimal.TEN))
        assertTrue(payoutMatches(TicketResult.LOSS, BigDecimal.ZERO, BigDecimal.TEN))
    }

    @Test fun moscowDayRollsOverAt2100Utc() {
        assertEquals(LocalDate.parse("2026-11-17"), moscowDate(Instant.parse("2026-11-16T21:00:00Z")))
        assertEquals(LocalDate.parse("2026-11-16"), moscowDate(Instant.parse("2026-11-16T20:59:59Z")))
    }

    private fun leg(event: String, offer: Market = market) =
        Leg(event, Mode.REAL, Sport.FOOTBALL, "Тестовая лига", event, "2026-11-16T16:00:00Z", offer)

    private fun ticket(stake: String, payout: String?, result: TicketResult) =
        TicketAmounts(BigDecimal(stake), payout?.toBigDecimal(), result)

    private fun assertDecimal(expected: String, actual: BigDecimal?) {
        assertEquals(expected, 0, BigDecimal(expected).compareTo(checkNotNull(actual)))
    }
}
