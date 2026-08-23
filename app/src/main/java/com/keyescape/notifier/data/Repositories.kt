package com.keyescape.notifier.data

import android.util.Log
import com.keyescape.notifier.BuildConfig
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

enum class Provider(val storageKey: String, val displayName: String) {
    KEYESCAPE("KEYESCAPE", "키이스케이프"),
    ZEROWORLD("ZEROWORLD", "제로월드");

    companion object {
        fun fromStorage(value: String): Provider? = values().firstOrNull { it.storageKey == value }
    }
}

data class Branch(val id: Int, val name: String, val region: String, val provider: Provider, val providerMetadata: String = "")

@Singleton class ProviderBranchCatalog @Inject constructor() {
    // Region labels deliberately do not infer location from a branch name.
    // Add a future provider's explicitly mapped branches here; its API stays in a separate repository.
    val branches = listOf(
        Branch(3, "키이스케이프 강남점", "서울 강남", Provider.KEYESCAPE), Branch(10, "키이스케이프 홍대점", "서울 홍대", Provider.KEYESCAPE),
        Branch(14, "더오름", "서울 강남", Provider.KEYESCAPE), Branch(16, "우주라이크", "서울 강남", Provider.KEYESCAPE),
        Branch(18, "메모리컴퍼니", "서울 강남", Provider.KEYESCAPE), Branch(19, "LOG_IN 1", "서울 강남", Provider.KEYESCAPE),
        Branch(20, "LOG_IN 2", "서울 강남", Provider.KEYESCAPE), Branch(22, "STATION", "서울 강남", Provider.KEYESCAPE),
        Branch(23, "후즈데어", "서울 강남", Provider.KEYESCAPE), Branch(9, "키이스케이프 부산점", "부산", Provider.KEYESCAPE), Branch(7, "키이스케이프 전주점", "전주", Provider.KEYESCAPE),
        Branch(1, "김포본점", "김포", Provider.ZEROWORLD, "A"), Branch(4, "강남점", "서울 강남", Provider.ZEROWORLD, "A"),
        Branch(5, "홍대점", "서울 홍대", Provider.ZEROWORLD, "A"), Branch(2, "다이브 건대점", "서울 건대", Provider.ZEROWORLD, "B")
    )
    fun regions() = branches.map { it.region }.distinct()
    fun providers(region: String) = branches.filter { it.region == region }.map { it.provider }.distinct()
    fun branches(region: String, provider: Provider) = branches.filter { it.region == region && it.provider == provider }
}

@Singleton class KeyescapeRepository @Inject constructor(private val api: KeyescapeApiService) {
    suspend fun themes(branchId: Int): Result<List<ThemeDto>> = runCatching {
        api.themes(branchId = branchId).let { response ->
            if (!response.status) error(response.msg.ifBlank { "테마 목록을 불러오지 못했습니다." })
            if (BuildConfig.DEBUG) {
                Log.d(THEME_LOG_TAG, "Theme API response: branchId=$branchId, count=${response.data.size}")
                response.data.forEach { theme ->
                    Log.d(THEME_LOG_TAG, "themeInfoNum=${theme.infoNum}, themeNum=${theme.themeNum}, name=${theme.name}")
                }
            }
            response.data
        }
    }
    suspend fun themeDate(infoNum: Int): Result<ThemeDateResponse> = runCatching { api.themeDate(infoNum = infoNum).let { if (it.status) it else error(it.msg.ifBlank { "테마 정보를 불러오지 못했습니다." }) } }
    suspend fun times(branchId: Int, themeNum: Int, date: String): Result<List<TimeSlotDto>> = runCatching { api.times(date = date, branchId = branchId, themeNum = themeNum).let { if (it.status) it.data else error(it.msg.ifBlank { "시간표를 불러오지 못했습니다." }) } }
    fun reservationUrl(branchId: Int, themeNum: Int, infoNum: Int) = "https://www.keyescape.com/reservation1.php?zizum_num=$branchId&theme_num=$themeNum&theme_info_num=$infoNum"

    private companion object { const val THEME_LOG_TAG = "KeyescapeThemes" }
}

@Singleton class MonitoringRepository @Inject constructor(private val dao: MonitoringDao) {
    fun allGroups(): Flow<List<MonitoringGroupWithSlots>> = dao.observeAllGroups()
    fun group(id: String): Flow<MonitoringGroupWithSlots?> = dao.observeGroup(id)
    suspend fun activeGroups(): List<MonitoringGroupWithSlots> = dao.activeGroups()
    suspend fun createOrAppend(group: MonitoringGroupEntity, slots: List<MonitoringSlotEntity>): String = dao.createOrAppend(group, slots)
    suspend fun deleteGroup(id: String) = dao.deleteGroup(id)
    suspend fun setGroupEnabled(id: String, value: Boolean) = dao.setGroupEnabled(id, value)
    suspend fun updateSlotResult(id: String, availability: String, notified: String, checkedAt: Long) = dao.updateSlotResult(id, availability, notified, checkedAt)
    suspend fun recordSlotCheck(slotId: String, availability: String, checkedAt: Long, foundEvent: AvailabilityEventEntity, goneEvent: AvailabilityEventEntity): SlotCheckPersistence? = dao.recordSlotCheck(slotId, availability, checkedAt, foundEvent, goneEvent)
    fun availabilityEvents(): Flow<List<AvailabilityEventEntity>> = dao.observeAvailabilityEvents()
    suspend fun replaceSlots(groupId: String, slots: List<MonitoringSlotEntity>) = dao.replaceSlots(groupId, slots)
}
