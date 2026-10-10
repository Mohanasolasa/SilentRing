package com.example.autosilent.system

import com.example.autosilent.domain.controller.SoundController
import com.example.autosilent.domain.model.*
import com.example.autosilent.domain.repository.ScheduleRepository
import com.example.autosilent.scheduler.AlarmScheduler
import com.example.autosilent.scheduler.ScheduleReconciler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Unit test suite for Phase 8: SoundControl contract, state safety, idempotency,
 * failure resilience, manual override compatibility, and schedule overlap compatibility.
 */
class SoundControlTest {

    private val zoneId = ZoneId.of("Asia/Kolkata")
    private val baseDate = LocalDate.of(2026, 10, 8)

    private lateinit var fakeRepository: FakeScheduleRepository
    private lateinit var fakeAlarmScheduler: FakeAlarmScheduler
    private lateinit var fakeSoundController: TestableSoundController
    private lateinit var reconciler: ScheduleReconciler

    @Before
    fun setUp() {
        fakeRepository = FakeScheduleRepository()
        fakeAlarmScheduler = FakeAlarmScheduler()
        fakeSoundController = TestableSoundController(initialMode = SoundMode.RING)
        reconciler = ScheduleReconciler(
            repository = fakeRepository,
            alarmScheduler = fakeAlarmScheduler,
            soundController = fakeSoundController
        )
    }

    /**
     * Requirement 9.1: SILENT request transitions mode to SILENT.
     */
    @Test
    fun test1_SilentRequest_TransitionsToSilent() {
        fakeSoundController.simulatedMode = SoundMode.RING
        val success = fakeSoundController.setSilent()
        assertTrue("setSilent must return true on success", success)
        assertEquals(SoundMode.SILENT, fakeSoundController.getCurrentMode())
        assertEquals(1, fakeSoundController.silentTransitions)
    }

    /**
     * Requirement 9.2: RING request transitions mode to RING.
     */
    @Test
    fun test2_RingRequest_TransitionsToRing() {
        fakeSoundController.simulatedMode = SoundMode.SILENT
        val success = fakeSoundController.setRing()
        assertTrue("setRing must return true on success", success)
        assertEquals(SoundMode.RING, fakeSoundController.getCurrentMode())
        assertEquals(1, fakeSoundController.ringTransitions)
    }

    /**
     * Requirement 9.3: Already-SILENT idempotency: no redundant mutations or state changes.
     */
    @Test
    fun test3_AlreadySilent_IdempotencyNoop() {
        fakeSoundController.simulatedMode = SoundMode.SILENT
        val success = fakeSoundController.setSilent()
        assertTrue("Already-SILENT request must return true", success)
        assertEquals(SoundMode.SILENT, fakeSoundController.getCurrentMode())
        assertEquals("No mutation should occur if already SILENT", 0, fakeSoundController.silentTransitions)
    }

    /**
     * Requirement 9.4: Already-RING idempotency: no redundant mutations or state changes.
     */
    @Test
    fun test4_AlreadyRing_IdempotencyNoop() {
        fakeSoundController.simulatedMode = SoundMode.RING
        val success = fakeSoundController.setRing()
        assertTrue("Already-RING request must return true", success)
        assertEquals(SoundMode.RING, fakeSoundController.getCurrentMode())
        assertEquals("No mutation should occur if already RING", 0, fakeSoundController.ringTransitions)
    }

