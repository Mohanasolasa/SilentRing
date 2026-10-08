package com.example.autosilent.domain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class Schedule(
    val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val recurrenceType: RecurrenceType,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    val isOvernight: Boolean
        get() = !endTime.isAfter(startTime) // endTime <= startTime (e.g. 22:00 to 06:00)
}

data class OccurrenceOverride(
    val id: Long = 0,
    val scheduleId: Long,
    val occurrenceStart: Instant,
    val occurrenceEnd: Instant,
    val createdAt: Instant = Instant.now()
)
