package com.example.autosilent.scheduler

import com.example.autosilent.data.local.dao.OccurrenceOverrideDao
import com.example.autosilent.data.local.dao.ScheduleDao
import com.example.autosilent.data.local.entity.OccurrenceOverrideEntity
import com.example.autosilent.data.local.entity.ScheduleEntity
import com.example.autosilent.data.repository.ScheduleRepositoryImpl
import com.example.autosilent.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

class ScheduleReconcilerTest {

    private val zoneUtc = ZoneId.of("UTC")
    private val baseDate = LocalDate.of(2026, 10, 12)

    private lateinit var fakeScheduleDao: FakeScheduleDao
    private lateinit var fakeOverrideDao: FakeOverrideDao
    private lateinit var repository: ScheduleRepositoryImpl
    private lateinit var fakeAlarmScheduler: FakeAlarmScheduler
    private lateinit var reconciler: ScheduleReconciler

    private val recordedSoundModes = mutableListOf<SoundMode>()

    @Before
    fun setUp() {
        fakeScheduleDao = FakeScheduleDao()
        fakeOverrideDao = FakeOverrideDao()
        repository = ScheduleRepositoryImpl(fakeScheduleDao, fakeOverrideDao)
        fakeAlarmScheduler = FakeAlarmScheduler()
        recordedSoundModes.clear()

        reconciler = ScheduleReconciler(
            repository = repository,
            alarmScheduler = fakeAlarmScheduler,
            onSoundModeChanged = { mode -> recordedSoundModes.add(mode) }
        )
    }