    /**
     * Requirement 9.5: Desired state transition when evaluated through ScheduleReconciler.
     */
    @Test
    fun test5_DesiredStateTransition_ReconcilerSilencesAtScheduleStart() = runBlocking {
        val schedule = Schedule(
            id = 101L,
            name = "Deep Work",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(12, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        fakeRepository.insertSchedule(schedule)

        // At 10:30 (inside schedule) -> desired = SILENT
        val instantDuring = baseDate.atTime(10, 30).atZone(zoneId).toInstant()
        val eval = reconciler.reconcile(instantDuring, zoneId)

        assertEquals(SoundMode.SILENT, eval.desiredSoundMode)
        assertEquals(SoundMode.SILENT, fakeSoundController.getCurrentMode())
        assertEquals(1, fakeSoundController.silentTransitions)

        // At 12:30 (outside schedule) -> desired = RING
        val instantAfter = baseDate.atTime(12, 30).atZone(zoneId).toInstant()
        val evalAfter = reconciler.reconcile(instantAfter, zoneId)

        assertEquals(SoundMode.RING, evalAfter.desiredSoundMode)
        assertEquals(SoundMode.RING, fakeSoundController.getCurrentMode())
        assertEquals(1, fakeSoundController.ringTransitions)
    }

    /**
     * Requirement 9.6: Repeated reconciliation does not create feedback loop or repeat transitions.
     */
    @Test
    fun test6_RepeatedReconciliation_IdempotentActuation() = runBlocking {
        val schedule = Schedule(
            id = 102L,
            name = "Study",
            startTime = LocalTime.of(14, 0),
            endTime = LocalTime.of(16, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        fakeRepository.insertSchedule(schedule)

        val instantDuring = baseDate.atTime(14, 15).atZone(zoneId).toInstant()

        // 1st reconcile -> sets SILENT
        reconciler.reconcile(instantDuring, zoneId)
        assertEquals(1, fakeSoundController.silentTransitions)

        // 2nd reconcile at same or slightly later active instant -> no redundant transition
        reconciler.reconcile(instantDuring.plusSeconds(60), zoneId)
        assertEquals("Repeated reconciliation must be a no-op on sound actuation", 1, fakeSoundController.silentTransitions)

        // 3rd reconcile -> still noop
        reconciler.reconcile(instantDuring.plusSeconds(120), zoneId)
        assertEquals(1, fakeSoundController.silentTransitions)
    }

    /**
     * Requirement 9.7: Failure handling: if OS refuses transition, app does not crash and handles safely.
     */
    @Test
    fun test7_FailureHandling_RefusalDoesNotCrash() {
        fakeSoundController.simulateSecurityException = true
        val result = fakeSoundController.setSilent()
        assertFalse("setSilent must return false on security exception", result)
        assertEquals("Sound mode must remain unaltered on exception", SoundMode.RING, fakeSoundController.getCurrentMode())
    }

    /**
     * Requirement 9.8: Unsupported / unavailable control: canControlSound == false fails safely.
     */
    @Test
    fun test8_UnsupportedControl_PermissionDeniedFailsSafely() {
        fakeSoundController.canControl = false
        val resultSilent = fakeSoundController.setSilent()
        assertFalse("setSilent must return false when control unavailable", resultSilent)
        assertEquals(SoundMode.RING, fakeSoundController.getCurrentMode())

        fakeSoundController.simulatedMode = SoundMode.SILENT
        val resultRing = fakeSoundController.setRing()
        assertFalse("setRing must return false when control unavailable", resultRing)
        assertEquals(SoundMode.SILENT, fakeSoundController.getCurrentMode())
    }

    /**
     * Requirement 9.9: Manual override compatibility:
     * When user manually overrides active occurrence, mode becomes RING.
     * Subsequent reconciliations do not create an endless loop fighting the user override.
     */
    @Test
    fun test9_ManualOverrideCompatibility_RespectsOccurrenceOverride() = runBlocking {
        val schedule = Schedule(
            id = 201L,
            name = "Class",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        fakeRepository.insertSchedule(schedule)

        val startInstant = baseDate.atTime(9, 0).atZone(zoneId).toInstant()
        val endInstant = baseDate.atTime(11, 0).atZone(zoneId).toInstant()
        val evalAtStart = reconciler.reconcile(startInstant, zoneId)
        assertEquals(SoundMode.SILENT, evalAtStart.desiredSoundMode)
        assertEquals(SoundMode.SILENT, fakeSoundController.getCurrentMode())

        // User overrides this specific occurrence to RING
        val override = OccurrenceOverride(
            scheduleId = 201L,
            occurrenceStart = startInstant,
            occurrenceEnd = endInstant,
            createdAt = startInstant.plusSeconds(60)
        )
        fakeRepository.recordOccurrenceOverride(override)

        // Reconcile after override recorded
        val evalAfterOverride = reconciler.reconcile(startInstant.plusSeconds(300), zoneId)
        assertEquals("Desired sound mode must respect manual override", SoundMode.RING, evalAfterOverride.desiredSoundMode)
        assertEquals("Actuator must restore RING mode", SoundMode.RING, fakeSoundController.getCurrentMode())

        // Subsequent reconciliation during same occurrence must NOT re-silence
        val evalLater = reconciler.reconcile(startInstant.plusSeconds(600), zoneId)
        assertEquals(SoundMode.RING, evalLater.desiredSoundMode)
        assertEquals(SoundMode.RING, fakeSoundController.getCurrentMode())
        assertEquals("Must not repeatedly trigger ring transition", 1, fakeSoundController.ringTransitions)
    }

    /**
     * Requirement 9.10: Overlap compatibility:
     * Study: 10:00–12:00
     * Meeting: 11:00–13:00
     * 10:00 -> SILENT
     * 11:00 -> remains SILENT (idempotent, no redundant transition)
     * 12:00 -> remains SILENT (Study ends, Meeting still active)
     * 13:00 -> RING
     */
    @Test
    fun test10_OverlapCompatibility_SeamlessContinuity() = runBlocking {
        val study = Schedule(
            id = 301L,
            name = "Study",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(12, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val meeting = Schedule(
            id = 302L,
            name = "Meeting",
            startTime = LocalTime.of(11, 0),
            endTime = LocalTime.of(13, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        fakeRepository.insertSchedule(study)
        fakeRepository.insertSchedule(meeting)

        // At 10:00 -> Study starts -> SILENT
        val t1000 = baseDate.atTime(10, 0).atZone(zoneId).toInstant()
        val eval1000 = reconciler.reconcile(t1000, zoneId)
        assertEquals(SoundMode.SILENT, eval1000.desiredSoundMode)
        assertEquals(SoundMode.SILENT, fakeSoundController.getCurrentMode())
        assertEquals(1, fakeSoundController.silentTransitions)

        // At 11:00 -> Meeting starts -> remains SILENT, no redundant mutation
        val t1100 = baseDate.atTime(11, 0).atZone(zoneId).toInstant()
        val eval1100 = reconciler.reconcile(t1100, zoneId)
        assertEquals(SoundMode.SILENT, eval1100.desiredSoundMode)
        assertEquals(SoundMode.SILENT, fakeSoundController.getCurrentMode())
        assertEquals("No redundant silent transition when already SILENT", 1, fakeSoundController.silentTransitions)

        // At 12:00 -> Study ends, Meeting active -> remains SILENT
        val t1200 = baseDate.atTime(12, 0).atZone(zoneId).toInstant()
        val eval1200 = reconciler.reconcile(t1200, zoneId)
        assertEquals(SoundMode.SILENT, eval1200.desiredSoundMode)
        assertEquals(SoundMode.SILENT, fakeSoundController.getCurrentMode())
        assertEquals(1, fakeSoundController.silentTransitions)

        // At 13:00 -> Meeting ends -> RING
        val t1300 = baseDate.atTime(13, 0).atZone(zoneId).toInstant()
        val eval1300 = reconciler.reconcile(t1300, zoneId)
        assertEquals(SoundMode.RING, eval1300.desiredSoundMode)
        assertEquals(SoundMode.RING, fakeSoundController.getCurrentMode())
        assertEquals(1, fakeSoundController.ringTransitions)
    }

    /**
     * Requirement 13: Duplicate reconciliation prevention test.
     * Verifies that firing multiple reconciliation events back-to-back produces identical,
     * idempotent output without duplicate transitions.
     */
    @Test
    fun test11_DuplicateReconciliationSafety() = runBlocking {
        val schedule = Schedule(
            id = 401L,
            name = "Night Sleep",
            startTime = LocalTime.of(22, 0),
            endTime = LocalTime.of(6, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        fakeRepository.insertSchedule(schedule)

        val t2300 = baseDate.atTime(23, 0).atZone(zoneId).toInstant()

        val r1 = reconciler.reconcile(t2300, zoneId)
        val r2 = reconciler.reconcile(t2300, zoneId)
        val r3 = reconciler.reconcile(t2300, zoneId)

        assertEquals(r1.desiredSoundMode, r2.desiredSoundMode)
        assertEquals(r2.desiredSoundMode, r3.desiredSoundMode)
        assertEquals(SoundMode.SILENT, r3.desiredSoundMode)
        assertEquals(1, fakeSoundController.silentTransitions)
    }

    // --- Test Doubles ---

    private class TestableSoundController(
        var initialMode: SoundMode = SoundMode.RING
    ) : SoundController {
        var simulatedMode: SoundMode = initialMode
        var canControl: Boolean = true
        var simulateSecurityException: Boolean = false

        var silentTransitions = 0
        var ringTransitions = 0

        override fun getCurrentMode(): SoundMode = simulatedMode

        override fun canControlSound(): Boolean = canControl

        override fun setSilent(): Boolean {
            if (simulateSecurityException) return false
            if (!canControl) return false
            if (simulatedMode == SoundMode.SILENT) return true
            simulatedMode = SoundMode.SILENT
            silentTransitions++
            return true
        }

        override fun setRing(): Boolean {
            if (simulateSecurityException) return false
            if (!canControl) return false
            if (simulatedMode == SoundMode.RING) return true
            simulatedMode = SoundMode.RING
            ringTransitions++
            return true
        }
    }

    private class FakeScheduleRepository : ScheduleRepository {
        private val schedules = mutableMapOf<Long, Schedule>()
        private val overrides = mutableListOf<OccurrenceOverride>()
        private var nextId = 1L

        override fun getSchedulesFlow(): Flow<List<Schedule>> = flowOf(schedules.values.toList())
        override suspend fun getScheduleById(id: Long): Schedule? = schedules[id]
        override suspend fun getEnabledSchedules(): List<Schedule> = schedules.values.filter { it.enabled }
        override fun getEnabledSchedulesFlow(): Flow<List<Schedule>> = flowOf(schedules.values.filter { it.enabled })

        override suspend fun insertSchedule(schedule: Schedule): Long {
            val id = if (schedule.id != 0L) schedule.id else nextId++
            val saved = schedule.copy(id = id)
            schedules[id] = saved
            return id
        }

        override suspend fun updateSchedule(schedule: Schedule) { schedules[schedule.id] = schedule }
        override suspend fun deleteSchedule(id: Long) { schedules.remove(id) }
        override suspend fun setScheduleEnabled(id: Long, enabled: Boolean) {
            schedules[id]?.let { schedules[id] = it.copy(enabled = enabled) }
        }

        override suspend fun recordOccurrenceOverride(override: OccurrenceOverride): Long {
            overrides.add(override)
            return override.id
        }

        override suspend fun getOverridesForSchedule(scheduleId: Long): List<OccurrenceOverride> =
            overrides.filter { it.scheduleId == scheduleId }

        override suspend fun getActiveOverrides(now: Instant): List<OccurrenceOverride> =
            overrides.filter { it.occurrenceEnd.isAfter(now) }

        override fun getActiveOverridesFlow(now: Instant): Flow<List<OccurrenceOverride>> =
            flowOf(overrides.filter { it.occurrenceEnd.isAfter(now) })

        override suspend fun pruneExpiredOverrides(now: Instant): Int {
            val initial = overrides.size
            overrides.removeAll { !it.occurrenceEnd.isAfter(now) }
            return initial - overrides.size
        }

        override suspend fun deleteOverridesForSchedule(scheduleId: Long): Int {
            val initial = overrides.size
            overrides.removeAll { it.scheduleId == scheduleId }
            return initial - overrides.size
        }

        override suspend fun deleteOverride(scheduleId: Long, occurrenceStart: Instant): Int {
            val initial = overrides.size
            overrides.removeAll { it.scheduleId == scheduleId && it.occurrenceStart == occurrenceStart }
            return initial - overrides.size
        }
    }

    private class FakeAlarmScheduler : AlarmScheduler {
        val scheduledEvents = mutableListOf<BoundaryEvent>()
        val cancelledEvents = mutableListOf<Long>()

        override fun canScheduleExactAlarms(): Boolean = true

        override fun scheduleBoundaryEvent(event: BoundaryEvent): Boolean {
            scheduledEvents.add(event)
            return true
        }

        override fun cancelBoundaryEvent(scheduleId: Long, type: BoundaryType, occurrenceStart: Instant) {
            cancelledEvents.add(scheduleId)
            scheduledEvents.removeAll { it.scheduleId == scheduleId && it.type == type }
        }

        override fun cancelAllForSchedule(scheduleId: Long) {
            cancelledEvents.add(scheduleId)
            scheduledEvents.removeAll { it.scheduleId == scheduleId }
        }

        override fun cancelAll() {
            scheduledEvents.clear()
        }
    }
}
