package com.example.autosilent.ui.fakes

import com.example.autosilent.domain.controller.SoundController
import com.example.autosilent.domain.model.BoundaryEvent
import com.example.autosilent.domain.model.BoundaryType
import com.example.autosilent.domain.model.OccurrenceOverride
import com.example.autosilent.domain.model.Schedule
import com.example.autosilent.domain.model.SoundMode
import com.example.autosilent.domain.repository.ScheduleRepository
import com.example.autosilent.scheduler.AlarmScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class FakeScheduleRepository : ScheduleRepository {

    private val idGen = AtomicLong(1)
    private val overrideIdGen = AtomicLong(1)

    private val schedules = LinkedHashMap<Long, Schedule>()
    private val overrides = LinkedHashMap<Long, OccurrenceOverride>()

    private val schedulesFlow = MutableStateFlow<List<Schedule>>(emptyList())
    private val overridesFlow = MutableStateFlow<List<OccurrenceOverride>>(emptyList())

    var shouldFailOnInsert = false
    var shouldFailOnUpdate = false
    var shouldFailOnDelete = false

    override fun getSchedulesFlow(): Flow<List<Schedule>> = schedulesFlow

    override suspend fun getScheduleById(id: Long): Schedule? = schedules[id]

    override suspend fun getEnabledSchedules(): List<Schedule> =
        schedules.values.filter { it.enabled }.sortedBy { it.startTime }

    override fun getEnabledSchedulesFlow(): Flow<List<Schedule>> =
        schedulesFlow.map { it.filter { s -> s.enabled } }

    override suspend fun insertSchedule(schedule: Schedule): Long {
        if (shouldFailOnInsert) throw IllegalStateException("Database error on insert")
        val id = if (schedule.id == 0L) idGen.getAndIncrement() else schedule.id
        val stored = schedule.copy(id = id)
        schedules[id] = stored
        schedulesFlow.value = schedules.values.toList()
        return id
    }

    override suspend fun updateSchedule(schedule: Schedule) {
        if (shouldFailOnUpdate) throw IllegalStateException("Database error on update")
        schedules[schedule.id] = schedule
        schedulesFlow.value = schedules.values.toList()
    }

    override suspend fun deleteSchedule(id: Long) {
        if (shouldFailOnDelete) throw IllegalStateException("Database error on delete")
        schedules.remove(id)
        overrides.entries.removeIf { it.value.scheduleId == id }
        schedulesFlow.value = schedules.values.toList()
        overridesFlow.value = overrides.values.toList()
    }

    override suspend fun setScheduleEnabled(id: Long, enabled: Boolean) {
        val existing = schedules[id] ?: return
        schedules[id] = existing.copy(enabled = enabled)
        schedulesFlow.value = schedules.values.toList()
    }

    override suspend fun recordOccurrenceOverride(override: OccurrenceOverride): Long {
        val id = if (override.id == 0L) overrideIdGen.getAndIncrement() else override.id
        val stored = override.copy(id = id)
        overrides[id] = stored
        overridesFlow.value = overrides.values.toList()
        return id
    }

    override suspend fun getOverridesForSchedule(scheduleId: Long): List<OccurrenceOverride> =
        overrides.values.filter { it.scheduleId == scheduleId }

    override suspend fun getActiveOverrides(now: Instant): List<OccurrenceOverride> =
        overrides.values.filter { it.occurrenceEnd.isAfter(now) }

    override fun getActiveOverridesFlow(now: Instant): Flow<List<OccurrenceOverride>> =
        overridesFlow.map { list -> list.filter { it.occurrenceEnd.isAfter(now) } }

    override suspend fun pruneExpiredOverrides(now: Instant): Int {
        val count = overrides.entries.count { !it.value.occurrenceEnd.isAfter(now) }
        overrides.entries.removeIf { !it.value.occurrenceEnd.isAfter(now) }
        overridesFlow.value = overrides.values.toList()
        return count
    }

    override suspend fun deleteOverridesForSchedule(scheduleId: Long): Int {
        val count = overrides.entries.count { it.value.scheduleId == scheduleId }
        overrides.entries.removeIf { it.value.scheduleId == scheduleId }
        overridesFlow.value = overrides.values.toList()
        return count
    }

    override suspend fun deleteOverride(scheduleId: Long, occurrenceStart: Instant): Int {
        val count = overrides.entries.count { it.value.scheduleId == scheduleId && it.value.occurrenceStart == occurrenceStart }
        overrides.entries.removeIf { it.value.scheduleId == scheduleId && it.value.occurrenceStart == occurrenceStart }
        overridesFlow.value = overrides.values.toList()
        return count
    }
}

class FakeAlarmScheduler : AlarmScheduler {
    var canScheduleExact: Boolean = true
    val scheduledEvents = mutableListOf<BoundaryEvent>()

    override fun scheduleBoundaryEvent(event: BoundaryEvent): Boolean {
        scheduledEvents.add(event)
        return true
    }

    override fun cancelBoundaryEvent(scheduleId: Long, type: BoundaryType, occurrenceStart: Instant) {
        scheduledEvents.removeAll { it.scheduleId == scheduleId && it.type == type && it.occurrenceStart == occurrenceStart }
    }

    override fun cancelAllForSchedule(scheduleId: Long) {
        scheduledEvents.removeAll { it.scheduleId == scheduleId }
    }

    override fun cancelAll() {
        scheduledEvents.clear()
    }

    override fun canScheduleExactAlarms(): Boolean = canScheduleExact
}

class FakeSoundController : SoundController {
    var canControl: Boolean = true
    private val _modeFlow = MutableStateFlow(SoundMode.RING)
    var simulatedMode: SoundMode
        get() = _modeFlow.value
        set(value) {
            _modeFlow.value = value
        }
    var setSilentCalls = 0
    var setRingCalls = 0

    override fun getCurrentMode(): SoundMode = _modeFlow.value

    override fun observeCurrentMode(): Flow<SoundMode> = _modeFlow.asStateFlow()

    override fun setSilent(): Boolean {
        if (!canControl) return false
        _modeFlow.value = SoundMode.SILENT
        setSilentCalls++
        return true
    }

    override fun setRing(): Boolean {
        if (!canControl) return false
        _modeFlow.value = SoundMode.RING
        setRingCalls++
        return true
    }

    override fun canControlSound(): Boolean = canControl
}
