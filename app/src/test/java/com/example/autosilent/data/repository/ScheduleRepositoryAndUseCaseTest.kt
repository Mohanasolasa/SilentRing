package com.example.autosilent.data.repository

import com.example.autosilent.data.local.dao.OccurrenceOverrideDao
import com.example.autosilent.data.local.dao.ScheduleDao
import com.example.autosilent.data.local.entity.OccurrenceOverrideEntity
import com.example.autosilent.data.local.entity.ScheduleEntity
import com.example.autosilent.domain.model.OccurrenceOverride
import com.example.autosilent.domain.model.RecurrenceType
import com.example.autosilent.domain.model.Schedule
import com.example.autosilent.domain.usecase.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicLong

class ScheduleRepositoryAndUseCaseTest {

    private lateinit var fakeScheduleDao: FakeScheduleDao
    private lateinit var fakeOverrideDao: FakeOccurrenceOverrideDao
    private lateinit var repository: ScheduleRepositoryImpl

    private lateinit var createScheduleUseCase: CreateScheduleUseCase
    private lateinit var updateScheduleUseCase: UpdateScheduleUseCase
    private lateinit var deleteScheduleUseCase: DeleteScheduleUseCase
    private lateinit var toggleScheduleUseCase: ToggleScheduleUseCase
    private lateinit var getSchedulesUseCase: GetSchedulesUseCase
    private lateinit var recordManualOverrideUseCase: RecordManualOverrideUseCase

    private val baseDate = LocalDate.of(2026, 10, 12) // Monday

    @Before
    fun setUp() {
        fakeScheduleDao = FakeScheduleDao()
        fakeOverrideDao = FakeOccurrenceOverrideDao()
        repository = ScheduleRepositoryImpl(fakeScheduleDao, fakeOverrideDao)

        createScheduleUseCase = CreateScheduleUseCase(repository)
        updateScheduleUseCase = UpdateScheduleUseCase(repository)
        deleteScheduleUseCase = DeleteScheduleUseCase(repository)
        toggleScheduleUseCase = ToggleScheduleUseCase(repository)
        getSchedulesUseCase = GetSchedulesUseCase(repository)
        recordManualOverrideUseCase = RecordManualOverrideUseCase(repository)
    }

    @Test
    fun createSchedule_valid_persistsAndReturnsId() = runBlocking {
        val schedule = Schedule(
            name = "Study",
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(10, 0),
            recurrenceType = RecurrenceType.WEEKDAYS,
            startDate = baseDate
        )

        val result = createScheduleUseCase(schedule)
        assertTrue(result.isSuccess)
        val createdId = result.getOrThrow()
        assertTrue(createdId > 0)

        val retrieved = repository.getScheduleById(createdId)
        assertNotNull(retrieved)
        assertEquals("Study", retrieved?.name)
        assertEquals(LocalTime.of(7, 0), retrieved?.startTime)
        assertEquals(LocalTime.of(10, 0), retrieved?.endTime)
        assertTrue(retrieved!!.daysOfWeek.contains(DayOfWeek.MONDAY))
    }

