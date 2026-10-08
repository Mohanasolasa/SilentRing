package com.example.autosilent.domain.usecase

import com.example.autosilent.domain.model.Schedule
import com.example.autosilent.domain.repository.ScheduleRepository
import com.example.autosilent.domain.validation.ScheduleValidator
import com.example.autosilent.domain.validation.ValidationResult

class UpdateScheduleUseCase(
    private val repository: ScheduleRepository
) {
    suspend operator fun invoke(schedule: Schedule): Result<Unit> {
        val validation = ScheduleValidator.validate(schedule)
        if (validation is ValidationResult.Invalid) {
            return Result.failure(IllegalArgumentException(validation.reason))
        }

        val existing = repository.getScheduleById(schedule.id)
        val normalized = ScheduleValidator.normalize(schedule)

        // If the schedule times or recurrence pattern changed, old occurrence overrides are invalid
        if (existing != null && (
                existing.startTime != normalized.startTime ||
                existing.endTime != normalized.endTime ||
                existing.recurrenceType != normalized.recurrenceType ||
                existing.daysOfWeek != normalized.daysOfWeek
            )
        ) {
            repository.deleteOverridesForSchedule(schedule.id)
        }

        repository.updateSchedule(normalized)
        return Result.success(Unit)
    }
}
