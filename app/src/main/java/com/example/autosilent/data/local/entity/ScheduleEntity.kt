package com.example.autosilent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.autosilent.domain.model.RecurrenceType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val recurrenceType: RecurrenceType,
    val daysOfWeek: Set<DayOfWeek>,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
