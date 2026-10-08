package com.example.autosilent.domain.usecase

import com.example.autosilent.domain.model.OccurrenceOverride
import com.example.autosilent.domain.repository.ScheduleRepository
import java.time.Instant

class RecordManualOverrideUseCase(
    private val repository: ScheduleRepository
) {
    suspend operator fun invoke(
        scheduleId: Long,
        occurrenceStart: Instant,
        occurrenceEnd: Instant
    ): Result<Long> {
        if (!occurrenceEnd.isAfter(occurrenceStart)) {
            return Result.failure(IllegalArgumentException("Occurrence end must be after occurrence start."))
        }

        val override = OccurrenceOverride(
            scheduleId = scheduleId,
            occurrenceStart = occurrenceStart,
            occurrenceEnd = occurrenceEnd,
            createdAt = Instant.now()
        )
        return try {
            val id = repository.recordOccurrenceOverride(override)
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
