package com.example.autosilent.ui.home

import com.example.autosilent.domain.model.BoundaryEvent
import com.example.autosilent.domain.model.RecurrenceType
import com.example.autosilent.domain.model.ScheduleOccurrence
import com.example.autosilent.domain.model.SoundMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

data class ScheduleUiModel(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val recurrenceType: RecurrenceType,
    val daysOfWeek: Set<DayOfWeek>,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val isOvernight: Boolean,
    val timeRangeFormatted: String,
    val recurrenceFormatted: String,
    val expirationFormatted: String?,
    val isActive: Boolean,
    val isOverridden: Boolean
)

data class HomeUiState(
    val schedules: List<ScheduleUiModel> = emptyList(),
    val currentSoundMode: SoundMode = SoundMode.RING,
    val desiredSoundMode: SoundMode = SoundMode.RING,
    val activeSchedules: List<ScheduleOccurrence> = emptyList(),
    val activeScheduleCount: Int = 0,
    val overriddenSchedules: List<ScheduleOccurrence> = emptyList(),
    val nextBoundary: BoundaryEvent? = null,
    val isExactAlarmGranted: Boolean = true,
    val isSoundControlGranted: Boolean = true,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