    @Test
    fun reconcile_singleSchedule_activatesSilentAndSchedulesEnd() = runBlocking {
        val schedule = Schedule(
            name = "Focus",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        // At 09:00: schedule starts
        val t0900 = baseDate.atTime(9, 0).atZone(zoneUtc).toInstant()
        val startEvent = BoundaryEvent(
            scheduleId = id,
            scheduleName = "Focus",
            type = BoundaryType.START,
            triggerInstant = t0900,
            occurrenceStart = t0900,
            occurrenceEnd = baseDate.atTime(11, 0).atZone(zoneUtc).toInstant()
        )

        val evaluation = reconciler.reconcile(t0900, zoneUtc, triggeredByEvent = startEvent)

        assertEquals(SoundMode.SILENT, evaluation.desiredSoundMode)
        assertEquals(listOf(SoundMode.SILENT), recordedSoundModes)
        assertEquals(1, evaluation.activeOccurrences.size)

        // Next boundary scheduled must be END at 11:00
        assertNotNull(fakeAlarmScheduler.lastScheduledEvent)
        assertEquals(BoundaryType.END, fakeAlarmScheduler.lastScheduledEvent?.type)
        assertEquals(baseDate.atTime(11, 0).atZone(zoneUtc).toInstant(), fakeAlarmScheduler.lastScheduledEvent?.triggerInstant)
    }

    @Test
    fun reconcile_overlap_studyEndsWhileMeetingActive_doesNotBlindlySetRing() = runBlocking {
        // Study: 07:00 - 10:00
        val studyId = repository.insertSchedule(
            Schedule(name = "Study", startTime = LocalTime.of(7, 0), endTime = LocalTime.of(10, 0), recurrenceType = RecurrenceType.DAILY, startDate = baseDate)
        )
        // Meeting: 09:00 - 11:00
        val meetingId = repository.insertSchedule(
            Schedule(name = "Meeting", startTime = LocalTime.of(9, 0), endTime = LocalTime.of(11, 0), recurrenceType = RecurrenceType.DAILY, startDate = baseDate)
        )

        // Study END boundary arrives at 10:00
        val t1000 = baseDate.atTime(10, 0).atZone(zoneUtc).toInstant()
        val studyEndEvent = BoundaryEvent(
            scheduleId = studyId,
            scheduleName = "Study",
            type = BoundaryType.END,
            triggerInstant = t1000,
            occurrenceStart = baseDate.atTime(7, 0).atZone(zoneUtc).toInstant(),
            occurrenceEnd = t1000
        )

        val eval1000 = reconciler.reconcile(t1000, zoneUtc, triggeredByEvent = studyEndEvent)

        // CRITICAL CHECK: Global state MUST REMAIN SILENT because Meeting is active!
        assertEquals(SoundMode.SILENT, eval1000.desiredSoundMode)
        assertEquals(listOf(SoundMode.SILENT), recordedSoundModes)
        assertEquals(1, eval1000.activeOccurrences.size)
        assertEquals(meetingId, eval1000.activeOccurrences[0].scheduleId)

        // Next scheduled boundary is Meeting END at 11:00
        assertEquals(BoundaryType.END, fakeAlarmScheduler.lastScheduledEvent?.type)
        assertEquals(baseDate.atTime(11, 0).atZone(zoneUtc).toInstant(), fakeAlarmScheduler.lastScheduledEvent?.triggerInstant)

        // Now at 11:00 Meeting ends
        val t1100 = baseDate.atTime(11, 0).atZone(zoneUtc).toInstant()
        val meetingEndEvent = BoundaryEvent(
            scheduleId = meetingId,
            scheduleName = "Meeting",
            type = BoundaryType.END,
            triggerInstant = t1100,
            occurrenceStart = baseDate.atTime(9, 0).atZone(zoneUtc).toInstant(),
            occurrenceEnd = t1100
        )
        val eval1100 = reconciler.reconcile(t1100, zoneUtc, triggeredByEvent = meetingEndEvent)

        // Now both ended -> RING
        assertEquals(SoundMode.RING, eval1100.desiredSoundMode)
        assertEquals(listOf(SoundMode.SILENT, SoundMode.RING), recordedSoundModes)
        assertTrue(eval1100.activeOccurrences.isEmpty())
    }

    @Test
    fun reconcile_staleAlarm_deletedSchedule_safelyHandledWithoutModifyingState() = runBlocking {
        // Trigger arrives for schedule 999 which does NOT exist in database
        val t0900 = baseDate.atTime(9, 0).atZone(zoneUtc).toInstant()
        val ghostEvent = BoundaryEvent(
            scheduleId = 999L,
            scheduleName = "Deleted Schedule",
            type = BoundaryType.START,
            triggerInstant = t0900,
            occurrenceStart = t0900,
            occurrenceEnd = baseDate.atTime(10, 0).atZone(zoneUtc).toInstant()
        )

        val evaluation = reconciler.reconcile(t0900, zoneUtc, triggeredByEvent = ghostEvent)

        // Database is empty, so result is cleanly RING
        assertEquals(SoundMode.RING, evaluation.desiredSoundMode)
        assertTrue(evaluation.activeOccurrences.isEmpty())
    }

    @Test
    fun reconcile_manualOverride_respectedAcrossOverlaps() = runBlocking {
        // Study: 07:00 - 10:00
        val studyId = repository.insertSchedule(
            Schedule(name = "Study", startTime = LocalTime.of(7, 0), endTime = LocalTime.of(10, 0), recurrenceType = RecurrenceType.DAILY, startDate = baseDate)
        )
        // Meeting: 09:00 - 11:00
        val meetingId = repository.insertSchedule(
            Schedule(name = "Meeting", startTime = LocalTime.of(9, 0), endTime = LocalTime.of(11, 0), recurrenceType = RecurrenceType.DAILY, startDate = baseDate)
        )

        val studyStart = baseDate.atTime(7, 0).atZone(zoneUtc).toInstant()
        val studyEnd = baseDate.atTime(10, 0).atZone(zoneUtc).toInstant()

        // User overrides Study occurrence at 08:00
        repository.recordOccurrenceOverride(
            OccurrenceOverride(scheduleId = studyId, occurrenceStart = studyStart, occurrenceEnd = studyEnd)
        )

        // At 08:30 -> Evaluation must be RING (Study overridden, Meeting not started)
        val t0830 = baseDate.atTime(8, 30).atZone(zoneUtc).toInstant()
        val eval0830 = reconciler.reconcile(t0830, zoneUtc)
        assertEquals(SoundMode.RING, eval0830.desiredSoundMode)

        // At 09:00 -> Meeting starts (not overridden) -> Global state must switch to SILENT!
        val t0900 = baseDate.atTime(9, 0).atZone(zoneUtc).toInstant()
        val meetingStartEvent = BoundaryEvent(
            scheduleId = meetingId,
            scheduleName = "Meeting",
            type = BoundaryType.START,
            triggerInstant = t0900,
            occurrenceStart = t0900,
            occurrenceEnd = baseDate.atTime(11, 0).atZone(zoneUtc).toInstant()
        )
        val eval0900 = reconciler.reconcile(t0900, zoneUtc, triggeredByEvent = meetingStartEvent)
        assertEquals(SoundMode.SILENT, eval0900.desiredSoundMode)
        assertEquals(1, eval0900.effectiveOccurrences.size)
        assertEquals("Meeting", eval0900.effectiveOccurrences[0].scheduleName)
    }

    // --- Fakes ---

    private class FakeAlarmScheduler : AlarmScheduler {
        var lastScheduledEvent: BoundaryEvent? = null
        val scheduledEvents = mutableListOf<BoundaryEvent>()

        override fun canScheduleExactAlarms(): Boolean = true

        override fun scheduleBoundaryEvent(event: BoundaryEvent): Boolean {
            lastScheduledEvent = event
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
    }

    private class FakeScheduleDao : ScheduleDao {
        private val idGen = AtomicLong(1)
        private val schedules = LinkedHashMap<Long, ScheduleEntity>()
        private val flow = MutableStateFlow<List<ScheduleEntity>>(emptyList())

        override suspend fun insert(schedule: ScheduleEntity): Long {
            val id = if (schedule.id == 0L) idGen.getAndIncrement() else schedule.id
            schedules[id] = schedule.copy(id = id)
            flow.value = schedules.values.toList()
            return id
        }

        override suspend fun update(schedule: ScheduleEntity) {
            schedules[schedule.id] = schedule
            flow.value = schedules.values.toList()
        }

        override suspend fun delete(schedule: ScheduleEntity) {
            schedules.remove(schedule.id)
            flow.value = schedules.values.toList()
        }

        override suspend fun deleteById(id: Long): Int {
            val removed = schedules.remove(id) != null
            flow.value = schedules.values.toList()
            return if (removed) 1 else 0
        }

        override suspend fun getScheduleById(id: Long): ScheduleEntity? = schedules[id]
        override fun getAllSchedulesFlow(): Flow<List<ScheduleEntity>> = flow
        override suspend fun getEnabledSchedules(): List<ScheduleEntity> =
            schedules.values.filter { it.enabled }.sortedBy { it.startTime }
        override fun getEnabledSchedulesFlow(): Flow<List<ScheduleEntity>> =
            flow.map { list -> list.filter { it.enabled } }

        override suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Instant) {
            val current = schedules[id] ?: return
            schedules[id] = current.copy(enabled = enabled, updatedAt = updatedAt)
            flow.value = schedules.values.toList()
        }
    }

    private class FakeOverrideDao : OccurrenceOverrideDao {
        private val idGen = AtomicLong(1)
        private val overrides = LinkedHashMap<Long, OccurrenceOverrideEntity>()
        private val flow = MutableStateFlow<List<OccurrenceOverrideEntity>>(emptyList())

        override suspend fun insert(override: OccurrenceOverrideEntity): Long {
            val id = if (override.id == 0L) idGen.getAndIncrement() else override.id
            overrides[id] = override.copy(id = id)
            flow.value = overrides.values.toList()
            return id
        }

        override suspend fun getAllOverrides(): List<OccurrenceOverrideEntity> = overrides.values.toList()
        override fun getAllOverridesFlow(): Flow<List<OccurrenceOverrideEntity>> = flow
        override suspend fun getOverridesForSchedule(scheduleId: Long): List<OccurrenceOverrideEntity> =
            overrides.values.filter { it.scheduleId == scheduleId }
        override suspend fun getActiveOverrides(now: Instant): List<OccurrenceOverrideEntity> =
            overrides.values.filter { it.occurrenceEnd.isAfter(now) }
        override fun getActiveOverridesFlow(now: Instant): Flow<List<OccurrenceOverrideEntity>> =
            flow.map { list -> list.filter { it.occurrenceEnd.isAfter(now) } }
        override suspend fun deleteExpiredOverrides(now: Instant): Int {
            val toRemove = overrides.filterValues { !it.occurrenceEnd.isAfter(now) }.keys
            toRemove.forEach { overrides.remove(it) }
            flow.value = overrides.values.toList()
            return toRemove.size
        }
        override suspend fun deleteByScheduleId(scheduleId: Long): Int {
            val toRemove = overrides.filterValues { it.scheduleId == scheduleId }.keys
            toRemove.forEach { overrides.remove(it) }
            flow.value = overrides.values.toList()
            return toRemove.size
        }
        override suspend fun deleteByScheduleAndOccurrence(scheduleId: Long, occurrenceStart: Instant): Int {
            val toRemove = overrides.filterValues { it.scheduleId == scheduleId && it.occurrenceStart == occurrenceStart }.keys
            toRemove.forEach { overrides.remove(it) }
            flow.value = overrides.values.toList()
            return toRemove.size
        }
    }
}
