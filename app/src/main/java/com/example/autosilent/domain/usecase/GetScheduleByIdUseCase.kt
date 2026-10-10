package com.example.autosilent.domain.usecase

import com.example.autosilent.domain.model.Schedule
import com.example.autosilent.domain.repository.ScheduleRepository

class GetScheduleByIdUseCase(
    private val repository: ScheduleRepository
) {
    suspend operator fun invoke(id: Long): Schedule? = repository.getScheduleById(id)
}
