package com.example.autosilent.domain.usecase

import com.example.autosilent.domain.repository.ScheduleRepository

class ToggleScheduleUseCase(
    private val repository: ScheduleRepository
) {
    suspend operator fun invoke(id: Long, enabled: Boolean): Result<Unit> {
        return try {
            repository.setScheduleEnabled(id, enabled)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
