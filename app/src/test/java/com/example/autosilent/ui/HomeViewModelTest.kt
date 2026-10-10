package com.example.autosilent.ui

import com.example.autosilent.domain.model.OccurrenceOverride
import com.example.autosilent.domain.model.RecurrenceType
import com.example.autosilent.domain.model.Schedule
import com.example.autosilent.domain.model.SoundMode
import com.example.autosilent.domain.usecase.DeleteScheduleUseCase
import com.example.autosilent.domain.usecase.GetSchedulesUseCase
import com.example.autosilent.domain.usecase.ResumeScheduleUseCase
import com.example.autosilent.domain.usecase.ToggleScheduleUseCase
import com.example.autosilent.scheduler.ScheduleReconciler
import com.example.autosilent.ui.fakes.FakeAlarmScheduler
import com.example.autosilent.ui.fakes.FakeScheduleRepository
import com.example.autosilent.ui.fakes.FakeSoundController
import com.example.autosilent.ui.home.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val zone = ZoneId.of("UTC")
    private val baseDate = LocalDate.of(2026, 10, 12)

    private lateinit var repository: FakeScheduleRepository
    private lateinit var alarmScheduler: FakeAlarmScheduler
    private lateinit var soundController: FakeSoundController
    private lateinit var reconciler: ScheduleReconciler
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeScheduleRepository()
        alarmScheduler = FakeAlarmScheduler()
        soundController = FakeSoundController()
        reconciler = ScheduleReconciler(
            repository = repository,
            alarmScheduler = alarmScheduler,
            soundController = soundController
        )

        viewModel = HomeViewModel(
            getSchedulesUseCase = GetSchedulesUseCase(repository),
            toggleScheduleUseCase = ToggleScheduleUseCase(repository),
            deleteScheduleUseCase = DeleteScheduleUseCase(repository),
            resumeScheduleUseCase = ResumeScheduleUseCase(repository),
            reconciler = reconciler,
            alarmScheduler = alarmScheduler,
            soundController = soundController,
            zoneId = zone
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // 1. Home loads schedules
    @Test
    fun home_loadsSchedules_correctly() = testScope.runTest {
        val schedule1 = Schedule(
            name = "Morning Study",
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(10, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val schedule2 = Schedule(
            name = "Evening Focus",
            startTime = LocalTime.of(18, 0),
            endTime = LocalTime.of(20, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        repository.insertSchedule(schedule1)
        repository.insertSchedule(schedule2)

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.schedules.size)
        assertEquals("Morning Study", state.schedules[0].name)
        assertEquals("Evening Focus", state.schedules[1].name)
        collectJob.cancel()
    }

    // 6. Toggle enabled/disabled
    @Test
    fun home_toggleEnabledDisabled_updatesStateAndReconciles() = testScope.runTest {
        val schedule = Schedule(
            name = "Daily Work",
            enabled = true,
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(17, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.toggleSchedule(id, false)
        advanceUntilIdle()

        val updated = repository.getScheduleById(id)
        assertNotNull(updated)
        assertFalse(updated!!.enabled)

        val state = viewModel.uiState.value
        assertFalse(state.schedules.first().enabled)
        collectJob.cancel()
    }

    // 5. Delete success
    @Test
    fun home_deleteSchedule_removesScheduleAndReconciles() = testScope.runTest {
        val schedule = Schedule(
            name = "To Delete",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.schedules.size)

        viewModel.deleteSchedule(id)
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.schedules.size)
        assertTrue(repository.getSchedulesFlow().first().isEmpty())
        collectJob.cancel()
    }

    // 7. Active schedule state
    @Test
    fun home_activeScheduleState_setsSilentAndMarksScheduleActive() = testScope.runTest {
        val schedule = Schedule(
            name = "Current Active",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(12, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        // Evaluate at 10:30 (active)
        val testInstant = baseDate.atTime(10, 30).atZone(zone).toInstant()
        reconciler.reconcile(testInstant, zone)

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(SoundMode.SILENT, state.currentSoundMode)
        assertEquals(1, state.activeScheduleCount)
        assertTrue(state.schedules.first { it.id == id }.isActive)
        collectJob.cancel()
    }

    // 8. Multiple active schedules (overlap)
    @Test
    fun home_multipleActiveSchedules_showsCountAndSilentMode() = testScope.runTest {
        val s1 = Schedule(
            name = "Study",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(12, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val s2 = Schedule(
            name = "Meeting",
            startTime = LocalTime.of(11, 0),
            endTime = LocalTime.of(13, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        repository.insertSchedule(s1)
        repository.insertSchedule(s2)

        // At 11:30 both are active
        val testInstant = baseDate.atTime(11, 30).atZone(zone).toInstant()
        reconciler.reconcile(testInstant, zone)

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(SoundMode.SILENT, state.currentSoundMode)
        assertEquals(2, state.activeScheduleCount)
        assertTrue(state.schedules[0].isActive)
        assertTrue(state.schedules[1].isActive)
        collectJob.cancel()
    }

    // 9. Manual override display
    @Test
    fun home_manualOverrideDisplay_showsOverriddenStatus() = testScope.runTest {
        val schedule = Schedule(
            name = "Focus",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        val evalInstant = baseDate.atTime(9, 30).atZone(zone).toInstant()
        val eval = reconciler.reconcile(evalInstant, zone)
        assertEquals(SoundMode.SILENT, eval.desiredSoundMode)

        // Record override
        val occ = eval.activeOccurrences.first()
        val override = OccurrenceOverride(
            scheduleId = id,
            occurrenceStart = occ.startInstant,
            occurrenceEnd = occ.endInstant,
            createdAt = evalInstant
        )
        repository.recordOccurrenceOverride(override)
        reconciler.reconcile(evalInstant.plusSeconds(10), zone)

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(SoundMode.RING, state.currentSoundMode)
        assertEquals(1, state.overriddenSchedules.size)
        assertTrue(state.schedules.first { it.id == id }.isOverridden)
        collectJob.cancel()
    }

    // 10. Resume override
    @Test
    fun home_resumeOverride_restoresSilenceWhenScheduleStillActive() = testScope.runTest {
        val schedule = Schedule(
            name = "Focus",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        val evalInstant = baseDate.atTime(9, 30).atZone(zone).toInstant()
        val eval = reconciler.reconcile(evalInstant, zone)
        val occ = eval.activeOccurrences.first()

        repository.recordOccurrenceOverride(
            OccurrenceOverride(
                scheduleId = id,
                occurrenceStart = occ.startInstant,
                occurrenceEnd = occ.endInstant,
                createdAt = evalInstant
            )
        )
        reconciler.reconcile(evalInstant.plusSeconds(10), zone)

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(SoundMode.RING, viewModel.uiState.value.currentSoundMode)

        // Resume schedule via ViewModel
        viewModel.resumeSchedule(id)
        advanceUntilIdle()

        // Overrides should be deleted
        assertTrue(repository.getActiveOverrides(evalInstant).isEmpty())
        collectJob.cancel()
    }

    // 11. Permission unavailable state
    @Test
    fun home_permissionUnavailableState_reflectedInUiState() = testScope.runTest {
        alarmScheduler.canScheduleExact = false
        soundController.canControl = false

        viewModel.refresh()
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isExactAlarmGranted)
        assertFalse(state.isSoundControlGranted)
        collectJob.cancel()
    }

    // 12. Repository failure state
    @Test
    fun home_repositoryFailureState_setsErrorMessage() = testScope.runTest {
        repository.shouldFailOnDelete = true
        val schedule = Schedule(
            name = "Fail Delete",
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(9, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.deleteSchedule(id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("Database error on delete"))
        collectJob.cancel()
    }

    // 13. Actual ringer mode reflection
    @Test
    fun home_actualRingerModeReflectedInState_whenMismatchedWithDesired() = testScope.runTest {
        val schedule = Schedule(
            name = "Manual override",
            startTime = LocalTime.of(12, 42),
            endTime = LocalTime.of(12, 45),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        repository.insertSchedule(schedule)

        // When permission is missing, actuation fails and actual mode remains RING
        soundController.canControl = false
        soundController.simulatedMode = SoundMode.RING

        // Evaluate when schedule is active (desired = SILENT)
        val testInstant = baseDate.atTime(12, 43).atZone(zone).toInstant()
        reconciler.reconcile(testInstant, zone)

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(SoundMode.RING, state.currentSoundMode)
        assertEquals(SoundMode.SILENT, state.desiredSoundMode)
        assertEquals(1, state.activeScheduleCount)
        collectJob.cancel()
    }

    // 14. Refresh re-reads actual ringer mode without altering device audio state
    @Test
    fun home_refresh_updatesCurrentSoundMode_whenHardwareRingerModeChanges() = testScope.runTest {
        val schedule = Schedule(
            name = "Test Schedule",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(12, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        repository.insertSchedule(schedule)

        // Schedule is active and device was placed in SILENT
        soundController.simulatedMode = SoundMode.SILENT
        val testInstant = baseDate.atTime(10, 30).atZone(zone).toInstant()
        reconciler.reconcile(testInstant, zone)

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(SoundMode.SILENT, viewModel.uiState.value.currentSoundMode)
        assertEquals(SoundMode.SILENT, viewModel.uiState.value.desiredSoundMode)

        // User manually changes ringer mode to RING outside the app via Android controls
        soundController.simulatedMode = SoundMode.RING

        // App resumes and invokes refresh()
        viewModel.refresh()
        advanceUntilIdle()

        val updatedState = viewModel.uiState.value
        // Status card reflects actual ringer mode (RING)
        assertEquals(SoundMode.RING, updatedState.currentSoundMode)
        // Desired sound mode is still SILENT
        assertEquals(SoundMode.SILENT, updatedState.desiredSoundMode)
        // Refresh must NOT have re-actuated the phone back into SILENT
        assertEquals(SoundMode.RING, soundController.getCurrentMode())

        collectJob.cancel()
    }

    // REGRESSION A: Silent -> Ring updates the status card
    @Test
    fun home_silentToRing_updatesStatusCard() = testScope.runTest {
        val schedule = Schedule(
            name = "Active Class",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        repository.insertSchedule(schedule)

        // Active schedule, device is SILENT
        soundController.simulatedMode = SoundMode.SILENT
        val testInstant = baseDate.atTime(9, 30).atZone(zone).toInstant()
        reconciler.reconcile(testInstant, zone)

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(SoundMode.SILENT, viewModel.uiState.value.currentSoundMode)
        assertEquals(SoundMode.SILENT, viewModel.uiState.value.desiredSoundMode)

        // User manually changes ringer mode to RING
        soundController.simulatedMode = SoundMode.RING
        advanceUntilIdle()

        val stateAfterRing = viewModel.uiState.value
        assertEquals(SoundMode.RING, stateAfterRing.currentSoundMode)
        assertEquals(SoundMode.SILENT, stateAfterRing.desiredSoundMode)

        collectJob.cancel()
    }

    // REGRESSION B: Ring -> Silent updates the status card
    @Test
    fun home_ringToSilent_updatesStatusCard() = testScope.runTest {
        val schedule = Schedule(
            name = "Active Class",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        repository.insertSchedule(schedule)

        val testInstant = baseDate.atTime(9, 30).atZone(zone).toInstant()
        reconciler.reconcile(testInstant, zone)

        // Active schedule was reconciled (desired = SILENT), but device was manually changed to RING
        soundController.simulatedMode = SoundMode.RING

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(SoundMode.RING, viewModel.uiState.value.currentSoundMode)
        assertEquals(SoundMode.SILENT, viewModel.uiState.value.desiredSoundMode)

        // User manually toggles phone back to SILENT (e.g. Xiaomi Quick Settings Mute tile)
        soundController.simulatedMode = SoundMode.SILENT
        advanceUntilIdle()

        val stateAfterSilent = viewModel.uiState.value
        assertEquals(SoundMode.SILENT, stateAfterSilent.currentSoundMode)
        assertEquals(SoundMode.SILENT, stateAfterSilent.desiredSoundMode)

        collectJob.cancel()
    }

    // REGRESSION C: Repeated Silent -> Ring -> Silent transitions update the card
    @Test
    fun home_repeatedTransitions_synchronizeStatusCard() = testScope.runTest {
        val schedule = Schedule(
            name = "Continuous Meeting",
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(12, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        repository.insertSchedule(schedule)

        soundController.simulatedMode = SoundMode.SILENT
        val testInstant = baseDate.atTime(9, 0).atZone(zone).toInstant()
        reconciler.reconcile(testInstant, zone)

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        // 1. Initially SILENT
        assertEquals(SoundMode.SILENT, viewModel.uiState.value.currentSoundMode)

        // 2. SILENT -> RING
        soundController.simulatedMode = SoundMode.RING
        advanceUntilIdle()
        assertEquals(SoundMode.RING, viewModel.uiState.value.currentSoundMode)

        // 3. RING -> SILENT
        soundController.simulatedMode = SoundMode.SILENT
        advanceUntilIdle()
        assertEquals(SoundMode.SILENT, viewModel.uiState.value.currentSoundMode)

        // 4. SILENT -> RING
        soundController.simulatedMode = SoundMode.RING
        advanceUntilIdle()
        assertEquals(SoundMode.RING, viewModel.uiState.value.currentSoundMode)

        // 5. RING -> SILENT
        soundController.simulatedMode = SoundMode.SILENT
        advanceUntilIdle()
        assertEquals(SoundMode.SILENT, viewModel.uiState.value.currentSoundMode)

        // Throughout all manual switches, desired mode remains unchanged
        assertEquals(SoundMode.SILENT, viewModel.uiState.value.desiredSoundMode)

        collectJob.cancel()
    }

    // REGRESSION D: UI refresh does not call setSilent() or setRing()
    @Test
    fun home_refresh_doesNotActuatePhoneRingerMode() = testScope.runTest {
        val schedule = Schedule(
            name = "Test Schedule",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(12, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        repository.insertSchedule(schedule)

        val testInstant = baseDate.atTime(10, 30).atZone(zone).toInstant()
        reconciler.reconcile(testInstant, zone)

        // User manually put phone in RING during active SILENT schedule
        soundController.simulatedMode = SoundMode.RING

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val silentCallsBefore = soundController.setSilentCalls
        val ringCallsBefore = soundController.setRingCalls

        // Refresh UI multiple times
        viewModel.refresh()
        viewModel.refresh()
        viewModel.refresh()
        advanceUntilIdle()

        // Absolutely no actuation calls must have been made
        assertEquals(silentCallsBefore, soundController.setSilentCalls)
        assertEquals(ringCallsBefore, soundController.setRingCalls)
        assertEquals(SoundMode.RING, soundController.getCurrentMode())

        collectJob.cancel()
    }

    // REGRESSION E: UI refresh preserves desired schedule mode and occurrence-level manual overrides
    @Test
    fun home_refresh_preservesDesiredScheduleModeAndOccurrenceOverrides() = testScope.runTest {
        val schedule = Schedule(
            name = "Important Meeting",
            startTime = LocalTime.of(13, 0),
            endTime = LocalTime.of(15, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        val occStart = baseDate.atTime(13, 0).atZone(zone).toInstant()
        val occEnd = baseDate.atTime(15, 0).atZone(zone).toInstant()
        val override = OccurrenceOverride(
            scheduleId = id,
            occurrenceStart = occStart,
            occurrenceEnd = occEnd
        )
        repository.recordOccurrenceOverride(override)

        val testInstant = baseDate.atTime(13, 30).atZone(zone).toInstant()
        reconciler.reconcile(testInstant, zone)

        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val stateBefore = viewModel.uiState.value
        assertEquals(1, stateBefore.overriddenSchedules.size)
        assertEquals(SoundMode.RING, stateBefore.desiredSoundMode)

        // User manually changes ringer to SILENT, then refreshes
        soundController.simulatedMode = SoundMode.SILENT
        viewModel.refresh()
        advanceUntilIdle()

        val stateAfter = viewModel.uiState.value
        assertEquals(SoundMode.SILENT, stateAfter.currentSoundMode)
        // Desired mode and overridden occurrence remain intact
        assertEquals(SoundMode.RING, stateAfter.desiredSoundMode)
        assertEquals(1, stateAfter.overriddenSchedules.size)
        // Overrides in repository are preserved
        val storedOverrides = repository.getOverridesForSchedule(id)
        assertEquals(1, storedOverrides.size)

        collectJob.cancel()
    }

    // REGRESSION F: Flow observation maintains reactivity without duplicate observers
    @Test
    fun home_flowObservation_reactsDeterministicallyWithoutDuplicates() = testScope.runTest {
        val schedule = Schedule(
            name = "Lab Work",
            startTime = LocalTime.of(14, 0),
            endTime = LocalTime.of(16, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        repository.insertSchedule(schedule)

        soundController.simulatedMode = SoundMode.SILENT
        val testInstant = baseDate.atTime(14, 30).atZone(zone).toInstant()
        reconciler.reconcile(testInstant, zone)

        // First collector
        val job1 = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        // Multiple refreshes
        viewModel.refresh()
        viewModel.refresh()
        advanceUntilIdle()

        // Transition mode
        soundController.simulatedMode = SoundMode.RING
        advanceUntilIdle()

        assertEquals(SoundMode.RING, viewModel.uiState.value.currentSoundMode)
        assertEquals(1, viewModel.uiState.value.schedules.size)

        job1.cancel()
    }
}

