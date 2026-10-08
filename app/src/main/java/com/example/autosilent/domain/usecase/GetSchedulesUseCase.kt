package com.example.autosilent.domain.usecase

import com.example.autosilent.domain.model.Schedule
import com.example.autosilent.domain.repository.ScheduleRepository
import kotlinx.coroutines.flow.Flow

class GetSchedulesUseCase(
    private val repository: ScheduleRepository
) {
    operator fun invoke(): Flow<List<Schedule>> = repository.getSchedulesFlow()
}
