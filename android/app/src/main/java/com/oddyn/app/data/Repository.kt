package com.oddyn.app.data

import android.content.Context
import androidx.room.withTransaction
import com.oddyn.app.domain.Coverage
import com.oddyn.app.domain.Discovery
import com.oddyn.app.domain.Event
import com.oddyn.app.domain.ExpressReview
import com.oddyn.app.domain.Leg
import com.oddyn.app.domain.Mode
import com.oddyn.app.domain.TicketAmounts
import com.oddyn.app.domain.TicketKind
import com.oddyn.app.domain.TicketResult
import com.oddyn.app.domain.decimalOdds
import com.oddyn.app.domain.money
import com.oddyn.app.domain.oddynJson
import com.oddyn.app.domain.payoutMatches
import com.oddyn.app.domain.reviewExpress
import com.oddyn.app.domain.usable
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString

enum class TaskState { QUEUED, DONE, BLOCKED_SOURCE, EXPIRED }

data class SearchResults(
    val events: List<Event> = emptyList(),
    val coverage: List<Coverage> = emptyList(),
    val processed: Int = 0,
    val waiting: Int = 0,
    val found: Int = 0,
    val hasRun: Boolean = false
)

data class Forecast(val createdAt: Instant, val leg: Leg)
data class SavedExpress(
    val id: String,
    val createdAt: Instant,
    val mode: Mode,
    val legs: List<Leg>,
    val review: ExpressReview
)

data class Ticket(
    val id: String,
    val createdAt: Instant,
    val bookmaker: String,
    val reference: String,
    val kind: TicketKind,
    val description: String,
    val odds: String,
    val amounts: TicketAmounts
)

data class TicketInput(
    val bookmaker: String,
    val reference: String,
    val kind: TicketKind,
    val description: String,
    val stake: String,
    val odds: String
)

class InputProblem(message: String) : IllegalArgumentException(message)

class OddynRepository(context: Context, private val database: OddynDatabase = OddynDatabase.get(context)) {
    private val analysis = database.analysis()
    private val journal = database.journal()
    private val demo = DemoSchedule(context)
    val sync: JournalSync = SheetsSync

    val batches = analysis.batchCount()
    val forecasts = journal.forecasts().map { rows ->
        rows.map { Forecast(Instant.ofEpochMilli(it.createdAt), oddynJson.decodeFromString(it.snapshot)) }
    }
    val expresses = journal.expresses().map { rows ->
        rows.map { (item, legs) ->
            SavedExpress(
                item.id, Instant.ofEpochMilli(item.createdAt), Mode.valueOf(item.mode),
                legs.sortedBy { it.position }.map { oddynJson.decodeFromString(it.snapshot) },
                ExpressReview(true, item.coefficient?.toBigDecimal(), oddynJson.decodeFromString(item.notes))
            )
        }
    }
    val tickets = journal.tickets().map { it.map(TicketRow::toTicket) }
    val draft = journal.draft().map { it.map(DraftRow::toLeg) }

    fun observe(preferences: Preferences) = analysis.observe(preferences.scope).map { saved ->
        if (saved == null) SearchResults() else SearchResults(
            events = saved.tasks.filter { it.state == TaskState.DONE.name || it.state == TaskState.BLOCKED_SOURCE.name }
                .sortedBy { it.position }.map { oddynJson.decodeFromString(it.snapshot) },
            coverage = oddynJson.decodeFromString(saved.batch.coverage),
            processed = saved.tasks.count { it.state != TaskState.QUEUED.name },
            waiting = saved.tasks.count { it.state == TaskState.QUEUED.name },
            found = saved.tasks.size,
            hasRun = true
        )
    }

