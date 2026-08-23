package com.keyescape.notifier.monitoring

import com.keyescape.notifier.data.Branch
import com.keyescape.notifier.data.AvailabilityEventEntity
import com.keyescape.notifier.data.MonitoringGroupEntity
import com.keyescape.notifier.data.MonitoringGroupWithSlots
import com.keyescape.notifier.data.MonitoringRepository
import com.keyescape.notifier.data.MonitoringSlotEntity
import com.keyescape.notifier.data.Provider
import com.keyescape.notifier.data.ProviderRepositoryResolver
import com.keyescape.notifier.data.ThemeDto
import com.keyescape.notifier.notification.AvailabilityNotificationManager
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

data class CheckedMonitoringSlot(
    val group: MonitoringGroupEntity,
    val slot: MonitoringSlotEntity
)

data class MonitoringCheckSummary(
    val checkedSlots: List<CheckedMonitoringSlot> = emptyList(),
    val availableSlots: List<CheckedMonitoringSlot> = emptyList(),
    val failedGroupCount: Int = 0
)

/** Shared availability decision logic for periodic work and the group-level "지금 확인" action. */
@Singleton
class CheckMonitoringTargetsUseCase @Inject constructor(
    private val monitoringRepository: MonitoringRepository,
    private val providers: ProviderRepositoryResolver,
    private val notifier: AvailabilityNotificationManager
) {
    suspend fun checkActive(): MonitoringCheckSummary = check(monitoringRepository.activeGroups())

    suspend fun check(groups: List<MonitoringGroupWithSlots>): MonitoringCheckSummary {
        val checked = mutableListOf<CheckedMonitoringSlot>()
        val available = mutableListOf<CheckedMonitoringSlot>()
        var failures = 0
        groups.flatMap { groupWithSlots ->
            groupWithSlots.slots.map { slot -> CheckedMonitoringSlot(groupWithSlots.group, slot) }
        }.groupBy { listOf(it.group.provider, it.group.branchId.toString(), it.group.themeNum.toString(), it.slot.date) }
            .forEach { (key, slotsForDate) ->
                val sample = slotsForDate.first()
                val provider = Provider.fromStorage(sample.group.provider)
                if (provider == null) { failures++; return@forEach }
                val branch = Branch(sample.group.branchId, sample.group.branchName, "", provider, sample.group.providerMetadata)
                val theme = ThemeDto(infoNum = sample.group.themeInfoNum, themeNum = sample.group.themeNum, name = sample.group.themeName)
                providers.get(provider).times(branch, theme, sample.slot.date).onSuccess { timeSlots ->
                    val byTime = timeSlots.associateBy { it.displayTime }
                    slotsForDate.forEach { monitored ->
                        val isAvailable = byTime[monitored.slot.desiredTime]?.available == true
                        val availability = if (isAvailable) "AVAILABLE" else "UNAVAILABLE"
                        val checkedAt = System.currentTimeMillis()
                        val event = { id: String, type: com.keyescape.notifier.data.AvailabilityEventType ->
                            AvailabilityEventEntity(
                                id = id,
                                provider = monitored.group.provider,
                                branchId = monitored.group.branchId,
                                branchName = monitored.group.branchName,
                                themeNum = monitored.group.themeNum,
                                themeName = monitored.group.themeName,
                                date = monitored.slot.date,
                                desiredTime = monitored.slot.desiredTime,
                                eventType = type,
                                detectedAt = checkedAt,
                                monitoringGroupId = monitored.group.id,
                                monitoringSlotId = monitored.slot.id
                            )
                        }
                        val persisted = monitoringRepository.recordSlotCheck(
                            slotId = monitored.slot.id,
                            availability = availability,
                            checkedAt = checkedAt,
                            foundEvent = event(UUID.randomUUID().toString(), com.keyescape.notifier.data.AvailabilityEventType.AVAILABLE_FOUND),
                            goneEvent = event(UUID.randomUUID().toString(), com.keyescape.notifier.data.AvailabilityEventType.AVAILABLE_GONE)
                        )
                        if (persisted?.shouldNotify == true) {
                            notifier.show(
                                monitored.group,
                                monitored.slot,
                                providers.get(provider).reservationUrl(branch, theme)
                            )
                        }
                        if (persisted != null) {
                            checked += monitored
                            if (isAvailable) available += monitored
                        }
                    }
                }.onFailure { failures++ }
            }
        return MonitoringCheckSummary(checked, available, failures)
    }
}
