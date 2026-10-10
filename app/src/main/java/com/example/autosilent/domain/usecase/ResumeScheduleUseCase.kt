package com.example.autosilent.domain.usecase

import com.example.autosilent.domain.repository.ScheduleRepository
import java.time.Instant

class ResumeScheduleUseCase(
    private val repository: ScheduleRepository
) {
    suspend operator fun invoke(scheduleId: Long, occurrenceStart: Instant? = null): Result<Unit> {
        return try {
            if (occurrenceStart != null) {
                repository.deleteOverride(scheduleId, occurrenceStart)
            } else {
                repository.deleteOverridesForSchedule(scheduleId)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
