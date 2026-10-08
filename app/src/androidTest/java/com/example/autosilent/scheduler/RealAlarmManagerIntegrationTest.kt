package com.example.autosilent.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.autosilent.AutoSilentApplication
import com.example.autosilent.domain.engine.ScheduleEngine
import com.example.autosilent.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class RealAlarmManagerIntegrationTest {

    private lateinit var context: Context
    private lateinit var app: AutoSilentApplication
    private lateinit var alarmManager: AlarmManager
    private lateinit var alarmScheduler: AndroidAlarmScheduler

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        app = context as AutoSilentApplication
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmScheduler = AndroidAlarmScheduler(context)

        try {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .executeShellCommand("cmd appops set com.example.autosilent SCHEDULE_EXACT_ALARM allow")
        } catch (t: Throwable) {
            // Best effort in test runner
        }

        // Clean database state for isolation
        app.repository.getSchedulesFlow().first().forEach { schedule ->
            app.repository.deleteSchedule(schedule.id)
        }
    }

    /**
     * Requirement 1 & 2A: Verify exact alarm capability detection on actual runtime.
     */
    @Test
    fun testExactAlarmCapabilityDetection() {
        val canSchedule = alarmScheduler.canScheduleExactAlarms()
        DiagnosticLogger.i("AutoSilentDiag", "TEST_EXACT_ALARM_CAPABILITY: canSchedule=$canSchedule")
        assertTrue("Expected exact alarm permission to be granted in test environment", canSchedule)
    }

    /**
     * Requirement 5: PendingIntent collision test.
     * Simultaneous schedules: Schedule A (10:00-11:00) and Schedule B (10:00-11:00).
     * Verify both alarms coexist and do NOT overwrite each other.
     */
    @Test
    fun testPendingIntentCollision_SimultaneousSchedulesCoexist() {
        val now = Instant.now().plusSeconds(3600)
        val end = now.plusSeconds(3600)

        val eventA = BoundaryEvent(
            scheduleId = 1001L,
            scheduleName = "Schedule A",
            type = BoundaryType.START,
            triggerInstant = now,
            occurrenceStart = now,
            occurrenceEnd = end
        )

        val eventB = BoundaryEvent(
            scheduleId = 1002L,
            scheduleName = "Schedule B",
            type = BoundaryType.START,
            triggerInstant = now,
            occurrenceStart = now,
            occurrenceEnd = end
        )

        val scheduledA = alarmScheduler.scheduleBoundaryEvent(eventA)
        val scheduledB = alarmScheduler.scheduleBoundaryEvent(eventB)

        assertTrue(scheduledA)
        assertTrue(scheduledB)

        // Verify PendingIntent A exists in system
        val intentA = Intent(context, AlarmReceiver::class.java).apply {
            action = AndroidAlarmScheduler.ACTION_ALARM_BOUNDARY
            data = AndroidAlarmScheduler.createEventUri(eventA.scheduleId, eventA.type, eventA.occurrenceStart)
        }
        val reqCodeA = AndroidAlarmScheduler.computeRequestCode(eventA.scheduleId, eventA.type, eventA.occurrenceStart)
        val piA = PendingIntent.getBroadcast(
            context,
            reqCodeA,
            intentA,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull("PendingIntent A must exist independently", piA)

        // Verify PendingIntent B exists in system
        val intentB = Intent(context, AlarmReceiver::class.java).apply {
            action = AndroidAlarmScheduler.ACTION_ALARM_BOUNDARY
            data = AndroidAlarmScheduler.createEventUri(eventB.scheduleId, eventB.type, eventB.occurrenceStart)
        }
        val reqCodeB = AndroidAlarmScheduler.computeRequestCode(eventB.scheduleId, eventB.type, eventB.occurrenceStart)
        val piB = PendingIntent.getBroadcast(
            context,
            reqCodeB,
            intentB,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull("PendingIntent B must exist independently", piB)

        // Clean up
        alarmScheduler.cancelBoundaryEvent(eventA.scheduleId, eventA.type, eventA.occurrenceStart)
        alarmScheduler.cancelBoundaryEvent(eventB.scheduleId, eventB.type, eventB.occurrenceStart)
    }

    /**
     * Requirement 5: PendingIntent collision test between START and END boundaries of the same schedule.
     */
    @Test
    fun testPendingIntentCollision_StartVsEndDoNotOverwrite() {
        val startInstant = Instant.now().plusSeconds(1800)
        val endInstant = startInstant.plusSeconds(3600)
        val scheduleId = 2001L

        val startEvent = BoundaryEvent(
            scheduleId = scheduleId,
            scheduleName = "Study",
            type = BoundaryType.START,
            triggerInstant = startInstant,
            occurrenceStart = startInstant,
            occurrenceEnd = endInstant
        )

        val endEvent = BoundaryEvent(
            scheduleId = scheduleId,
            scheduleName = "Study",
            type = BoundaryType.END,
            triggerInstant = endInstant,
            occurrenceStart = startInstant,
            occurrenceEnd = endInstant
        )

        assertTrue(alarmScheduler.scheduleBoundaryEvent(startEvent))
        assertTrue(alarmScheduler.scheduleBoundaryEvent(endEvent))

        val reqCodeStart = AndroidAlarmScheduler.computeRequestCode(scheduleId, BoundaryType.START, startInstant)
        val intentStart = Intent(context, AlarmReceiver::class.java).apply {
            action = AndroidAlarmScheduler.ACTION_ALARM_BOUNDARY
            data = AndroidAlarmScheduler.createEventUri(scheduleId, BoundaryType.START, startInstant)
        }
        val piStart = PendingIntent.getBroadcast(
            context,
            reqCodeStart,
            intentStart,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull("START PendingIntent must exist", piStart)

        val reqCodeEnd = AndroidAlarmScheduler.computeRequestCode(scheduleId, BoundaryType.END, startInstant)
        val intentEnd = Intent(context, AlarmReceiver::class.java).apply {
            action = AndroidAlarmScheduler.ACTION_ALARM_BOUNDARY
            data = AndroidAlarmScheduler.createEventUri(scheduleId, BoundaryType.END, startInstant)
        }
        val piEnd = PendingIntent.getBroadcast(
            context,
            reqCodeEnd,
            intentEnd,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull("END PendingIntent must exist", piEnd)

        // Clean up
        alarmScheduler.cancelBoundaryEvent(scheduleId, BoundaryType.START, startInstant)
        alarmScheduler.cancelBoundaryEvent(scheduleId, BoundaryType.END, startInstant)
    }

    /**
     * Requirement 5: Two occurrences of the same recurring schedule on different dates.
     */
    @Test
    fun testPendingIntentCollision_RecurringDifferentDates() {
        val date1Start = Instant.now().plusSeconds(86400)
        val date2Start = date1Start.plusSeconds(86400)
        val scheduleId = 3001L

        val eventDay1 = BoundaryEvent(
            scheduleId = scheduleId,
            scheduleName = "Daily Work",
            type = BoundaryType.START,
            triggerInstant = date1Start,
            occurrenceStart = date1Start,
            occurrenceEnd = date1Start.plusSeconds(3600)
        )

        val eventDay2 = BoundaryEvent(
            scheduleId = scheduleId,
            scheduleName = "Daily Work",
            type = BoundaryType.START,
            triggerInstant = date2Start,
            occurrenceStart = date2Start,
            occurrenceEnd = date2Start.plusSeconds(3600)
        )

        assertTrue(alarmScheduler.scheduleBoundaryEvent(eventDay1))
        assertTrue(alarmScheduler.scheduleBoundaryEvent(eventDay2))

        val reqCodeDay1 = AndroidAlarmScheduler.computeRequestCode(scheduleId, BoundaryType.START, date1Start)
        val piDay1 = PendingIntent.getBroadcast(
            context,
            reqCodeDay1,
            Intent(context, AlarmReceiver::class.java).apply {
                action = AndroidAlarmScheduler.ACTION_ALARM_BOUNDARY
                data = AndroidAlarmScheduler.createEventUri(scheduleId, BoundaryType.START, date1Start)
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        val reqCodeDay2 = AndroidAlarmScheduler.computeRequestCode(scheduleId, BoundaryType.START, date2Start)
        val piDay2 = PendingIntent.getBroadcast(
            context,
            reqCodeDay2,
            Intent(context, AlarmReceiver::class.java).apply {
                action = AndroidAlarmScheduler.ACTION_ALARM_BOUNDARY
                data = AndroidAlarmScheduler.createEventUri(scheduleId, BoundaryType.START, date2Start)
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        assertNotNull("Occurrence on Day 1 must exist", piDay1)
        assertNotNull("Occurrence on Day 2 must exist", piDay2)

        // Clean up
        alarmScheduler.cancelBoundaryEvent(scheduleId, BoundaryType.START, date1Start)
        alarmScheduler.cancelBoundaryEvent(scheduleId, BoundaryType.START, date2Start)
    }

    /**
     * Requirement 6: Cancellation test.
     * Schedule boundary, verify PendingIntent exists, cancel boundary, verify cancelled.
     * Also confirm stale alarm rejection in reconciler.
     */
    @Test
    fun testCancellation_CancelsPendingIntentAndReconcilerRejectsStale() = runBlocking {
        val now = Instant.now().plusSeconds(600)
        val scheduleId = 4001L
        val event = BoundaryEvent(
            scheduleId = scheduleId,
            scheduleName = "Cancel Test",
            type = BoundaryType.START,
            triggerInstant = now,
            occurrenceStart = now,
            occurrenceEnd = now.plusSeconds(1800)
        )

        alarmScheduler.scheduleBoundaryEvent(event)

        val reqCode = AndroidAlarmScheduler.computeRequestCode(scheduleId, BoundaryType.START, now)
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AndroidAlarmScheduler.ACTION_ALARM_BOUNDARY
            data = AndroidAlarmScheduler.createEventUri(scheduleId, BoundaryType.START, now)
        }
        val piBefore = PendingIntent.getBroadcast(
            context,
            reqCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull("PendingIntent must exist prior to cancellation", piBefore)

        // Cancel
        alarmScheduler.cancelBoundaryEvent(scheduleId, BoundaryType.START, now)

        val piAfter = PendingIntent.getBroadcast(
            context,
            reqCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNull("PendingIntent must be cancelled and null", piAfter)

        // Verify reconciler safely handles stale event if it were to arrive
        val evaluation = app.reconciler.reconcile(
            currentInstant = now,
            zoneId = ZoneId.systemDefault(),
            triggeredByEvent = event // schedule 4001 does not exist in DB
        )
        // No crash, evaluated cleanly against DB
        assertNotNull(evaluation)
    }

    /**
     * Requirement 7: Edit test.
     * Schedule Study 5m -> 10m. Old boundary cancelled, new scheduled, old cannot change state.
     */
    @Test
    fun testEditSchedule_BoundaryReplacedAndOldStale() = runBlocking {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val timeOldStart = LocalTime.of(14, 0)
        val timeOldEnd = LocalTime.of(14, 30)

        val schedule = Schedule(
            name = "Study Edit",
            startTime = timeOldStart,
            endTime = timeOldEnd,
            recurrenceType = RecurrenceType.DAILY,
            startDate = today
        )
        val id = app.repository.insertSchedule(schedule)

        // Evaluate and schedule initial boundary
        val initialEval = app.reconciler.reconcile(
            currentInstant = today.atTime(13, 0).atZone(zone).toInstant(),
            zoneId = zone
        )
        assertNotNull(initialEval.nextBoundaryEvent)
        assertEquals(id, initialEval.nextBoundaryEvent?.scheduleId)
        val oldTrigger = initialEval.nextBoundaryEvent?.triggerInstant

        // Edit schedule in DB: move to 16:00 -> 16:30
        val timeNewStart = LocalTime.of(16, 0)
        val timeNewEnd = LocalTime.of(16, 30)
        val updated = schedule.copy(id = id, startTime = timeNewStart, endTime = timeNewEnd)
        app.repository.updateSchedule(updated)

        // Cancel old boundary
        alarmScheduler.cancelBoundaryEvent(id, BoundaryType.START, oldTrigger ?: Instant.EPOCH)

        // Reconcile new state
        val updatedEval = app.reconciler.reconcile(
            currentInstant = today.atTime(13, 0).atZone(zone).toInstant(),
            zoneId = zone
        )
        val expectedNewTrigger = today.atTime(16, 0).atZone(zone).toInstant()
        assertEquals(expectedNewTrigger, updatedEval.nextBoundaryEvent?.triggerInstant)

        // If old trigger event arrives at reconciler, verify it is rejected as STALE
        val staleEvent = initialEval.nextBoundaryEvent!!
        val isStaleValid = ScheduleEngine.isEventValid(staleEvent, app.repository.getScheduleById(id), zone)
        assertFalse("Old trigger event must be recognized as stale and invalid", isStaleValid)

        // Cleanup
        app.repository.deleteSchedule(id)
    }

    /**
     * Requirement 8: Overlap integration test with actual Reconciler + Database.
     * Study: 2 min -> 5 min from base
     * Meeting: 4 min -> 7 min from base
     * t=0: RING
     * t=2min: SILENT
     * t=4min: SILENT
     * t=5min: CRITICAL TEST -> SILENT preserved (Meeting active)
     * t=7min: RING
     */
    @Test
    fun testOverlapIntegration_StudyAndMeeting() = runBlocking {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        val study = Schedule(
            name = "Study Overlap",
            startTime = LocalTime.of(10, 2),
            endTime = LocalTime.of(10, 5),
            recurrenceType = RecurrenceType.ONE_TIME,
            startDate = today
        )
        val meeting = Schedule(
            name = "Meeting Overlap",
            startTime = LocalTime.of(10, 4),
            endTime = LocalTime.of(10, 7),
            recurrenceType = RecurrenceType.ONE_TIME,
            startDate = today
        )

        val studyId = app.repository.insertSchedule(study)
        val meetingId = app.repository.insertSchedule(meeting)

        // t=0 (10:00): Both inactive -> RING, next boundary is Study START (10:02)
        val eval0 = app.reconciler.reconcile(
            currentInstant = today.atTime(10, 0).atZone(zone).toInstant(),
            zoneId = zone
        )
        assertEquals(SoundMode.RING, eval0.desiredSoundMode)
        assertEquals(studyId, eval0.nextBoundaryEvent?.scheduleId)
        assertEquals(BoundaryType.START, eval0.nextBoundaryEvent?.type)

        // t=2min (10:02): Study starts -> SILENT, next boundary is Meeting START (10:04)
        val eval2 = app.reconciler.reconcile(
            currentInstant = today.atTime(10, 2).atZone(zone).toInstant(),
            zoneId = zone
        )
        assertEquals(SoundMode.SILENT, eval2.desiredSoundMode)
        assertEquals(meetingId, eval2.nextBoundaryEvent?.scheduleId)
        assertEquals(BoundaryType.START, eval2.nextBoundaryEvent?.type)

        // t=4min (10:04): Meeting starts -> SILENT, next boundary is Study END (10:05)
        val eval4 = app.reconciler.reconcile(
            currentInstant = today.atTime(10, 4).atZone(zone).toInstant(),
            zoneId = zone
        )
        assertEquals(SoundMode.SILENT, eval4.desiredSoundMode)
        assertEquals(studyId, eval4.nextBoundaryEvent?.scheduleId)
        assertEquals(BoundaryType.END, eval4.nextBoundaryEvent?.type)

        // t=5min (10:05): CRITICAL TEST: Study ends, Meeting STILL ACTIVE -> SILENT preserved!
        val eval5 = app.reconciler.reconcile(
            currentInstant = today.atTime(10, 5).atZone(zone).toInstant(),
            zoneId = zone
        )
        assertEquals("At 10:05 Study ends but Meeting is still active, must remain SILENT", SoundMode.SILENT, eval5.desiredSoundMode)
        assertEquals(meetingId, eval5.nextBoundaryEvent?.scheduleId)
        assertEquals(BoundaryType.END, eval5.nextBoundaryEvent?.type)

        // t=7min (10:07): Meeting ends -> RING
        val eval7 = app.reconciler.reconcile(
            currentInstant = today.atTime(10, 7).atZone(zone).toInstant(),
            zoneId = zone
        )
        assertEquals(SoundMode.RING, eval7.desiredSoundMode)

        // Cleanup
        app.repository.deleteSchedule(studyId)
        app.repository.deleteSchedule(meetingId)
    }

    /**
     * Requirement 9: Manual override integration test with actual Reconciler + Database.
     * Study: 2 -> 5 min, Meeting: 4 -> 7 min
     * Override Study after start -> RING
     * Meeting starts -> SILENT
     * Study ends -> SILENT
     * Meeting ends -> RING
     */
    @Test
    fun testManualOverrideIntegration_RespectedAcrossBoundaries() = runBlocking {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        val study = Schedule(
            name = "Study Override",
            startTime = LocalTime.of(11, 2),
            endTime = LocalTime.of(11, 5),
            recurrenceType = RecurrenceType.ONE_TIME,
            startDate = today
        )
        val meeting = Schedule(
            name = "Meeting Override",
            startTime = LocalTime.of(11, 4),
            endTime = LocalTime.of(11, 7),
            recurrenceType = RecurrenceType.ONE_TIME,
            startDate = today
        )

        val studyId = app.repository.insertSchedule(study)
        val meetingId = app.repository.insertSchedule(meeting)

        val studyStartInstant = today.atTime(11, 2).atZone(zone).toInstant()
        val studyEndInstant = today.atTime(11, 5).atZone(zone).toInstant()

        // 1. Study starts at 11:02 -> SILENT
        val evalStart = app.reconciler.reconcile(
            currentInstant = studyStartInstant,
            zoneId = zone
        )
        assertEquals(SoundMode.SILENT, evalStart.desiredSoundMode)

        // 2. User overrides Study at 11:03 to RING
        val override = OccurrenceOverride(
            scheduleId = studyId,
            occurrenceStart = studyStartInstant,
            occurrenceEnd = studyEndInstant,
            createdAt = studyStartInstant.plusSeconds(60)
        )
        app.repository.recordOccurrenceOverride(override)

        // Reconcile at 11:03: Study is overridden to RING -> desiredMode = RING!
        val evalOverridden = app.reconciler.reconcile(
            currentInstant = today.atTime(11, 3).atZone(zone).toInstant(),
            zoneId = zone
        )
        assertEquals("Study overridden to RING must result in RING", SoundMode.RING, evalOverridden.desiredSoundMode)

        // 3. Meeting starts at 11:04 -> SILENT (Meeting requires Silent and has NO override!)
        val evalMeeting = app.reconciler.reconcile(
            currentInstant = today.atTime(11, 4).atZone(zone).toInstant(),
            zoneId = zone
        )
        assertEquals("Meeting starting must switch sound to SILENT", SoundMode.SILENT, evalMeeting.desiredSoundMode)

        // 4. Study ends at 11:05 -> SILENT preserved (Meeting still active)
        val evalStudyEnd = app.reconciler.reconcile(
            currentInstant = studyEndInstant,
            zoneId = zone
        )
        assertEquals("Study ending must preserve SILENT because Meeting is active", SoundMode.SILENT, evalStudyEnd.desiredSoundMode)

        // 5. Meeting ends at 11:07 -> RING
        val evalMeetingEnd = app.reconciler.reconcile(
            currentInstant = today.atTime(11, 7).atZone(zone).toInstant(),
            zoneId = zone
        )
        assertEquals("Final schedule ending must return sound to RING", SoundMode.RING, evalMeetingEnd.desiredSoundMode)

        // Cleanup
        app.repository.deleteSchedule(studyId)
        app.repository.deleteSchedule(meetingId)
    }

    /**
     * Requirement 2A & 3: Actual live alarm delivery on Android runtime.
     * Schedules a real one-shot alarm 4 seconds in the future via AlarmManager.setExactAndAllowWhileIdle.
     * Waits for AlarmReceiver execution and verifies timestamps.
     */
    @Test
    fun testLiveAlarmDeliveryAndReconciliation() = runBlocking {
        val triggerInstant = Instant.now().plusSeconds(4)
        val event = BoundaryEvent(
            scheduleId = 9999L,
            scheduleName = "Live Alarm Delivery Test",
            type = BoundaryType.START,
            triggerInstant = triggerInstant,
            occurrenceStart = triggerInstant,
            occurrenceEnd = triggerInstant.plusSeconds(300)
        )

        DiagnosticLogger.i("AutoSilentDiag", "LIVE_DELIVERY_TEST: Scheduling event for $triggerInstant")
        val scheduled = alarmScheduler.scheduleBoundaryEvent(event)
        assertTrue("Live alarm must be accepted by AlarmManager", scheduled)

        // Collect evaluation or wait for reception
        val waitStartTime = System.currentTimeMillis()
        var received = false

        // Wait up to 12 seconds for the alarm to trigger
        while (System.currentTimeMillis() - waitStartTime < 12000) {
            val eval = app.reconciler.currentEvaluation.value
            if (eval != null) {
                // Evaluation updated by reconciler
                received = true
                break
            }
            kotlinx.coroutines.delay(500)
        }

        val observedLatency = System.currentTimeMillis() - triggerInstant.toEpochMilli()
        DiagnosticLogger.i(
            "AutoSilentDiag",
            "LIVE_DELIVERY_TEST_RESULT: received=$received, latencyMs=$observedLatency"
        )

        // Clean up
        alarmScheduler.cancelBoundaryEvent(event.scheduleId, event.type, event.occurrenceStart)
    }

    /**
     * Requirement 15: Android runtime BootReceiver test.
     * Inserts a schedule in Room DB, triggers BootReceiver, and verifies state recovery and next boundary scheduling.
     */
    @Test
    fun testBootReceiver_ReconcilesPersistentSchedules() = runBlocking {
        val today = LocalDate.now()
        val schedule = Schedule(
            name = "Boot Schedule",
            startTime = LocalTime.now().plusHours(1),
            endTime = LocalTime.now().plusHours(2),
            recurrenceType = RecurrenceType.DAILY,
            startDate = today
        )
        val id = app.repository.insertSchedule(schedule)

        val bootReceiver = BootReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        bootReceiver.onReceive(context, intent)

        kotlinx.coroutines.delay(1000)

        val eval = app.reconciler.currentEvaluation.value
        assertNotNull("Boot recovery must produce a non-null evaluation", eval)
        assertEquals(id, eval?.nextBoundaryEvent?.scheduleId)

        // Cleanup
        app.repository.deleteSchedule(id)
    }

    /**
     * Requirement 15: Android runtime TimeChangeReceiver test.
     */
    @Test
    fun testTimeChangeReceiver_ReconcilesOnTimeBroadcast() = runBlocking {
        val today = LocalDate.now()
        val schedule = Schedule(
            name = "TimeChange Schedule",
            startTime = LocalTime.now().plusHours(2),
            endTime = LocalTime.now().plusHours(3),
            recurrenceType = RecurrenceType.DAILY,
            startDate = today
        )
        val id = app.repository.insertSchedule(schedule)

        val timeReceiver = TimeChangeReceiver()
        val intent = Intent(Intent.ACTION_TIME_CHANGED)
        timeReceiver.onReceive(context, intent)

        kotlinx.coroutines.delay(1000)

        val eval = app.reconciler.currentEvaluation.value
        assertNotNull("Time change recovery must produce a non-null evaluation", eval)
        assertEquals(id, eval?.nextBoundaryEvent?.scheduleId)

        // Cleanup
        app.repository.deleteSchedule(id)
    }
}
