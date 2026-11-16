package com.oddyn.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "analysis_batches", indices = [Index("scope")])
data class BatchRow(
    @PrimaryKey val id: String,
    val scope: String,
    val createdAt: Long,
    val coverage: String
)

@Entity(
    tableName = "analysis_queue",
    primaryKeys = ["batchId", "eventId"],
    foreignKeys = [ForeignKey(entity = BatchRow::class, parentColumns = ["id"], childColumns = ["batchId"], onDelete = ForeignKey.CASCADE)]
)
data class TaskRow(
    val batchId: String,
    val eventId: String,
    val position: Int,
    val snapshot: String,
    val state: String,
    val updatedAt: Long
)

@Entity(
    tableName = "checkpoints",
    indices = [Index("batchId")],
    foreignKeys = [ForeignKey(entity = BatchRow::class, parentColumns = ["id"], childColumns = ["batchId"], onDelete = ForeignKey.CASCADE)]
)
data class CheckpointRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: String,
    val timestamp: Long,
    val processedIds: String,
    val pendingIds: String,
    val lastEventId: String?,
    val lastStep: String,
    val nextAction: String
)

@Entity(
    tableName = "market_observations",
    primaryKeys = ["batchId", "eventId", "marketId"],
    foreignKeys = [ForeignKey(entity = BatchRow::class, parentColumns = ["id"], childColumns = ["batchId"], onDelete = ForeignKey.CASCADE)]
)
data class ObservationRow(val batchId: String, val eventId: String, val marketId: String, val snapshot: String)

@Entity(tableName = "forecasts", indices = [Index("identity", unique = true)])
data class ForecastRow(
    @PrimaryKey val id: String,
    val identity: String,
    val createdAt: Long,
    val mode: String,
    val snapshot: String
)

@Entity(tableName = "expresses")
data class ExpressRow(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val mode: String,
    val coefficient: String?,
    val notes: String
)

@Entity(
    tableName = "express_legs",
    primaryKeys = ["expressId", "position"],
    foreignKeys = [ForeignKey(entity = ExpressRow::class, parentColumns = ["id"], childColumns = ["expressId"], onDelete = ForeignKey.CASCADE)]
)
data class ExpressLegRow(val expressId: String, val position: Int, val snapshot: String)

@Entity(tableName = "tickets", indices = [Index("identity", unique = true)])
data class TicketRow(
    @PrimaryKey val id: String,
    val identity: String,
    val createdAt: Long,
    val bookmaker: String,
    val reference: String,
    val kind: String,
    val description: String,
    val stake: String,
    val odds: String,
    val result: String,
    val payout: String? = null,
    val settledAt: Long? = null
)

@Entity(tableName = "audit")
data class AuditRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val action: String,
    val objectId: String
)

@Entity(tableName = "sources")
data class SourceRow(@PrimaryKey val id: String, val snapshot: String)

@Entity(tableName = "express_draft", primaryKeys = ["mode", "legKey"])
data class DraftRow(val mode: String, val legKey: String, val createdAt: Long, val snapshot: String)

data class BatchWithTasks(
    @Embedded val batch: BatchRow,
    @Relation(parentColumn = "id", entityColumn = "batchId") val tasks: List<TaskRow>
)

data class ExpressWithLegs(
    @Embedded val express: ExpressRow,
    @Relation(parentColumn = "id", entityColumn = "expressId") val legs: List<ExpressLegRow>
)

@Dao
interface AnalysisDao {
    @Transaction
    @Query("SELECT * FROM analysis_batches WHERE scope = :scope ORDER BY createdAt DESC, rowid DESC LIMIT 1")
    fun observe(scope: String): Flow<BatchWithTasks?>

    @Transaction
    @Query("SELECT * FROM analysis_batches WHERE scope = :scope ORDER BY createdAt DESC, rowid DESC LIMIT 1")
    suspend fun latest(scope: String): BatchWithTasks?

    @Query("SELECT * FROM analysis_queue WHERE batchId = :batchId ORDER BY position")
    suspend fun tasks(batchId: String): List<TaskRow>

    @Insert suspend fun addBatch(batch: BatchRow)
    @Insert suspend fun addTasks(tasks: List<TaskRow>)
    @Insert suspend fun checkpoint(row: CheckpointRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun observeMarkets(rows: List<ObservationRow>)

    @Query("UPDATE analysis_queue SET state = :state, snapshot = :snapshot, updatedAt = :time WHERE batchId = :batchId AND eventId = :eventId")
    suspend fun updateTask(batchId: String, eventId: String, state: String, snapshot: String, time: Long)

    @Query("UPDATE analysis_batches SET coverage = :coverage WHERE id = :batchId")
    suspend fun updateCoverage(batchId: String, coverage: String)

    @Query("SELECT COUNT(*) FROM analysis_batches")
    fun batchCount(): Flow<Int>

    @Query("DELETE FROM analysis_batches")
    suspend fun clear()
}

@Dao
interface JournalDao {
    @Query("SELECT * FROM forecasts ORDER BY createdAt DESC, rowid DESC")
    fun forecasts(): Flow<List<ForecastRow>>

    @Transaction
    @Query("SELECT * FROM expresses ORDER BY createdAt DESC, rowid DESC")
    fun expresses(): Flow<List<ExpressWithLegs>>

    @Query("SELECT * FROM tickets ORDER BY createdAt DESC, rowid DESC")
    fun tickets(): Flow<List<TicketRow>>

    @Query("SELECT * FROM express_draft ORDER BY createdAt, rowid")
    fun draft(): Flow<List<DraftRow>>

    @Query("SELECT * FROM express_draft WHERE mode = :mode ORDER BY createdAt, rowid")
    suspend fun currentDraft(mode: String): List<DraftRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addForecast(row: ForecastRow): Long

    @Insert suspend fun addExpress(row: ExpressRow)
    @Insert suspend fun addLegs(rows: List<ExpressLegRow>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTicket(row: TicketRow): Long

    @Query("SELECT * FROM tickets WHERE id = :id")
    suspend fun ticket(id: String): TicketRow?

    @Query("UPDATE tickets SET result = :result, payout = :payout, settledAt = :time WHERE id = :id AND result = 'PENDING'")
    suspend fun settle(id: String, result: String, payout: String, time: Long): Int

    @Insert suspend fun addDraft(row: DraftRow)

    @Query("DELETE FROM express_draft WHERE mode = :mode AND legKey = :key")
    suspend fun removeDraft(mode: String, key: String)

    @Query("DELETE FROM express_draft WHERE mode = :mode")
    suspend fun clearDraft(mode: String)

    @Insert suspend fun audit(row: AuditRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSources(rows: List<SourceRow>)
}

@Database(
    entities = [
        BatchRow::class, TaskRow::class, CheckpointRow::class, ObservationRow::class,
        ForecastRow::class, ExpressRow::class, ExpressLegRow::class, TicketRow::class,
        AuditRow::class, SourceRow::class, DraftRow::class
    ],
    version = 1,
    exportSchema = true
)
abstract class OddynDatabase : RoomDatabase() {
    abstract fun analysis(): AnalysisDao
    abstract fun journal(): JournalDao

    companion object {
        @Volatile private var instance: OddynDatabase? = null

        fun get(context: Context): OddynDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, OddynDatabase::class.java, "oddyn.db"
            ).build().also { instance = it }
        }
    }
}
