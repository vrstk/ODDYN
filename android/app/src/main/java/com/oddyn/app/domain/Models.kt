package com.oddyn.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val Moscow: ZoneId = ZoneId.of("Europe/Moscow")
fun moscowDate(instant: Instant = Instant.now()): LocalDate = instant.atZone(Moscow).toLocalDate()

val oddynJson = Json { ignoreUnknownKeys = true }

@Serializable
enum class Mode(val label: String) {
    @SerialName("demo") DEMO("Демо"),
    @SerialName("real") REAL("Реальные данные")
}

@Serializable
enum class Sport(val label: String) {
    @SerialName("football") FOOTBALL("Футбол"),
    @SerialName("hockey") HOCKEY("Хоккей"),
    @SerialName("tennis") TENNIS("Теннис")
}

@Serializable
enum class SettlementRules(val label: String) {
    @SerialName("binary") BINARY("Выигрыш или проигрыш"),
    @SerialName("push") PUSH("Возможен возврат"),
    @SerialName("split") SPLIT("Возможен половинный расчёт"),
    @SerialName("unknown") UNKNOWN("Условия требуют проверки")
}

@Serializable
data class Evidence(
    val name: String,
    val kind: String,
    val url: String? = null,
    @SerialName("checked_at") val checkedAt: String? = null
)

@Serializable
data class OddsQuote(
    val decimal: String,
    val bookmaker: String,
    val source: Evidence
)

@Serializable
data class ProbabilityEstimate(
    val value: String,
    val method: String,
    @SerialName("sample_size") val sampleSize: Int,
    val validation: String,
    val evidence: List<Evidence>
)

@Serializable
data class Market(
    val id: String,
    val family: String,
    val label: String,
    val selection: String,
    val line: String? = null,
    val period: String,
    val rules: String,
    val settlement: SettlementRules,
    @SerialName("exclusive_group") val exclusiveGroup: String? = null,
    val quote: OddsQuote? = null,
    val model: ProbabilityEstimate? = null,
    val rationale: List<String> = emptyList(),
    val risk: String
)

@Serializable
data class Event(
    val id: String,
    val mode: Mode,
    val sport: Sport,
    val tournament: String,
    val home: String,
    val away: String,
    @SerialName("starts_at") val startsAt: String,
    val sources: List<Evidence>,
    val facts: List<String> = emptyList(),
    val markets: List<Market> = emptyList()
) {
    val title: String get() = "$home · $away"
    val kickoff: Instant get() = Instant.parse(startsAt)
}

@Serializable
data class Coverage(
    val sport: Sport,
    val source: String,
    val status: String,
    val details: String
)

@Serializable
data class Discovery(
    @SerialName("api_version") val apiVersion: Int = 1,
    val date: String,
    val mode: Mode,
    val events: List<Event>,
    val coverage: List<Coverage>
)

@Serializable
data class Leg(
    @SerialName("event_id") val eventId: String,
    val mode: Mode,
    val sport: Sport,
    val tournament: String,
    @SerialName("event_title") val eventTitle: String,
    @SerialName("starts_at") val startsAt: String,
    val market: Market
) {
    val key: String get() = "$eventId/${market.id}"

    constructor(event: Event, market: Market) : this(
        event.id, event.mode, event.sport, event.tournament, event.title, event.startsAt, market
    )
}

@Serializable
data class DemoFixture(
    @SerialName("reference_date") val referenceDate: String,
    val events: List<Event>,
    val coverage: List<Coverage>
)

@Serializable
data class Health(
    @SerialName("api_version") val apiVersion: Int,
    val status: String,
    val coverage: List<Coverage>
)

data class Family(val code: String, val name: String)

fun marketCatalog(sport: Sport): List<Family> = when (sport) {
    Sport.FOOTBALL -> listOf(
        Family("F01", "1X2"), Family("F02", "Двойной шанс"), Family("F03", "Фора 0"),
        Family("F04", "Форы"), Family("F05", "Тотал голов"), Family("F06", "Индивидуальные тоталы"),
        Family("F07", "Обе забьют"), Family("F08", "Рынки таймов"),
        Family("F09", "Угловые и карточки")
    )
    Sport.HOCKEY -> listOf(
        Family("H01", "1X2 за 60 минут"), Family("H02", "Двойной шанс"),
        Family("H03", "Победитель с ОТ и буллитами"), Family("H04", "Форы"),
        Family("H05", "Тотал шайб"), Family("H06", "Индивидуальные тоталы"),
        Family("H07", "Рынки периодов"), Family("H08", "Броски и удаления")
    )
    Sport.TENNIS -> listOf(
        Family("T01", "Победитель матча"), Family("T02", "Форы по геймам"),
        Family("T03", "Тотал геймов"), Family("T04", "Индивидуальные тоталы"),
        Family("T05", "Победитель сета"), Family("T06", "Тоталы и форы по сетам"),
        Family("T07", "Тотал геймов первого сета"), Family("T08", "Эйсы и двойные ошибки")
    )
}
