package com.example.autosilent.system

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autosilent.AutoSilentApplication
import com.example.autosilent.domain.controller.SoundController
import com.example.autosilent.domain.model.OccurrenceOverride
import com.example.autosilent.domain.model.RecurrenceType
import com.example.autosilent.domain.model.Schedule
import com.example.autosilent.domain.model.SoundMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Android runtime integration tests for Phase 8: Real Sound Control / Actuation on API 37 emulator.
 *
 * Validates actual hardware/platform audio state changes via system [AudioManager]:
 * - Test A: Initial RING -> request SILENT -> verify device ringer mode == RINGER_MODE_SILENT
 * - Test B: Initial SILENT -> request RING -> verify device ringer mode == RINGER_MODE_NORMAL
 * - Test C: Repeated SILENT request -> verify idempotent no-op
 * - Test D: Repeated RING request -> verify idempotent no-op
 * - Test E: Schedule starts -> reconciler calculates SILENT -> actual device becomes SILENT
 * - Test F: Schedule ends -> reconciler calculates RING -> actual device becomes RING/NORMAL
 * - Test G: Overlapping schedules -> seamless SILENT continuity until final schedule ends
 * - Test H: Manual override -> active occurrence restored to RING without feedback loop
 */
@RunWith(AndroidJUnit4::class)
class RealSoundControlIntegrationTest {

    private lateinit var context: Context
    private lateinit var app: AutoSilentApplication
    private lateinit var audioManager: AudioManager
    private lateinit var soundController: SoundController

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        app = context as AutoSilentApplication
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        soundController = app.soundController

        // Ensure exact alarm and notification policy (DND) permissions are allowed in test environment
        try {
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .executeShellCommand("cmd appops set com.example.autosilent SCHEDULE_EXACT_ALARM allow")
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .executeShellCommand("cmd notification allow_dnd com.example.autosilent")
        } catch (_: Throwable) {}

        // Ensure device starts in NORMAL (RING) ringer mode
        try {
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        } catch (_: Throwable) {}

