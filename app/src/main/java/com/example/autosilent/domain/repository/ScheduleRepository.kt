package com.example.autosilent.domain.repository

import com.example.autosilent.domain.model.OccurrenceOverride
import com.example.autosilent.domain.model.Schedule
import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface ScheduleRepository {
    fun getSchedulesFlow(): Flow<List<Schedule>>
    suspend fun getScheduleById(id: Long): Schedule?
    suspend fun getEnabledSchedules(): List<Schedule>
    fun getEnabledSchedulesFlow(): Flow<List<Schedule>>
    suspend fun insertSchedule(schedule: Schedule): Long
    suspend fun updateSchedule(schedule: Schedule)
    suspend fun deleteSchedule(id: Long)
    suspend fun setScheduleEnabled(id: Long, enabled: Boolean)

    // Occurrence-scoped manual overrides
    suspend fun recordOccurrenceOverride(override: OccurrenceOverride): Long
    suspend fun getOverridesForSchedule(scheduleId: Long): List<OccurrenceOverride>
    suspend fun getActiveOverrides(now: Instant): List<OccurrenceOverride>
    fun getActiveOverridesFlow(now: Instant): Flow<List<OccurrenceOverride>>
    suspend fun pruneExpiredOverrides(now: Instant): Int
    suspend fun deleteOverridesForSchedule(scheduleId: Long): Int
}