    suspend fun search(preferences: Preferences) {
        val provider = when (preferences.mode) {
            Mode.DEMO -> demo
            Mode.REAL -> ServerSchedule(preferences.serverUrl)
        }
        val previous = analysis.latest(preferences.scope)
        val queue = previous?.tasks?.filter { it.state == TaskState.QUEUED.name }.orEmpty()
        val fresh = if (queue.isEmpty() || preferences.mode == Mode.REAL) provider.discover(preferences.date) else null

        val batch = if (queue.isEmpty()) {
            begin(preferences, checkNotNull(fresh))
        } else {
            checkNotNull(previous).batch.also {
                if (fresh != null) analysis.updateCoverage(it.id, oddynJson.encodeToString(fresh.coverage))
            }
        }

        val refreshed = fresh?.events?.associateBy(Event::id).orEmpty()
        val blockedSports = fresh?.coverage.orEmpty().filter { it.status != "available" }.map { it.sport }.toSet()
        var blocked = false
        for (task in analysis.tasks(batch.id).filter { it.state == TaskState.QUEUED.name }) {
            currentCoroutineContext().ensureActive()
            val previousEvent: Event = oddynJson.decodeFromString(task.snapshot)
            if (preferences.mode == Mode.REAL && previousEvent.sport in blockedSports && queue.isNotEmpty()) {
                blocked = true
                continue
            }
            val event = refreshed[task.eventId] ?: previousEvent
            val now = Instant.now()
            val state = when {
                event.mode == Mode.REAL && (refreshed[task.eventId] == null || event.kickoff <= now) -> TaskState.EXPIRED
                event.markets.none { it.quote?.usable(event.mode, now) != null } -> TaskState.BLOCKED_SOURCE
                else -> TaskState.DONE
            }
            database.withTransaction {
                analysis.updateTask(batch.id, event.id, state.name, oddynJson.encodeToString(event), now.toEpochMilli())
                if (state != TaskState.EXPIRED) {
                    analysis.observeMarkets(event.markets.map { market ->
                        ObservationRow(batch.id, event.id, market.id, oddynJson.encodeToString(Leg(event, market)))
                    })
                    val sources = event.sources + event.markets.flatMap {
                        listOfNotNull(it.quote?.source) + it.model?.evidence.orEmpty()
                    }
                    journal.saveSources(sources.map { source ->
                        SourceRow(digest(source.kind, source.url ?: source.name), oddynJson.encodeToString(source))
                    })
                }
                checkpoint(batch.id, now, event.id, state.name)
            }
            yield()
        }
        if (blocked) throw InputProblem("Часть очереди сохранена. Источники временно недоступны, повторите анализ позже.")
    }

    private suspend fun begin(preferences: Preferences, discovered: Discovery): BatchRow = database.withTransaction {
        val now = Instant.now()
        val batch = BatchRow(
            id("OD"), preferences.scope, now.toEpochMilli(), oddynJson.encodeToString(discovered.coverage)
        )
        analysis.addBatch(batch)
        analysis.addTasks(discovered.events.sortedBy(Event::kickoff).mapIndexed { position, event ->
            TaskRow(batch.id, event.id, position, oddynJson.encodeToString(event), TaskState.QUEUED.name, now.toEpochMilli())
        })
        checkpoint(batch.id, now, last = null, step = "DISCOVERY")
        batch
    }

    private suspend fun checkpoint(batchId: String, now: Instant, last: String?, step: String) {
        val tasks = analysis.tasks(batchId)
        val (pending, complete) = tasks.partition { it.state == TaskState.QUEUED.name }
        analysis.checkpoint(CheckpointRow(
            batchId = batchId, timestamp = now.toEpochMilli(),
            processedIds = oddynJson.encodeToString(complete.map { it.eventId }),
            pendingIds = oddynJson.encodeToString(pending.map { it.eventId }),
            lastEventId = last, lastStep = step, nextAction = if (pending.isEmpty()) "FINISH" else "REVIEW_MARKETS"
        ))
    }

    suspend fun saveForecast(leg: Leg): Boolean = database.withTransaction {
        val now = Instant.now()
        checkAvailable(leg, now)
        val snapshot = oddynJson.encodeToString(leg)
        val record = ForecastRow(id("ODF"), digest(snapshot), now.toEpochMilli(), leg.mode.name, snapshot)
        val created = journal.addForecast(record) != -1L
        if (created) audit("FORECAST_SAVED", record.id, now)
        created
    }

    suspend fun addDraft(leg: Leg): Unit = database.withTransaction {
        val now = Instant.now()
        checkAvailable(leg, now)
        val draft = journal.currentDraft(leg.mode.name).map(DraftRow::toLeg)
        if (draft.any { it.key == leg.key }) throw InputProblem("Этот исход уже в подборке.")
        if (draft.size >= 10) throw InputProblem("В одном экспрессе до 10 плеч.")
        journal.addDraft(DraftRow(leg.mode.name, leg.key, now.toEpochMilli(), oddynJson.encodeToString(leg)))
    }

    suspend fun removeDraft(mode: Mode, key: String) = journal.removeDraft(mode.name, key)

