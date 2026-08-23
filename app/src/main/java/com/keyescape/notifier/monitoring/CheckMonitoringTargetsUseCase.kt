package com.keyescape.notifier.monitoring

import com.keyescape.notifier.data.KeyescapeRepository
import com.keyescape.notifier.data.MonitoringGroupEntity
import com.keyescape.notifier.data.MonitoringGroupWithSlots
import com.keyescape.notifier.data.MonitoringRepository
import com.keyescape.notifier.data.MonitoringSlotEntity
import com.keyescape.notifier.notification.AvailabilityNotificationManager
import javax.inject.Inject
import javax.inject.Singleton

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
    private val keyescapeRepository: KeyescapeRepository,
    private val notifier: AvailabilityNotificationManager
) {
    suspend fun checkActive(): MonitoringCheckSummary = check(monitoringRepository.activeGroups())

    suspend fun check(groups: List<MonitoringGroupWithSlots>): MonitoringCheckSummary {
        val checked = mutableListOf<CheckedMonitoringSlot>()
        val available = mutableListOf<CheckedMonitoringSlot>()
        var failures = 0
        groups.flatMap { groupWithSlots ->
            groupWithSlots.slots.map { slot -> CheckedMonitoringSlot(groupWithSlots.group, slot) }
        }.groupBy { Triple(it.group.branchId, it.group.themeNum, it.slot.date) }
            .forEach { (key, slotsForDate) ->
                keyescapeRepository.times(key.first, key.second, key.third).onSuccess { timeSlots ->
                    val byTime = timeSlots.associateBy { it.displayTime }
                    slotsForDate.forEach { monitored ->
                        val isAvailable = byTime[monitored.slot.desiredTime]?.available == true
                        val availability = if (isAvailable) "AVAILABLE" else "UNAVAILABLE"
                        val shouldNotify = isAvailable && (
                            monitored.slot.lastAvailability != "AVAILABLE" ||
                                monitored.slot.lastNotifiedAvailability != "AVAILABLE"
                            )
                        if (shouldNotify) {
                            notifier.show(
                                monitored.group,
                                monitored.slot,
                                keyescapeRepository.reservationUrl(
                                    monitored.group.branchId,
                                    monitored.group.themeNum,
                                    monitored.group.themeInfoNum
                                )
                            )
                        }
                        monitoringRepository.updateSlotResult(
                            monitored.slot.id,
                            availability,
                            availability,
                            System.currentTimeMillis()
                        )
                        checked += monitored
                        if (isAvailable) available += monitored
                    }
                }.onFailure { failures++ }
            }
        return MonitoringCheckSummary(checked, available, failures)
    }
}
