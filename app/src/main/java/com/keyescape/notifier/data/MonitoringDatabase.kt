package com.keyescape.notifier.data

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
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "monitoring_groups",
    indices = [Index(value = ["provider", "branchId", "themeNum"], unique = true)]
)
data class MonitoringGroupEntity(
    @PrimaryKey val id: String,
    val provider: String = "KEYESCAPE",
    val providerMetadata: String = "",
    val branchId: Int,
    val branchName: String,
    val themeNum: Int,
    val themeInfoNum: Int,
    val themeName: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "monitoring_slots",
    foreignKeys = [
        ForeignKey(
            entity = MonitoringGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("groupId"), Index(value = ["groupId", "date", "desiredTime"], unique = true)]
)
data class MonitoringSlotEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val date: String,
    val desiredTime: String,
    val lastAvailability: String = "UNKNOWN",
    val lastCheckedAt: Long? = null,
    val lastNotifiedAvailability: String = "UNKNOWN"
)

enum class AvailabilityEventType { AVAILABLE_FOUND, AVAILABLE_GONE }

class AvailabilityEventConverters {
    @TypeConverter fun fromEventType(value: AvailabilityEventType): String = value.name
    @TypeConverter fun toEventType(value: String): AvailabilityEventType = AvailabilityEventType.valueOf(value)
}

/** Snapshot fields keep the log readable after its monitoring condition is edited or deleted. */
@Entity(tableName = "availability_events", indices = [Index(value = ["detectedAt"])])
data class AvailabilityEventEntity(
    @PrimaryKey val id: String,
    val provider: String,
    val branchId: Int,
    val branchName: String,
    val themeNum: Int,
    val themeName: String,
    val date: String,
    val desiredTime: String,
    val eventType: AvailabilityEventType,
    val detectedAt: Long,
    val monitoringGroupId: String? = null,
    val monitoringSlotId: String? = null
)

data class SlotCheckPersistence(
    val shouldNotify: Boolean,
    val eventType: AvailabilityEventType?
)

data class MonitoringGroupWithSlots(
    @Embedded val group: MonitoringGroupEntity,
    @Relation(parentColumn = "id", entityColumn = "groupId")
    val slots: List<MonitoringSlotEntity>
)

@Dao
interface MonitoringDao {
    @Transaction
    @Query("SELECT * FROM monitoring_groups ORDER BY enabled DESC, createdAt DESC")
    fun observeAllGroups(): Flow<List<MonitoringGroupWithSlots>>

    @Transaction
    @Query("SELECT * FROM monitoring_groups WHERE id = :id")
    fun observeGroup(id: String): Flow<MonitoringGroupWithSlots?>

    @Transaction
    @Query("SELECT * FROM monitoring_groups WHERE enabled = 1")
    suspend fun activeGroups(): List<MonitoringGroupWithSlots>

    @Query("SELECT * FROM availability_events ORDER BY detectedAt DESC")
    fun observeAvailabilityEvents(): Flow<List<AvailabilityEventEntity>>

    @Query("SELECT * FROM monitoring_groups WHERE provider = :provider AND branchId = :branchId AND themeNum = :themeNum LIMIT 1")
    suspend fun findGroup(provider: String, branchId: Int, themeNum: Int): MonitoringGroupEntity?

    @Insert
    suspend fun insertGroup(group: MonitoringGroupEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSlots(slots: List<MonitoringSlotEntity>)

    @Transaction
    suspend fun createOrAppend(group: MonitoringGroupEntity, slots: List<MonitoringSlotEntity>): String {
        val existing = findGroup(group.provider, group.branchId, group.themeNum)
        val groupId = existing?.id ?: group.id
        if (existing == null) insertGroup(group)
        insertSlots(slots.map { it.copy(groupId = groupId) })
        return groupId
    }

    @Query("DELETE FROM monitoring_groups WHERE id = :id")
    suspend fun deleteGroup(id: String)

    @Query("UPDATE monitoring_groups SET enabled = :enabled WHERE id = :id")
    suspend fun setGroupEnabled(id: String, enabled: Boolean)

    @Query("UPDATE monitoring_slots SET lastAvailability = :availability, lastNotifiedAvailability = :notified, lastCheckedAt = :checkedAt WHERE id = :id")
    suspend fun updateSlotResult(id: String, availability: String, notified: String, checkedAt: Long)

    @Query("SELECT * FROM monitoring_slots WHERE id = :id LIMIT 1")
    suspend fun slot(id: String): MonitoringSlotEntity?

    @Insert
    suspend fun insertAvailabilityEvent(event: AvailabilityEventEntity)

    /** Persists the slot state and any true N↔Y transition as one transaction. */
    @Transaction
    suspend fun recordSlotCheck(
        slotId: String,
        availability: String,
        checkedAt: Long,
        foundEvent: AvailabilityEventEntity,
        goneEvent: AvailabilityEventEntity
    ): SlotCheckPersistence? {
        val current = slot(slotId) ?: return null
        val eventType = when {
            current.lastAvailability == "UNAVAILABLE" && availability == "AVAILABLE" -> AvailabilityEventType.AVAILABLE_FOUND
            current.lastAvailability == "AVAILABLE" && availability == "UNAVAILABLE" -> AvailabilityEventType.AVAILABLE_GONE
            else -> null
        }
        val shouldNotify = availability == "AVAILABLE" && (
            current.lastAvailability != "AVAILABLE" || current.lastNotifiedAvailability != "AVAILABLE"
        )
        updateSlotResult(slotId, availability, availability, checkedAt)
        when (eventType) {
            AvailabilityEventType.AVAILABLE_FOUND -> insertAvailabilityEvent(foundEvent)
            AvailabilityEventType.AVAILABLE_GONE -> insertAvailabilityEvent(goneEvent)
            null -> Unit
        }
        return SlotCheckPersistence(shouldNotify, eventType)
    }

    @Query("DELETE FROM monitoring_slots WHERE groupId = :groupId")
    suspend fun deleteSlots(groupId: String)

    @Transaction
    suspend fun replaceSlots(groupId: String, slots: List<MonitoringSlotEntity>) {
        deleteSlots(groupId)
        insertSlots(slots.map { it.copy(groupId = groupId) })
    }
}

@Database(
    entities = [MonitoringGroupEntity::class, MonitoringSlotEntity::class, AvailabilityEventEntity::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(AvailabilityEventConverters::class)
abstract class MonitoringDatabase : RoomDatabase() {
    abstract fun monitoringDao(): MonitoringDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE monitoring_groups ADD COLUMN providerMetadata TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS availability_events (
                        id TEXT NOT NULL PRIMARY KEY,
                        provider TEXT NOT NULL,
                        branchId INTEGER NOT NULL,
                        branchName TEXT NOT NULL,
                        themeNum INTEGER NOT NULL,
                        themeName TEXT NOT NULL,
                        date TEXT NOT NULL,
                        desiredTime TEXT NOT NULL,
                        eventType TEXT NOT NULL,
                        detectedAt INTEGER NOT NULL,
                        monitoringGroupId TEXT,
                        monitoringSlotId TEXT
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_availability_events_detectedAt ON availability_events (detectedAt)")
            }
        }
    }
}