    @Test
    fun createSchedule_invalid_returnsFailure() = runBlocking {
        val invalidSchedule = Schedule(
            name = "",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(9, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )

        val result = createScheduleUseCase(invalidSchedule)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun updateSchedule_modifiesEntity() = runBlocking {
        val schedule = Schedule(
            name = "Study",
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(10, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        val updated = schedule.copy(id = id, name = "Deep Study", endTime = LocalTime.of(11, 0))
        val result = updateScheduleUseCase(updated)
        assertTrue(result.isSuccess)

        val retrieved = repository.getScheduleById(id)
        assertEquals("Deep Study", retrieved?.name)
        assertEquals(LocalTime.of(11, 0), retrieved?.endTime)
    }

    @Test
    fun updateSchedule_prunesOverrides_whenTimesChange() = runBlocking {
        val schedule = Schedule(
            name = "Study",
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(10, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        val startInstant = Instant.parse("2026-10-12T07:00:00Z")
        val endInstant = Instant.parse("2026-10-12T10:00:00Z")
        repository.recordOccurrenceOverride(
            OccurrenceOverride(scheduleId = id, occurrenceStart = startInstant, occurrenceEnd = endInstant)
        )
        assertEquals(1, repository.getOverridesForSchedule(id).size)

        // Change start time -> old overrides must be pruned
        val updated = schedule.copy(id = id, startTime = LocalTime.of(8, 0))
        updateScheduleUseCase(updated)

        assertEquals(0, repository.getOverridesForSchedule(id).size)
    }

    @Test
    fun deleteSchedule_removesScheduleAndCascadesOverrides() = runBlocking {
        val schedule = Schedule(
            name = "Meeting",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(10, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )
        val id = repository.insertSchedule(schedule)

        val startInstant = Instant.parse("2026-10-12T09:00:00Z")
        val endInstant = Instant.parse("2026-10-12T10:00:00Z")
        repository.recordOccurrenceOverride(
            OccurrenceOverride(scheduleId = id, occurrenceStart = startInstant, occurrenceEnd = endInstant)
        )

        deleteScheduleUseCase(id)

        assertNull(repository.getScheduleById(id))
        assertEquals(0, repository.getOverridesForSchedule(id).size)
    }

    @Test
    fun toggleSchedule_enablesAndDisables() = runBlocking {
        val schedule = Schedule(
            name = "Workout",
            startTime = LocalTime.of(18, 0),
            endTime = LocalTime.of(19, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate,
            enabled = true
        )
        val id = repository.insertSchedule(schedule)

        toggleScheduleUseCase(id, enabled = false)
        assertFalse(repository.getScheduleById(id)!!.enabled)

        toggleScheduleUseCase(id, enabled = true)
        assertTrue(repository.getScheduleById(id)!!.enabled)
    }

    @Test
    fun retrieveSchedules_reactivelyFlows() = runBlocking {
        val initialList = getSchedulesUseCase().first()
        assertTrue(initialList.isEmpty())

        createScheduleUseCase(
            Schedule(
                name = "A",
                startTime = LocalTime.of(1, 0),
                endTime = LocalTime.of(2, 0),
                recurrenceType = RecurrenceType.DAILY,
                startDate = baseDate
            )
        )

        val updatedList = getSchedulesUseCase().first()
        assertEquals(1, updatedList.size)
        assertEquals("A", updatedList[0].name)
    }

    @Test
    fun occurrenceManualOverride_belongsOnlyToExactOccurrence() = runBlocking {
        // Schedule: Study (Mon-Fri 07:00-10:00)
        val schedule = Schedule(
            name = "Study",
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(10, 0),
            recurrenceType = RecurrenceType.WEEKDAYS,
            startDate = baseDate
        )
        val scheduleId = repository.insertSchedule(schedule)

        // Monday occurrence: 2026-10-12 07:00 -> 10:00 UTC
        val mondayStart = Instant.parse("2026-10-12T07:00:00Z")
        val mondayEnd = Instant.parse("2026-10-12T10:00:00Z")

        // Tuesday occurrence: 2026-10-13 07:00 -> 10:00 UTC
        val tuesdayStart = Instant.parse("2026-10-13T07:00:00Z")
        val tuesdayEnd = Instant.parse("2026-10-13T10:00:00Z")
        assertTrue(tuesdayEnd.isAfter(tuesdayStart))

        // User overrides Monday at 08:00
        val overrideResult = recordManualOverrideUseCase(scheduleId, mondayStart, mondayEnd)
        assertTrue(overrideResult.isSuccess)

        val activeOverridesMonday = repository.getActiveOverrides(Instant.parse("2026-10-12T08:00:00Z"))
        assertEquals(1, activeOverridesMonday.size)
        assertEquals(mondayStart, activeOverridesMonday[0].occurrenceStart)

        // Verify Monday override does NOT match Tuesday occurrence
        val matchesTuesday = activeOverridesMonday.any {
            it.scheduleId == scheduleId && it.occurrenceStart == tuesdayStart
        }
        assertFalse(matchesTuesday)

        // Verify Tuesday at 08:00: Monday's override has expired!
        val activeOverridesTuesday = repository.getActiveOverrides(Instant.parse("2026-10-13T08:00:00Z"))
        assertTrue(activeOverridesTuesday.isEmpty())

        // Verify recurring schedule definition remains completely unchanged
        val persistentSchedule = repository.getScheduleById(scheduleId)!!
        assertTrue(persistentSchedule.enabled)
        assertEquals("Study", persistentSchedule.name)
        assertEquals(RecurrenceType.WEEKDAYS, persistentSchedule.recurrenceType)
    }

    // --- Fakes for In-Memory Unit Testing ---

    private class FakeScheduleDao : ScheduleDao {
        private val idGen = AtomicLong(1)
        private val schedules = LinkedHashMap<Long, ScheduleEntity>()
        private val flow = MutableStateFlow<List<ScheduleEntity>>(emptyList())

        private fun emit() {
            flow.value = schedules.values.sortedBy { it.startTime }.toList()
        }

        override suspend fun insert(schedule: ScheduleEntity): Long {
            val id = if (schedule.id == 0L) idGen.getAndIncrement() else schedule.id
            schedules[id] = schedule.copy(id = id)
            emit()
            return id
        }

        override suspend fun update(schedule: ScheduleEntity) {
            schedules[schedule.id] = schedule
            emit()
        }

        override suspend fun delete(schedule: ScheduleEntity) {
            schedules.remove(schedule.id)
            emit()
        }

        override suspend fun deleteById(id: Long): Int {
            val removed = schedules.remove(id) != null
            if (removed) emit()
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
            emit()
        }
    }

    private class FakeOccurrenceOverrideDao : OccurrenceOverrideDao {
        private val idGen = AtomicLong(1)
        private val overrides = LinkedHashMap<Long, OccurrenceOverrideEntity>()
        private val flow = MutableStateFlow<List<OccurrenceOverrideEntity>>(emptyList())

        private fun emit() {
            flow.value = overrides.values.toList()
        }

        override suspend fun insert(override: OccurrenceOverrideEntity): Long {
            val id = if (override.id == 0L) idGen.getAndIncrement() else override.id
            overrides[id] = override.copy(id = id)
            emit()
            return id
        }

        override suspend fun getAllOverrides(): List<OccurrenceOverrideEntity> =
            overrides.values.toList()

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
            if (toRemove.isNotEmpty()) emit()
            return toRemove.size
        }

        override suspend fun deleteByScheduleId(scheduleId: Long): Int {
            val toRemove = overrides.filterValues { it.scheduleId == scheduleId }.keys
            toRemove.forEach { overrides.remove(it) }
            if (toRemove.isNotEmpty()) emit()
            return toRemove.size
        }

        override suspend fun deleteByScheduleAndOccurrence(
            scheduleId: Long,
            occurrenceStart: Instant
        ): Int {
            val toRemove = overrides.filterValues {
                it.scheduleId == scheduleId && it.occurrenceStart == occurrenceStart
            }.keys
            toRemove.forEach { overrides.remove(it) }
            if (toRemove.isNotEmpty()) emit()
            return toRemove.size
        }
    }
}
