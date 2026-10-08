package com.example.autosilent.data.repository

import com.example.autosilent.data.local.dao.OccurrenceOverrideDao
import com.example.autosilent.data.local.dao.ScheduleDao
import com.example.autosilent.data.local.entity.OccurrenceOverrideEntity
import com.example.autosilent.data.local.entity.ScheduleEntity
import com.example.autosilent.domain.model.OccurrenceOverride
import com.example.autosilent.domain.model.Schedule
import com.example.autosilent.domain.repository.ScheduleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

class ScheduleRepositoryImpl(
    private val scheduleDao: ScheduleDao,
    private val occurrenceOverrideDao: OccurrenceOverrideDao
) : ScheduleRepository {

    override fun getSchedulesFlow(): Flow<List<Schedule>> =
        scheduleDao.getAllSchedulesFlow().map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun getScheduleById(id: Long): Schedule? =
        scheduleDao.getScheduleById(id)?.toDomain()

    override suspend fun getEnabledSchedules(): List<Schedule> =
        scheduleDao.getEnabledSchedules().map { it.toDomain() }

    override fun getEnabledSchedulesFlow(): Flow<List<Schedule>> =
        scheduleDao.getEnabledSchedulesFlow().map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun insertSchedule(schedule: Schedule): Long {
        val entity = schedule.toEntity()
        return scheduleDao.insert(entity)
    }

    override suspend fun updateSchedule(schedule: Schedule) {
        scheduleDao.update(schedule.toEntity(updatedAt = Instant.now()))
    }

    override suspend fun deleteSchedule(id: Long) {
        scheduleDao.deleteById(id)
        // With foreign key cascade, Room removes child overrides automatically when SQLite foreign keys are active.
        // We also explicitly call deleteByScheduleId to guarantee complete override cleanup.
        occurrenceOverrideDao.deleteByScheduleId(id)
    }

    override suspend fun setScheduleEnabled(id: Long, enabled: Boolean) {
        scheduleDao.setEnabled(id, enabled, Instant.now())
        if (!enabled) {
            // When disabling a schedule, clear any active overrides for it
            occurrenceOverrideDao.deleteByScheduleId(id)
        }
    }

    override suspend fun recordOccurrenceOverride(override: OccurrenceOverride): Long {
        return occurrenceOverrideDao.insert(override.toEntity())
    }

    override suspend fun getOverridesForSchedule(scheduleId: Long): List<OccurrenceOverride> =
        occurrenceOverrideDao.getOverridesForSchedule(scheduleId).map { it.toDomain() }

    override suspend fun getActiveOverrides(now: Instant): List<OccurrenceOverride> =
        occurrenceOverrideDao.getActiveOverrides(now).map { it.toDomain() }

    override fun getActiveOverridesFlow(now: Instant): Flow<List<OccurrenceOverride>> =
        occurrenceOverrideDao.getActiveOverridesFlow(now).map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun pruneExpiredOverrides(now: Instant): Int =
        occurrenceOverrideDao.deleteExpiredOverrides(now)

    override suspend fun deleteOverridesForSchedule(scheduleId: Long): Int =
        occurrenceOverrideDao.deleteByScheduleId(scheduleId)

    // Private Entity <-> Domain Mappers
    private fun ScheduleEntity.toDomain(): Schedule = Schedule(
        id = id,
        name = name,
        enabled = enabled,
        startTime = startTime,
        endTime = endTime,
        recurrenceType = recurrenceType,
        daysOfWeek = daysOfWeek,
        startDate = startDate,
        endDate = endDate,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun Schedule.toEntity(updatedAt: Instant = this.updatedAt): ScheduleEntity = ScheduleEntity(
        id = id,
        name = name,
        enabled = enabled,
        startTime = startTime,
        endTime = endTime,
        recurrenceType = recurrenceType,
        daysOfWeek = daysOfWeek,
        startDate = startDate,
        endDate = endDate,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun OccurrenceOverrideEntity.toDomain(): OccurrenceOverride = OccurrenceOverride(
        id = id,
        scheduleId = scheduleId,
        occurrenceStart = occurrenceStart,
        occurrenceEnd = occurrenceEnd,
        createdAt = createdAt
    )

    private fun OccurrenceOverride.toEntity(): OccurrenceOverrideEntity = OccurrenceOverrideEntity(
        id = id,
        scheduleId = scheduleId,
        occurrenceStart = occurrenceStart,
        occurrenceEnd = occurrenceEnd,
        createdAt = createdAt
    )
}