        // Clean Room repository state for test isolation
        app.repository.getSchedulesFlow().first().forEach { schedule ->
            app.repository.deleteSchedule(schedule.id)
        }
    }

    @After
    fun tearDown() = runBlocking {
        // Restore ringer mode to NORMAL
        try {
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        } catch (_: Throwable) {}

        app.repository.getSchedulesFlow().first().forEach { schedule ->
            app.repository.deleteSchedule(schedule.id)
        }
    }

    /**
     * Test A: Initial RING -> request SILENT -> verify actual device ringer mode becomes SILENT.
     */
    @Test
    fun testA_InitialRing_RequestSilent_DeviceBecomesSilent() {
        soundController.setRing()
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)

        val success = soundController.setSilent()
        assertTrue("setSilent must succeed when policy access is granted", success)
        assertEquals(SoundMode.SILENT, soundController.getCurrentMode())
        assertEquals(
            "Hardware ringer mode must be RINGER_MODE_SILENT",
            AudioManager.RINGER_MODE_SILENT,
            audioManager.ringerMode
        )
    }

    /**
     * Test B: Initial SILENT -> request RING -> verify actual device ringer mode becomes RING/NORMAL.
     */
    @Test
    fun testB_InitialSilent_RequestRing_DeviceBecomesNormal() {
        soundController.setSilent()
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        val success = soundController.setRing()
        assertTrue("setRing must succeed when transitioning from SILENT", success)
        assertEquals(SoundMode.RING, soundController.getCurrentMode())
        assertEquals(
            "Hardware ringer mode must be RINGER_MODE_NORMAL",
            AudioManager.RINGER_MODE_NORMAL,
            audioManager.ringerMode
        )
    }

    /**
     * Test C: Repeated SILENT request -> verify idempotency and no incorrect behavior.
     */
    @Test
    fun testC_RepeatedSilentRequest_Idempotent() {
        soundController.setSilent()
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        // Subsequent call
        val success2 = soundController.setSilent()
        assertTrue("Repeated setSilent must succeed idempotently", success2)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
    }

    /**
     * Test D: Repeated RING request -> verify idempotency and no incorrect behavior.
     */
    @Test
    fun testD_RepeatedRingRequest_Idempotent() {
        soundController.setRing()
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)

        // Subsequent call
        val success2 = soundController.setRing()
        assertTrue("Repeated setRing must succeed idempotently", success2)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
    }

    /**
     * Test E: Schedule starts -> reconciler calculates SILENT -> actual Android state becomes SILENT.
     */
    @Test
    fun testE_ScheduleStarts_ReconcilerSilencesDevice() = runBlocking {
        val today = LocalDate.now()
        val now = LocalTime.now()

        // Active schedule starting 10 minutes ago, ending 50 minutes from now
        val activeSchedule = Schedule(
            name = "Test E Study",
            startTime = now.minusMinutes(10),
            endTime = now.plusMinutes(50),
            recurrenceType = RecurrenceType.DAILY,
            startDate = today
        )
        val id = app.repository.insertSchedule(activeSchedule)

        val eval = app.reconciler.reconcile(Instant.now(), ZoneId.systemDefault())
        assertEquals("Desired sound mode must be SILENT", SoundMode.SILENT, eval.desiredSoundMode)
        assertEquals("Controller current mode must be SILENT", SoundMode.SILENT, soundController.getCurrentMode())
        assertEquals("Actual Android AudioManager ringer mode must be SILENT", AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        app.repository.deleteSchedule(id)
    }

    /**
     * Test F: Schedule ends -> reconciler calculates RING -> actual Android state becomes RING.
     */
    @Test
    fun testF_ScheduleEnds_ReconcilerRestoresRing() = runBlocking {
        val today = LocalDate.now()
        val now = LocalTime.now()

        // Schedule ended 10 minutes ago
        val pastSchedule = Schedule(
            name = "Test F Completed",
            startTime = now.minusMinutes(50),
            endTime = now.minusMinutes(10),
            recurrenceType = RecurrenceType.DAILY,
            startDate = today
        )
        val id = app.repository.insertSchedule(pastSchedule)

        // Start from SILENT
        soundController.setSilent()
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        val eval = app.reconciler.reconcile(Instant.now(), ZoneId.systemDefault())
        assertEquals("Desired sound mode must be RING when no active schedule exists", SoundMode.RING, eval.desiredSoundMode)
        assertEquals("Controller current mode must be RING", SoundMode.RING, soundController.getCurrentMode())
        assertEquals("Actual Android AudioManager ringer mode must be NORMAL", AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)

        app.repository.deleteSchedule(id)
    }

    /**
     * Test G: Overlapping schedules (Study 10:00–12:00, Meeting 11:00–13:00):
     * 10:00 -> SILENT
     * 11:00 -> remains SILENT
     * 12:00 -> remains SILENT
     * 13:00 -> RING
     */
    @Test
    fun testG_OverlappingSchedules_SeamlessActuation() = runBlocking {
        val zone = ZoneId.systemDefault()
        val baseDate = LocalDate.now()

        val study = Schedule(
            name = "Study Overlap",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(12, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val meeting = Schedule(
            name = "Meeting Overlap",
            startTime = LocalTime.of(11, 0),
            endTime = LocalTime.of(13, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val studyId = app.repository.insertSchedule(study)
        val meetingId = app.repository.insertSchedule(meeting)

        // 10:00 -> Study starts -> SILENT
        val t1000 = baseDate.atTime(10, 0).atZone(zone).toInstant()
        val e1000 = app.reconciler.reconcile(t1000, zone)
        assertEquals(SoundMode.SILENT, e1000.desiredSoundMode)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        // 11:00 -> Meeting starts -> remains SILENT
        val t1100 = baseDate.atTime(11, 0).atZone(zone).toInstant()
        val e1100 = app.reconciler.reconcile(t1100, zone)
        assertEquals(SoundMode.SILENT, e1100.desiredSoundMode)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        // 12:00 -> Study ends, Meeting still active -> remains SILENT
        val t1200 = baseDate.atTime(12, 0).atZone(zone).toInstant()
        val e1200 = app.reconciler.reconcile(t1200, zone)
        assertEquals(SoundMode.SILENT, e1200.desiredSoundMode)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        // 13:00 -> Meeting ends -> RING
        val t1300 = baseDate.atTime(13, 0).atZone(zone).toInstant()
        val e1300 = app.reconciler.reconcile(t1300, zone)
        assertEquals(SoundMode.RING, e1300.desiredSoundMode)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)

        app.repository.deleteSchedule(studyId)
        app.repository.deleteSchedule(meetingId)
    }

    /**
     * Test H: Manual override:
     * Schedule active -> SILENT -> occurrence override -> RING -> no immediate re-silencing.
     */
    @Test
    fun testH_ManualOverride_RestoresRingWithoutLoop() = runBlocking {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val nowTime = LocalTime.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)

        val schedule = Schedule(
            name = "Test H Focus",
            startTime = nowTime.minusMinutes(15),
            endTime = nowTime.plusMinutes(45),
            recurrenceType = RecurrenceType.DAILY,
            startDate = today
        )
        val id = app.repository.insertSchedule(schedule)

        // Initial state -> SILENT
        val nowInstant = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val evalInitial = app.reconciler.reconcile(nowInstant, zone)
        assertEquals(SoundMode.SILENT, evalInitial.desiredSoundMode)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        // Occurrence override recorded
        val occ = evalInitial.activeOccurrences.first()
        val override = OccurrenceOverride(
            scheduleId = id,
            occurrenceStart = occ.startInstant,
            occurrenceEnd = occ.endInstant,
            createdAt = nowInstant
        )
        app.repository.recordOccurrenceOverride(override)

        // Reconcile after override -> device becomes RING/NORMAL
        val evalOverridden = app.reconciler.reconcile(nowInstant.plusSeconds(30), zone)
        assertEquals("Manual override must transition to RING", SoundMode.RING, evalOverridden.desiredSoundMode)
        assertEquals("Actual Android mode must be NORMAL", AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)

        // Subsequent reconciliation must NOT force SILENT
        val evalSubsequent = app.reconciler.reconcile(nowInstant.plusSeconds(60), zone)
        assertEquals("Subsequent reconciliation must remain RING", SoundMode.RING, evalSubsequent.desiredSoundMode)
        assertEquals("Actual Android mode must remain NORMAL", AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)

        app.repository.deleteSchedule(id)
    }
}
