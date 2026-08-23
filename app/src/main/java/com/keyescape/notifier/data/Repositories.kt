package com.keyescape.notifier.data

import android.util.Log
import com.keyescape.notifier.BuildConfig
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

data class Branch(val id: Int, val name: String, val region: String)

@Singleton class BranchCatalog @Inject constructor() {
    // Region labels deliberately do not infer location from a branch name.
    val branches = listOf(
        Branch(3, "키이스케이프 강남점", "서울 강남"), Branch(10, "키이스케이프 홍대점", "서울 홍대"),
        Branch(14, "더오름", "서울 홍대"), Branch(16, "우주라이크", "서울 홍대"),
        Branch(18, "메모리컴퍼니", "서울 강남"), Branch(19, "LOG_IN 1", "서울 강남"),
        Branch(20, "LOG_IN 2", "서울 강남"), Branch(22, "STATION", "서울 강남"),
        Branch(23, "후즈데어", "서울 강남"), Branch(9, "키이스케이프 부산점", "부산"), Branch(7, "키이스케이프 전주점", "전주")
    )
    fun regions() = branches.map { it.region }.distinct()
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
    suspend fun replaceSlots(groupId: String, slots: List<MonitoringSlotEntity>) = dao.replaceSlots(groupId, slots)
}
