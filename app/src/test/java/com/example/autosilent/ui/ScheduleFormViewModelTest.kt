package com.example.autosilent.ui

import com.example.autosilent.domain.model.RecurrenceType
import com.example.autosilent.domain.model.Schedule
import com.example.autosilent.domain.usecase.CreateScheduleUseCase
import com.example.autosilent.domain.usecase.GetScheduleByIdUseCase
import com.example.autosilent.domain.usecase.UpdateScheduleUseCase
import com.example.autosilent.scheduler.ScheduleReconciler
import com.example.autosilent.ui.fakes.FakeAlarmScheduler
import com.example.autosilent.ui.fakes.FakeScheduleRepository
import com.example.autosilent.ui.fakes.FakeSoundController
import com.example.autosilent.ui.form.ScheduleFormViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleFormViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val zone = ZoneId.of("UTC")
    private val baseDate = LocalDate.of(2026, 10, 12)

    private lateinit var repository: FakeScheduleRepository
    private lateinit var reconciler: ScheduleReconciler
    private lateinit var viewModel: ScheduleFormViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeScheduleRepository()
        val alarmScheduler = FakeAlarmScheduler()
        val soundController = FakeSoundController()
        reconciler = ScheduleReconciler(
            repository = repository,
            alarmScheduler = alarmScheduler,
            soundController = soundController
        )

        viewModel = ScheduleFormViewModel(
            createScheduleUseCase = CreateScheduleUseCase(repository),
            updateScheduleUseCase = UpdateScheduleUseCase(repository),
            getScheduleByIdUseCase = GetScheduleByIdUseCase(repository),
            reconciler = reconciler,
            zoneId = zone
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // 2. Create success
    @Test
    fun form_createSchedule_success() = testScope.runTest {
        viewModel.onNameChange("New Study")
        viewModel.onStartTimeChange(LocalTime.of(8, 0))
        viewModel.onEndTimeChange(LocalTime.of(10, 0))
        viewModel.onRecurrenceChange(RecurrenceType.DAILY)
        viewModel.onStartDateChange(baseDate)

        viewModel.saveSchedule()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isSaved)
        assertNull(state.validationError)

        val createdList = repository.getEnabledSchedules()
        assertEquals(1, createdList.size)
        assertEquals("New Study", createdList.first().name)
    }

    // 3. Create validation failure - Empty name
    @Test
    fun form_createSchedule_emptyName_failsValidation() = testScope.runTest {
        viewModel.onNameChange("")
        viewModel.saveSchedule()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSaved)
        assertNotNull(state.validationError)
        assertTrue(state.validationError!!.contains("name cannot be empty"))
    }

    // 3. Create validation failure - Identical start/end time
    @Test
    fun form_createSchedule_identicalTimes_failsValidation() = testScope.runTest {
        viewModel.onNameChange("Same Time")
        viewModel.onStartTimeChange(LocalTime.of(9, 0))
        viewModel.onEndTimeChange(LocalTime.of(9, 0))

        viewModel.saveSchedule()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSaved)
        assertNotNull(state.validationError)
        assertTrue(state.validationError!!.contains("identical"))
    }

    // 3. Create validation failure - Expiration date before start date
    @Test
    fun form_createSchedule_expirationBeforeStart_failsValidation() = testScope.runTest {
        viewModel.onNameChange("Bad Dates")
        viewModel.onStartDateChange(baseDate)
        viewModel.onToggleExpiration(true)
        viewModel.onEndDateChange(baseDate.minusDays(2))

        viewModel.saveSchedule()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSaved)
        assertNotNull(state.validationError)
        assertTrue(state.validationError!!.contains("earlier than start date"))
    }

    // 4. Edit success
    @Test
    fun form_editSchedule_preloadsAndUpdatesSuccessfully() = testScope.runTest {
        val existing = Schedule(
            name = "Original Name",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(12, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(existing)

        // Load into ViewModel
        viewModel.loadSchedule(id)
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertTrue(state.isEditMode)
        assertEquals("Original Name", state.name)
        assertEquals(LocalTime.of(9, 0), state.startTime)

        // Modify name and time
        viewModel.onNameChange("Updated Name")
        viewModel.onEndTimeChange(LocalTime.of(13, 0))

        viewModel.saveSchedule()
        advanceUntilIdle()

        state = viewModel.uiState.value
        assertTrue(state.isSaved)

        val updated = repository.getScheduleById(id)
        assertNotNull(updated)
        assertEquals("Updated Name", updated!!.name)
        assertEquals(LocalTime.of(13, 0), updated.endTime)
    }

    // Overnight schedule support
    @Test
    fun form_overnightSchedule_detectedAndSavedSuccessfully() = testScope.runTest {
        viewModel.onNameChange("Night Shift")
        viewModel.onStartTimeChange(LocalTime.of(22, 0))
        viewModel.onEndTimeChange(LocalTime.of(6, 0))
        viewModel.onRecurrenceChange(RecurrenceType.DAILY)
        viewModel.onStartDateChange(baseDate)

        assertTrue(viewModel.uiState.value.isOvernight)

        viewModel.saveSchedule()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isSaved)
        assertNull(state.validationError)

        val saved = repository.getEnabledSchedules().first()
        assertTrue(saved.isOvernight)
    }

    // Custom days toggle
    @Test
    fun form_customDays_toggleAndSave() = testScope.runTest {
        viewModel.onNameChange("MWF Class")
        viewModel.onRecurrenceChange(RecurrenceType.CUSTOM_DAYS)
        viewModel.onToggleDay(DayOfWeek.WEDNESDAY)
        viewModel.onToggleDay(DayOfWeek.FRIDAY)

        val state = viewModel.uiState.value
        assertTrue(DayOfWeek.MONDAY in state.customDays)
        assertTrue(DayOfWeek.WEDNESDAY in state.customDays)
        assertTrue(DayOfWeek.FRIDAY in state.customDays)

        viewModel.saveSchedule()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSaved)
        val saved = repository.getEnabledSchedules().first()
        assertEquals(3, saved.daysOfWeek.size)
    }

    @Test
    fun form_rapidSave_onlySavesOnce() = testScope.runTest {
        viewModel.onNameChange("Rapid Task")
        viewModel.onStartTimeChange(LocalTime.of(10, 0))
        viewModel.onEndTimeChange(LocalTime.of(11, 0))

        // Multiple rapid invocations before advanceUntilIdle
        viewModel.saveSchedule()
        viewModel.saveSchedule()
        viewModel.saveSchedule()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSaved)
        val allSchedules = repository.getEnabledSchedules()
        assertEquals(1, allSchedules.size)
    }

    @Test
    fun form_loadSchedule_setsLoadingThenClears() = testScope.runTest {
        val schedule = Schedule(
            name = "Test Loading",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(10, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        viewModel.loadSchedule(id)
        // Before idle, check loading state triggered
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Test Loading", state.name)
    }
}
