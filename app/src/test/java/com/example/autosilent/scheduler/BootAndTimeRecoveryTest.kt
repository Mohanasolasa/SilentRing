package com.example.autosilent.scheduler

import com.example.autosilent.data.local.dao.OccurrenceOverrideDao
import com.example.autosilent.data.local.dao.ScheduleDao
import com.example.autosilent.data.local.entity.OccurrenceOverrideEntity
import com.example.autosilent.data.local.entity.ScheduleEntity
import com.example.autosilent.data.repository.ScheduleRepositoryImpl
import com.example.autosilent.domain.engine.ScheduleEngine
import com.example.autosilent.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.*
import java.util.concurrent.atomic.AtomicLong

class BootAndTimeRecoveryTest {

    private val zoneUtc = ZoneId.of("UTC")
    private val zoneKolkata = ZoneId.of("Asia/Kolkata")
    private val zoneNewYork = ZoneId.of("America/New_York")
    private val baseDate = LocalDate.of(2026, 10, 12) // Monday

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

    /**
     * Requirement 2 Scenario A: Phone boots before a schedule (09:00 for 10:00-11:00 schedule).
     * Expected: RING, next alarm: 10:00 START.
     */
    @Test
    fun testBootBeforeSchedule_SetsRingAndSchedulesStart() = runBlocking {
        val schedule = Schedule(
            name = "Work",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        val bootInstant = baseDate.atTime(9, 0).atZone(zoneUtc).toInstant()
        val evaluation = reconciler.reconcile(bootInstant, zoneUtc)

        assertEquals(SoundMode.RING, evaluation.desiredSoundMode)
        assertEquals(listOf(SoundMode.RING), recordedSoundModes)
        assertNotNull(evaluation.nextBoundaryEvent)
        assertEquals(id, evaluation.nextBoundaryEvent?.scheduleId)
        assertEquals(BoundaryType.START, evaluation.nextBoundaryEvent?.type)
        assertEquals(baseDate.atTime(10, 0).atZone(zoneUtc).toInstant(), evaluation.nextBoundaryEvent?.triggerInstant)
    }

    /**
     * Requirement 2 Scenario B: Phone boots during an active schedule (10:30 for 10:00-11:00 schedule).
     * Phone was powered off from 09:00-10:30.
     * Expected: SILENT immediately, next alarm: 11:00 END. Must NOT wait for 10:00 START.
     */
    @Test
    fun testBootDuringActiveSchedule_SetsSilentAndSchedulesEnd() = runBlocking {
        val schedule = Schedule(
            name = "Lecture",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        // Boot at 10:30 (middle of occurrence)
        val bootInstant = baseDate.atTime(10, 30).atZone(zoneUtc).toInstant()
        val evaluation = reconciler.reconcile(bootInstant, zoneUtc)

        assertEquals("Boot during active schedule must immediately evaluate to SILENT", SoundMode.SILENT, evaluation.desiredSoundMode)
        assertEquals(listOf(SoundMode.SILENT), recordedSoundModes)
        assertEquals(1, evaluation.activeOccurrences.size)
        assertEquals(id, evaluation.activeOccurrences[0].scheduleId)

        // Next boundary must be 11:00 END
        assertNotNull(evaluation.nextBoundaryEvent)
        assertEquals(id, evaluation.nextBoundaryEvent?.scheduleId)
        assertEquals(BoundaryType.END, evaluation.nextBoundaryEvent?.type)
        assertEquals(baseDate.atTime(11, 0).atZone(zoneUtc).toInstant(), evaluation.nextBoundaryEvent?.triggerInstant)
    }

    /**
     * Requirement 2 Scenario C: Phone boots after a schedule (12:00 for 10:00-11:00 daily schedule).
     * Expected: RING, next valid occurrence scheduled (tomorrow 10:00 START).
     */
    @Test
    fun testBootAfterSchedule_SetsRingAndSchedulesNextOccurrence() = runBlocking {
        val schedule = Schedule(
            name = "Study",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        val bootInstant = baseDate.atTime(12, 0).atZone(zoneUtc).toInstant()
        val evaluation = reconciler.reconcile(bootInstant, zoneUtc)

        assertEquals(SoundMode.RING, evaluation.desiredSoundMode)
        assertTrue(evaluation.activeOccurrences.isEmpty())
        assertNotNull(evaluation.nextBoundaryEvent)
        assertEquals(id, evaluation.nextBoundaryEvent?.scheduleId)
        assertEquals(BoundaryType.START, evaluation.nextBoundaryEvent?.type)
        assertEquals(baseDate.plusDays(1).atTime(10, 0).atZone(zoneUtc).toInstant(), evaluation.nextBoundaryEvent?.triggerInstant)
    }

    /**
     * Requirement 2 Scenario D: Boot during overlapping schedules.
     * Study: 10:00-12:00, Meeting: 11:00-13:00. Phone boots at 11:30.
     * Expected at 11:30: SILENT, next boundary 12:00 END (Study).
     * At 12:00: Meeting remains active -> STILL SILENT, next boundary 13:00 END.
     * At 13:00: RING.
     */
    @Test
    fun testBootDuringOverlappingSchedules_ResolvesCorrectly() = runBlocking {
        val study = Schedule(
            name = "Study",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(12, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val meeting = Schedule(
            name = "Meeting",
            startTime = LocalTime.of(11, 0),
            endTime = LocalTime.of(13, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val studyId = repository.insertSchedule(study)
        val meetingId = repository.insertSchedule(meeting)

        // 1. Boot at 11:30
        val bootInstant = baseDate.atTime(11, 30).atZone(zoneUtc).toInstant()
        val eval1130 = reconciler.reconcile(bootInstant, zoneUtc)

        assertEquals(SoundMode.SILENT, eval1130.desiredSoundMode)
        assertEquals(2, eval1130.activeOccurrences.size)
        // Next boundary is Study END at 12:00
        assertEquals(studyId, eval1130.nextBoundaryEvent?.scheduleId)
        assertEquals(BoundaryType.END, eval1130.nextBoundaryEvent?.type)
        assertEquals(baseDate.atTime(12, 0).atZone(zoneUtc).toInstant(), eval1130.nextBoundaryEvent?.triggerInstant)

        // 2. Alarm arrives at 12:00 (Study ends)
        val eval1200 = reconciler.reconcile(baseDate.atTime(12, 0).atZone(zoneUtc).toInstant(), zoneUtc, eval1130.nextBoundaryEvent)
        assertEquals("At 12:00 Study ended but Meeting is active, must remain SILENT", SoundMode.SILENT, eval1200.desiredSoundMode)
        assertEquals(1, eval1200.activeOccurrences.size)
        assertEquals(meetingId, eval1200.activeOccurrences[0].scheduleId)
        assertEquals(meetingId, eval1200.nextBoundaryEvent?.scheduleId)
        assertEquals(BoundaryType.END, eval1200.nextBoundaryEvent?.type)
        assertEquals(baseDate.atTime(13, 0).atZone(zoneUtc).toInstant(), eval1200.nextBoundaryEvent?.triggerInstant)

        // 3. Alarm arrives at 13:00 (Meeting ends)
        val eval1300 = reconciler.reconcile(baseDate.atTime(13, 0).atZone(zoneUtc).toInstant(), zoneUtc, eval1200.nextBoundaryEvent)
        assertEquals(SoundMode.RING, eval1300.desiredSoundMode)
        assertTrue(eval1300.activeOccurrences.isEmpty())
    }

    /**
     * Requirement 2 Scenario E: Boot during manually overridden occurrence.
     * Study 10:00-12:00. Overridden to RING at 10:15. Phone boots at 10:30.
     * Expected: Reboot preserves occurrence override -> RING.
     */
    @Test
    fun testBootWithManualOverride_PreservesOccurrenceOverride() = runBlocking {
        val schedule = Schedule(
            name = "Study Override",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(12, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        val occStart = baseDate.atTime(10, 0).atZone(zoneUtc).toInstant()
        val occEnd = baseDate.atTime(12, 0).atZone(zoneUtc).toInstant()

        // Override recorded in DB before reboot
        val override = OccurrenceOverride(
            scheduleId = id,
            occurrenceStart = occStart,
            occurrenceEnd = occEnd,
            createdAt = baseDate.atTime(10, 15).atZone(zoneUtc).toInstant()
        )
        repository.recordOccurrenceOverride(override)

        // Boot at 10:30
        val bootInstant = baseDate.atTime(10, 30).atZone(zoneUtc).toInstant()
        val evaluation = reconciler.reconcile(bootInstant, zoneUtc)

        assertEquals("Overridden occurrence must evaluate to RING even across reboot", SoundMode.RING, evaluation.desiredSoundMode)
        assertEquals(1, evaluation.activeOccurrences.size)
        assertEquals(1, evaluation.overriddenOccurrences.size)
        assertTrue(evaluation.effectiveOccurrences.isEmpty())
    }

    /**
     * Requirement 3: Missed START alarm recovery.
     * Schedule 10:00-11:00. 10:00 START was missed. Reconciler runs at 10:30.
     * Expected: SILENT, next 11:00 END. State is always derived from current Instant.
     */
    @Test
    fun testMissedStartAlarm_RecoversStateAtCurrentInstant() = runBlocking {
        val schedule = Schedule(
            name = "Missed Start",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        // Reconcile executes at 10:30 with no prior START execution
        val eval = reconciler.reconcile(baseDate.atTime(10, 30).atZone(zoneUtc).toInstant(), zoneUtc)

        assertEquals(SoundMode.SILENT, eval.desiredSoundMode)
        assertEquals(id, eval.nextBoundaryEvent?.scheduleId)
        assertEquals(BoundaryType.END, eval.nextBoundaryEvent?.type)
        assertEquals(baseDate.atTime(11, 0).atZone(zoneUtc).toInstant(), eval.nextBoundaryEvent?.triggerInstant)
    }

    /**
     * Requirement 3: Missed END alarm recovery.
     * Schedule 10:00-11:00. 11:00 END was missed. Reconciler runs at 11:15.
     * Expected: RING, next boundary scheduled.
     */
    @Test
    fun testMissedEndAlarm_RecoversStateAtCurrentInstant() = runBlocking {
        val schedule = Schedule(
            name = "Missed End",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        val eval = reconciler.reconcile(baseDate.atTime(11, 15).atZone(zoneUtc).toInstant(), zoneUtc)

        assertEquals(SoundMode.RING, eval.desiredSoundMode)
        assertEquals(id, eval.nextBoundaryEvent?.scheduleId)
        assertEquals(BoundaryType.START, eval.nextBoundaryEvent?.type)
        assertEquals(baseDate.plusDays(1).atTime(10, 0).atZone(zoneUtc).toInstant(), eval.nextBoundaryEvent?.triggerInstant)
    }

    /**
     * Requirement 5: Manual clock changes (forward and backward).
     * Schedule: 10:00-12:00.
     * 09:00 -> RING
     * move to 10:30 -> SILENT
     * move to 12:30 -> RING
     * move backwards to 09:30 -> RING
     * move forward to 10:30 -> SILENT
     * State is stateless and purely derived from instantaneous inputs.
     */
    @Test
    fun testManualClockChange_ForwardAndBackward() = runBlocking {
        val schedule = Schedule(
            name = "Clock Jump",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(12, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        repository.insertSchedule(schedule)

        // 1. 09:00
        val eval0900 = reconciler.reconcile(baseDate.atTime(9, 0).atZone(zoneUtc).toInstant(), zoneUtc)
        assertEquals(SoundMode.RING, eval0900.desiredSoundMode)

        // 2. Clock moves to 10:30
        val eval1030 = reconciler.reconcile(baseDate.atTime(10, 30).atZone(zoneUtc).toInstant(), zoneUtc)
        assertEquals(SoundMode.SILENT, eval1030.desiredSoundMode)

        // 3. Clock moves to 12:30
        val eval1230 = reconciler.reconcile(baseDate.atTime(12, 30).atZone(zoneUtc).toInstant(), zoneUtc)
        assertEquals(SoundMode.RING, eval1230.desiredSoundMode)

        // 4. Clock moves backward to 09:30
        val eval0930 = reconciler.reconcile(baseDate.atTime(9, 30).atZone(zoneUtc).toInstant(), zoneUtc)
        assertEquals(SoundMode.RING, eval0930.desiredSoundMode)

        // 5. Clock moves to 10:30
        val eval1030Again = reconciler.reconcile(baseDate.atTime(10, 30).atZone(zoneUtc).toInstant(), zoneUtc)
        assertEquals(SoundMode.SILENT, eval1030Again.desiredSoundMode)
    }

    /**
     * Requirement 6: Date changes and midnight crossing (23:59 -> 00:00).
     * Overnight schedule: Monday 22:00 -> Tuesday 06:00.
     * At Tuesday 01:00: SILENT.
     * At Tuesday 06:00: RING.
     */
    @Test
    fun testDateChange_MidnightCrossing() = runBlocking {
        val overnight = Schedule(
            name = "Night Shift",
            startTime = LocalTime.of(22, 0),
            endTime = LocalTime.of(6, 0),
            recurrenceType = RecurrenceType.WEEKDAYS,
            daysOfWeek = setOf(DayOfWeek.MONDAY), // Initiates Monday 22:00
            startDate = baseDate // Monday Oct 12
        )
        repository.insertSchedule(overnight)

        // 1. Tuesday 01:00 (crossing midnight)
        val tuesday0100 = baseDate.plusDays(1).atTime(1, 0).atZone(zoneUtc).toInstant()
        val eval0100 = reconciler.reconcile(tuesday0100, zoneUtc)
        assertEquals("At Tuesday 01:00 overnight schedule from Monday must be active", SoundMode.SILENT, eval0100.desiredSoundMode)
        assertEquals(1, eval0100.activeOccurrences.size)

        // 2. Tuesday 06:00 (end boundary)
        val tuesday0600 = baseDate.plusDays(1).atTime(6, 0).atZone(zoneUtc).toInstant()
        val eval0600 = reconciler.reconcile(tuesday0600, zoneUtc)
        assertEquals("At Tuesday 06:00 overnight schedule has ended", SoundMode.RING, eval0600.desiredSoundMode)
    }

    /**
     * Requirement 7: Timezone changes.
     * Recurring schedule: 09:00-10:00 local time daily.
     * In Asia/Kolkata: 09:00 IST.
     * User travels to America/New_York: Schedule definition is STILL 09:00-10:00 local time!
     * Obsolete alarm cancelled, next boundary in America/New_York scheduled.
     */
    @Test
    fun testTimezoneChange_LocalTimePreservedAndBoundariesAdjusted() = runBlocking {
        val schedule = Schedule(
            name = "Morning Standup",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(10, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        // Initial evaluation in Kolkata at 08:00 IST
        val ist0800 = baseDate.atTime(8, 0).atZone(zoneKolkata).toInstant()
        val evalKolkata = reconciler.reconcile(ist0800, zoneKolkata)

        assertEquals(SoundMode.RING, evalKolkata.desiredSoundMode)
        val expectedTriggerIst = baseDate.atTime(9, 0).atZone(zoneKolkata).toInstant()
        assertEquals(expectedTriggerIst, evalKolkata.nextBoundaryEvent?.triggerInstant)

        // Timezone changed to New York. Evaluate at 08:00 EDT on same calendar day
        val edt0800 = baseDate.atTime(8, 0).atZone(zoneNewYork).toInstant()
        val evalNewYork = reconciler.reconcile(edt0800, zoneNewYork)

        assertEquals(SoundMode.RING, evalNewYork.desiredSoundMode)
        val expectedTriggerEdt = baseDate.atTime(9, 0).atZone(zoneNewYork).toInstant()
        assertEquals(expectedTriggerEdt, evalNewYork.nextBoundaryEvent?.triggerInstant)
        assertNotEquals("Trigger instant in UTC must differ between zones", expectedTriggerIst, expectedTriggerEdt)

        // If stale IST alarm arrives in New York, verify isEventValid returns false
        val staleEvent = evalKolkata.nextBoundaryEvent!!
        val isStaleValid = ScheduleEngine.isEventValid(staleEvent, repository.getScheduleById(id), zoneNewYork)
        assertFalse("Stale alarm from previous timezone must be rejected", isStaleValid)
    }

    /**
     * Requirement 8: Overnight schedule with timezone change.
     * 22:00 -> 06:00 overnight schedule before and after timezone shift.
     */
    @Test
    fun testOvernightSchedule_WithTimezoneChange() = runBlocking {
        val overnight = Schedule(
            name = "Overnight Sleep",
            startTime = LocalTime.of(22, 0),
            endTime = LocalTime.of(6, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        repository.insertSchedule(overnight)

        // 23:00 local time in New York -> SILENT
        val ny2300 = baseDate.atTime(23, 0).atZone(zoneNewYork).toInstant()
        val evalNy = reconciler.reconcile(ny2300, zoneNewYork)
        assertEquals(SoundMode.SILENT, evalNy.desiredSoundMode)
        assertEquals(baseDate.plusDays(1).atTime(6, 0).atZone(zoneNewYork).toInstant(), evalNy.nextBoundaryEvent?.triggerInstant)

        // Switch to Kolkata: at 23:00 IST -> SILENT
        val kolkata2300 = baseDate.atTime(23, 0).atZone(zoneKolkata).toInstant()
        val evalKolkata = reconciler.reconcile(kolkata2300, zoneKolkata)
        assertEquals(SoundMode.SILENT, evalKolkata.desiredSoundMode)
        assertEquals(baseDate.plusDays(1).atTime(6, 0).atZone(zoneKolkata).toInstant(), evalKolkata.nextBoundaryEvent?.triggerInstant)
    }

    /**
     * Requirement 9: Reconciliation idempotency.
     * Multiple calls to reconcile() at identical Instant produce identical results and 1 effective alarm.
     */
    @Test
    fun testReconciliationIdempotency() = runBlocking {
        val schedule = Schedule(
            name = "Focus",
            startTime = LocalTime.of(14, 0),
            endTime = LocalTime.of(16, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        repository.insertSchedule(schedule)

        val instant = baseDate.atTime(13, 0).atZone(zoneUtc).toInstant()
        val eval1 = reconciler.reconcile(instant, zoneUtc)
        val eval2 = reconciler.reconcile(instant, zoneUtc)
        val eval3 = reconciler.reconcile(instant, zoneUtc)

        assertEquals(eval1.desiredSoundMode, eval2.desiredSoundMode)
        assertEquals(eval2.desiredSoundMode, eval3.desiredSoundMode)
        assertEquals(eval1.nextBoundaryEvent?.triggerInstant, eval3.nextBoundaryEvent?.triggerInstant)
    }

    /**
     * Requirement 9: Concurrent reconciliation serialized by Mutex.
     */
    @Test
    fun testConcurrentReconciliation_SerializedByMutex() = runBlocking {
        val schedule = Schedule(
            name = "Concurrent",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        repository.insertSchedule(schedule)

        val instant = baseDate.atTime(9, 30).atZone(zoneUtc).toInstant()
        val deferreds = (1..10).map {
            async(Dispatchers.Default) {
                reconciler.reconcile(instant, zoneUtc)
            }
        }

        val results = deferreds.awaitAll()
        assertEquals(10, results.size)
        results.forEach { eval ->
            assertEquals(SoundMode.RING, eval.desiredSoundMode)
            assertEquals(baseDate.atTime(10, 0).atZone(zoneUtc).toInstant(), eval.nextBoundaryEvent?.triggerInstant)
        }
    }

    /**
     * Requirement 17: Boot with revoked exact alarm permission.
     * When canScheduleExactAlarms() is false, reconciler still computes state and applies it without crashing.
     */
    @Test
    fun testBootWithRevokedExactAlarmPermission_DoesNotCrash() = runBlocking {
        fakeAlarmScheduler.exactAlarmPermitted = false

        val schedule = Schedule(
            name = "Permission Revoked Test",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        repository.insertSchedule(schedule)

        // Boot during active schedule at 10:15
        val bootInstant = baseDate.atTime(10, 15).atZone(zoneUtc).toInstant()
        val evaluation = reconciler.reconcile(bootInstant, zoneUtc)

        assertEquals("State must still be evaluated to SILENT even if exact alarm cannot be scheduled", SoundMode.SILENT, evaluation.desiredSoundMode)
        assertEquals(1, evaluation.activeOccurrences.size)
        // Alarm scheduler rejected boundary gracefully
        assertEquals(0, fakeAlarmScheduler.scheduledEvents.size)
    }

    // --- Fakes ---

    private class FakeAlarmScheduler : AlarmScheduler {
        var exactAlarmPermitted: Boolean = true
        var lastScheduledEvent: BoundaryEvent? = null
        val scheduledEvents = mutableListOf<BoundaryEvent>()

        override fun canScheduleExactAlarms(): Boolean = exactAlarmPermitted

        override fun scheduleBoundaryEvent(event: BoundaryEvent): Boolean {
            if (!canScheduleExactAlarms()) return false
            lastScheduledEvent?.let { prev ->
                if (prev != event) {
                    cancelBoundaryEvent(prev.scheduleId, prev.type, prev.occurrenceStart)
                }
            }
            lastScheduledEvent = event
            scheduledEvents.add(event)
            return true
        }

        override fun cancelBoundaryEvent(scheduleId: Long, type: BoundaryType, occurrenceStart: Instant) {
            scheduledEvents.removeAll { it.scheduleId == scheduleId && it.type == type && it.occurrenceStart == occurrenceStart }
            if (lastScheduledEvent?.scheduleId == scheduleId && lastScheduledEvent?.type == type) {
                lastScheduledEvent = null
            }
        }

        override fun cancelAllForSchedule(scheduleId: Long) {
            scheduledEvents.removeAll { it.scheduleId == scheduleId }
        }

        override fun cancelAll() {
            scheduledEvents.clear()
            lastScheduledEvent = null
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