    suspend fun saveExpress(mode: Mode): Unit = database.withTransaction {
        val now = Instant.now()
        val legs = journal.currentDraft(mode.name).map(DraftRow::toLeg)
        val review = reviewExpress(legs, now)
        if (!review.canSave) throw InputProblem(review.notes.first())
        val row = ExpressRow(id("ODX"), now.toEpochMilli(), mode.name, review.coefficient?.toPlainString(), oddynJson.encodeToString(review.notes))
        journal.addExpress(row)
        journal.addLegs(legs.mapIndexed { index, leg -> ExpressLegRow(row.id, index, oddynJson.encodeToString(leg)) })
        journal.clearDraft(mode.name)
        audit("EXPRESS_SAVED", row.id, now)
    }

    suspend fun addTicket(input: TicketInput): Unit = database.withTransaction {
        val bookmaker = input.bookmaker.trim()
        val reference = input.reference.trim()
        val description = input.description.trim()
        if (bookmaker.isEmpty() || reference.isEmpty() || description.isEmpty()) {
            throw InputProblem("Заполните букмекера, номер купона и описание.")
        }
        if (bookmaker.length > 100 || reference.length > 120 || description.length > 300) {
            throw InputProblem("Сократите имя букмекера, номер или описание купона.")
        }
        val stake = money(input.stake)?.takeIf { it.signum() > 0 }
            ?: throw InputProblem("Введите положительную сумму в рублях с точностью до копеек.")
        val odds = decimalOdds(input.odds) ?: throw InputProblem("Введите фактический десятичный коэффициент больше 1.")
        val now = Instant.now()
        val ticket = TicketRow(
            id("ODT"), digest(bookmaker.lowercase(Locale.ROOT), reference.lowercase(Locale.ROOT)), now.toEpochMilli(),
            bookmaker, reference, input.kind.name, description, stake.toPlainString(), odds.toPlainString(),
            TicketResult.PENDING.name
        )
        if (journal.addTicket(ticket) == -1L) throw InputProblem("Купон с таким номером уже есть у этого букмекера.")
        audit("TICKET_CONFIRMED_BY_USER", ticket.id, now)
    }

    suspend fun settleTicket(id: String, result: TicketResult, payoutText: String): Unit = database.withTransaction {
        val payout = money(payoutText) ?: throw InputProblem("Введите фактическую выплату в рублях, включая 0 ₽.")
        val ticket = journal.ticket(id) ?: throw InputProblem("Купон отсутствует в журнале.")
        if (!payoutMatches(result, payout, ticket.stake.toBigDecimal())) {
            throw InputProblem("Выплата не соответствует выбранному исходу. Сверьте расчёт букмекера.")
        }
        val now = Instant.now()
        if (journal.settle(id, result.name, payout.toPlainString(), now.toEpochMilli()) != 1) {
            throw InputProblem("Расчёт по этому купону уже сохранён.")
        }
        audit("TICKET_SETTLED_BY_USER", id, now)
    }

    suspend fun clearAnalysis(): Unit = database.withTransaction {
        analysis.clear()
        audit("ANALYSIS_CACHE_CLEARED", "local", Instant.now())
    }

    private suspend fun audit(action: String, id: String, now: Instant) {
        journal.audit(AuditRow(timestamp = now.toEpochMilli(), action = action, objectId = id))
    }

    private fun checkAvailable(leg: Leg, now: Instant) {
        if (leg.mode == Mode.REAL && Instant.parse(leg.startsAt) <= now) {
            throw InputProblem("Матч уже начался. Обновите поиск.")
        }
        if (leg.market.quote?.usable(leg.mode, now) == null) {
            throw InputProblem("Для сохранения нужна действительная котировка.")
        }
    }
}

private fun DraftRow.toLeg(): Leg = oddynJson.decodeFromString(snapshot)
private fun TicketRow.toTicket() = Ticket(
    id, Instant.ofEpochMilli(createdAt), bookmaker, reference, TicketKind.valueOf(kind), description, odds,
    TicketAmounts(stake.toBigDecimal(), payout?.toBigDecimal(), TicketResult.valueOf(result))
)

private fun id(prefix: String) = "$prefix-${UUID.randomUUID()}"
private fun digest(vararg parts: String): String = MessageDigest.getInstance("SHA-256")
    .digest(oddynJson.encodeToString(parts.toList()).toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
