package com.example.autosilent.domain.usecase

import com.example.autosilent.domain.model.Schedule
import com.example.autosilent.domain.repository.ScheduleRepository
import com.example.autosilent.domain.validation.ScheduleValidator
import com.example.autosilent.domain.validation.ValidationResult

class CreateScheduleUseCase(
    private val repository: ScheduleRepository
) {
    suspend operator fun invoke(schedule: Schedule): Result<Long> {
        val validation = ScheduleValidator.validate(schedule)
        if (validation is ValidationResult.Invalid) {
            return Result.failure(IllegalArgumentException(validation.reason))
        }

        val normalized = ScheduleValidator.normalize(schedule)
        val id = repository.insertSchedule(normalized)
        return Result.success(id)
    }
}
