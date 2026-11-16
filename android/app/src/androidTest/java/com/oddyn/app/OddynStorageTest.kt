package com.oddyn.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oddyn.app.data.BatchRow
import com.oddyn.app.data.DemoSchedule
import com.oddyn.app.data.InputProblem
import com.oddyn.app.data.OddynDatabase
import com.oddyn.app.data.OddynRepository
import com.oddyn.app.data.Preferences
import com.oddyn.app.data.TaskRow
import com.oddyn.app.data.TaskState
import com.oddyn.app.data.TicketInput
import com.oddyn.app.domain.TicketKind
import com.oddyn.app.domain.TicketResult
import com.oddyn.app.domain.oddynJson
import com.oddyn.app.domain.ticketStats
import java.math.BigDecimal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OddynStorageTest {
    @Test fun resumesOnlyPendingRowsAndKeepsCheckpoint() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Room.inMemoryDatabaseBuilder(context, OddynDatabase::class.java).build().use { database ->
            val preferences = Preferences()
            val sample = DemoSchedule(context).discover(preferences.date)
            val events = sample.events.take(2)
            val dao = database.analysis()
            dao.addBatch(BatchRow("test-batch", preferences.scope, 14L, oddynJson.encodeToString(sample.coverage)))
            dao.addTasks(events.mapIndexed { index, event ->
                val state = if (index == 0) TaskState.DONE else TaskState.QUEUED
                TaskRow("test-batch", event.id, index, oddynJson.encodeToString(event), state.name, 14L)
            })
            val restarted = OddynRepository(context, database)
            restarted.search(preferences)
            val restored = restarted.observe(preferences).first()
            assertEquals(2, restored.processed)
            assertEquals(0, restored.waiting)
            assertEquals(events[0].id, restored.events[0].id)
            assertEquals(14L, dao.tasks("test-batch").first().updatedAt)
            database.openHelper.readableDatabase.query("SELECT lastEventId, nextAction FROM checkpoints").use {
                assertTrue(it.moveToFirst())
                assertEquals(events[1].id, it.getString(0))
                assertEquals("FINISH", it.getString(1))
            }
        }
    }

    @Test fun confirmsOneRealTicketAndCountsItsActualPayout() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Room.inMemoryDatabaseBuilder(context, OddynDatabase::class.java).build().use { database ->
            val repo = OddynRepository(context, database)
            val input = TicketInput("Тестовая БК", "001", TicketKind.EXPRESS, "Тестовый купон", "125,50", "2,10")
            repo.addTicket(input)
            val duplicate = runCatching { repo.addTicket(input) }.exceptionOrNull()
            assertTrue(duplicate is InputProblem)
            val ticket = repo.tickets.first().single()
            assertEquals(TicketResult.PENDING, ticket.amounts.result)
            repo.settleTicket(ticket.id, TicketResult.WIN, "263,55")
            val stats = ticketStats(repo.tickets.first().map { it.amounts })
            assertEquals(1, stats.settled)
            assertEquals(BigDecimal("138.05"), stats.profit)
        }
    }
}
