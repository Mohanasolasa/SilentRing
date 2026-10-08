package com.example.autosilent.domain.usecase

import com.example.autosilent.domain.repository.ScheduleRepository

class DeleteScheduleUseCase(
    private val repository: ScheduleRepository
) {
    suspend operator fun invoke(id: Long): Result<Unit> {
        return try {
            repository.deleteSchedule(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
