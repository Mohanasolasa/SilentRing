package com.example.autosilent.ui

import com.example.autosilent.domain.model.OccurrenceOverride
import com.example.autosilent.domain.model.RecurrenceType
import com.example.autosilent.domain.model.Schedule
import com.example.autosilent.domain.usecase.DeleteScheduleUseCase
import com.example.autosilent.domain.usecase.GetScheduleByIdUseCase
import com.example.autosilent.domain.usecase.ResumeScheduleUseCase
import com.example.autosilent.domain.usecase.ToggleScheduleUseCase
import com.example.autosilent.scheduler.ScheduleReconciler
import com.example.autosilent.ui.details.ScheduleDetailsViewModel
import com.example.autosilent.ui.fakes.FakeAlarmScheduler
import com.example.autosilent.ui.fakes.FakeScheduleRepository
import com.example.autosilent.ui.fakes.FakeSoundController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleDetailsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val zone = ZoneId.of("UTC")
    private val baseDate = LocalDate.of(2026, 10, 12)

    private lateinit var repository: FakeScheduleRepository
    private lateinit var reconciler: ScheduleReconciler
    private lateinit var viewModel: ScheduleDetailsViewModel

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

        viewModel = ScheduleDetailsViewModel(
            getSchedulesUseCase = com.example.autosilent.domain.usecase.GetSchedulesUseCase(repository),
            toggleScheduleUseCase = ToggleScheduleUseCase(repository),
            deleteScheduleUseCase = DeleteScheduleUseCase(repository),
            resumeScheduleUseCase = ResumeScheduleUseCase(repository),
            reconciler = reconciler,
            zoneId = zone
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun details_loadSchedule_success() = testScope.runTest {
        val schedule = Schedule(
            name = "Work Session",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(17, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        val collectJob = launch { viewModel.uiState.collect {} }
        viewModel.loadSchedule(id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.schedule)
        assertEquals("Work Session", state.schedule!!.name)
        assertNull(state.errorMessage)
        collectJob.cancel()
    }

    @Test
    fun details_loadNonExistentSchedule_showsError() = testScope.runTest {
        val collectJob = launch { viewModel.uiState.collect {} }
        viewModel.loadSchedule(999L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.schedule)
        assertNotNull(state.errorMessage)
        collectJob.cancel()
    }

    @Test
    fun details_toggleEnabled_updatesSchedule() = testScope.runTest {
        val schedule = Schedule(
            name = "Toggle Test",
            enabled = true,
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(17, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        val collectJob = launch { viewModel.uiState.collect {} }
        viewModel.loadSchedule(id)
        advanceUntilIdle()

        viewModel.toggleEnabled(false)
        advanceUntilIdle()

        val updated = repository.getScheduleById(id)
        assertNotNull(updated)
        assertFalse(updated!!.enabled)
        assertFalse(viewModel.uiState.value.schedule!!.enabled)
        collectJob.cancel()
    }

    @Test
    fun details_resumeSchedule_removesOverride() = testScope.runTest {
        val schedule = Schedule(
            name = "Overridden Schedule",
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
        viewModel.loadSchedule(id)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isOverridden)

        viewModel.resumeSchedule()
        advanceUntilIdle()

        assertTrue(repository.getOverridesForSchedule(id).isEmpty())
        collectJob.cancel()
    }

    @Test
    fun details_deleteSchedule_removesFromRepositoryAndSetsDeleted() = testScope.runTest {
        val schedule = Schedule(
            name = "Delete Me",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        val collectJob = launch { viewModel.uiState.collect {} }
        viewModel.loadSchedule(id)
        advanceUntilIdle()

        viewModel.deleteSchedule()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isDeleted)
        assertNull(repository.getScheduleById(id))
        collectJob.cancel()
    }

    @Test
    fun details_rapidDelete_onlyDeletesOnce() = testScope.runTest {
        val schedule = Schedule(
            name = "Rapid Delete",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        val collectJob = launch { viewModel.uiState.collect {} }
        viewModel.loadSchedule(id)
        advanceUntilIdle()

        viewModel.deleteSchedule()
        viewModel.deleteSchedule()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isDeleted)
        assertNull(repository.getScheduleById(id))
        collectJob.cancel()
    }
}
