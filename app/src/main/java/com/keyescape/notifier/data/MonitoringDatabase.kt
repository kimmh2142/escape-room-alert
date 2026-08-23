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
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "monitoring_groups",
    indices = [Index(value = ["provider", "branchId", "themeNum"], unique = true)]
)
data class MonitoringGroupEntity(
    @PrimaryKey val id: String,
    val provider: String = "KEYESCAPE",
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

    @Query("DELETE FROM monitoring_slots WHERE groupId = :groupId")
    suspend fun deleteSlots(groupId: String)

    @Transaction
    suspend fun replaceSlots(groupId: String, slots: List<MonitoringSlotEntity>) {
        deleteSlots(groupId)
        insertSlots(slots.map { it.copy(groupId = groupId) })
    }
}

@Database(
    entities = [MonitoringGroupEntity::class, MonitoringSlotEntity::class],
    version = 2,
    exportSchema = false
)
abstract class MonitoringDatabase : RoomDatabase() {
    abstract fun monitoringDao(): MonitoringDao
}
