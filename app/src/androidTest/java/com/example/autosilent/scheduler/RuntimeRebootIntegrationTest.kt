package com.example.autosilent.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autosilent.AutoSilentApplication
import com.example.autosilent.domain.model.RecurrenceType
import com.example.autosilent.domain.model.Schedule
import com.example.autosilent.domain.model.SoundMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Android runtime integration tests for validating device reboot recovery workflow.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeRebootIntegrationTest {

    private lateinit var context: Context
    private lateinit var app: AutoSilentApplication
    private lateinit var alarmScheduler: AndroidAlarmScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = context as AutoSilentApplication
        alarmScheduler = AndroidAlarmScheduler(context)

        try {
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .executeShellCommand("cmd appops set com.example.autosilent SCHEDULE_EXACT_ALARM allow")
        } catch (_: Throwable) {}
    }

    /**
     * Prepares persistent schedules in Room DB before executing an actual device reboot.
     * Inserts an active schedule (started 15 mins ago, ends in 45 mins) and future schedule.
     */
    @Test
    fun step1_prepareDatabaseAndConfirmAlarmBeforeReboot() = runBlocking {
        // Clean database
        app.repository.getSchedulesFlow().first().forEach { schedule ->
            app.repository.deleteSchedule(schedule.id)
        }

        val zone = ZoneId.systemDefault()
        val nowInstant = Instant.now()
        val activeStartInstant = nowInstant.minus(java.time.Duration.ofMinutes(15))
        val activeEndInstant = nowInstant.plus(java.time.Duration.ofMinutes(45))
        val activeStartZdt = activeStartInstant.atZone(zone)
        val activeEndZdt = activeEndInstant.atZone(zone)

        // 1. Active schedule during reboot (started 15 mins ago, ends in 45 mins)
        val activeSchedule = Schedule(
            name = "Reboot Active Meeting",
            startTime = activeStartZdt.toLocalTime(),
            endTime = activeEndZdt.toLocalTime(),
            recurrenceType = RecurrenceType.DAILY,
            startDate = activeStartZdt.toLocalDate()
        )
        val activeId = app.repository.insertSchedule(activeSchedule)

        // 2. Future schedule (starts in 60 mins, ends in 120 mins)
        val futureStartInstant = nowInstant.plus(java.time.Duration.ofMinutes(60))
        val futureEndInstant = nowInstant.plus(java.time.Duration.ofMinutes(120))
        val futureStartZdt = futureStartInstant.atZone(zone)
        val futureEndZdt = futureEndInstant.atZone(zone)

        val futureSchedule = Schedule(
            name = "Reboot Future Study",
            startTime = futureStartZdt.toLocalTime(),
            endTime = futureEndZdt.toLocalTime(),
            recurrenceType = RecurrenceType.DAILY,
            startDate = futureStartZdt.toLocalDate()
        )
        val futureId = app.repository.insertSchedule(futureSchedule)

        // Perform initial reconciliation
        val eval = app.reconciler.reconcile(nowInstant, zone)
        assertEquals("Desired sound mode must be SILENT during active schedule", SoundMode.SILENT, eval.desiredSoundMode)
        assertEquals(1, eval.activeOccurrences.size)
        assertNotNull("Next boundary event must be scheduled", eval.nextBoundaryEvent)
        assertEquals(activeId, eval.nextBoundaryEvent?.scheduleId)

        DiagnosticLogger.i("AutoSilentBootTest", "PRE_REBOOT_SETUP_DONE: activeId=$activeId, futureId=$futureId, nextBoundary=${eval.nextBoundaryEvent?.triggerInstant}")
    }

    /**
     * Executes post-reboot verification: asserts that state was recovered from Room ground truth,
     * the currently active schedule keeps the mode SILENT, and next boundary is the END boundary.
     */
    @Test
    fun step2_verifyStateAndAlarmAfterReboot() = runBlocking {
        // Allow BootReceiver background coroutine to finish
        kotlinx.coroutines.delay(2000)

        // Check current evaluation state or trigger fresh reconcile to inspect
        val eval = app.reconciler.reconcile(Instant.now(), ZoneId.systemDefault())

        DiagnosticLogger.i("AutoSilentBootTest", "POST_REBOOT_EVALUATION: mode=${eval.desiredSoundMode}, activeCount=${eval.activeOccurrences.size}, nextBoundary=${eval.nextBoundaryEvent?.triggerInstant}")
        assertEquals("Desired sound mode after reboot must be SILENT for active occurrence", SoundMode.SILENT, eval.desiredSoundMode)
        assertEquals("Active schedule must be recovered from Room", 1, eval.activeOccurrences.size)
        assertEquals("Active schedule name must match", "Reboot Active Meeting", eval.activeOccurrences.first().scheduleName)
        assertNotNull("Next boundary must be scheduled", eval.nextBoundaryEvent)

        // Verify PendingIntent exists in system AlarmManager
        val nextEvent = eval.nextBoundaryEvent!!
        val reqCode = AndroidAlarmScheduler.computeRequestCode(nextEvent.scheduleId, nextEvent.type, nextEvent.occurrenceStart)
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AndroidAlarmScheduler.ACTION_ALARM_BOUNDARY
            data = AndroidAlarmScheduler.createEventUri(nextEvent.scheduleId, nextEvent.type, nextEvent.occurrenceStart)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            reqCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull("Next boundary alarm must be registered in AlarmManager after reboot", pi)

        // Cleanup
        app.repository.getSchedulesFlow().first().forEach { schedule ->
            app.repository.deleteSchedule(schedule.id)
        }
    }
}
