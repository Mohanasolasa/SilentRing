package com.example.autosilent.ui.form

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autosilent.domain.model.RecurrenceType
import com.example.autosilent.domain.model.Schedule
import com.example.autosilent.domain.usecase.CreateScheduleUseCase
import com.example.autosilent.domain.usecase.GetScheduleByIdUseCase
import com.example.autosilent.domain.usecase.UpdateScheduleUseCase
import com.example.autosilent.domain.validation.ScheduleValidator
import com.example.autosilent.domain.validation.ValidationResult
import com.example.autosilent.scheduler.ScheduleReconciler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class ScheduleFormUiState(
    val scheduleId: Long? = null,
    val isEditMode: Boolean = false,
    val name: String = "",
    val startTime: LocalTime = LocalTime.of(9, 0),
    val endTime: LocalTime = LocalTime.of(17, 0),
    val recurrenceType: RecurrenceType = RecurrenceType.DAILY,
    val customDays: Set<DayOfWeek> = emptySet(),
    val startDate: LocalDate = LocalDate.now(),
    val hasExpiration: Boolean = false,
    val endDate: LocalDate? = null,
    val isOvernight: Boolean = false,
    val validationError: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false
)

class ScheduleFormViewModel(
    private val createScheduleUseCase: CreateScheduleUseCase,
    private val updateScheduleUseCase: UpdateScheduleUseCase,
    private val getScheduleByIdUseCase: GetScheduleByIdUseCase,
    private val reconciler: ScheduleReconciler,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScheduleFormUiState())
    val uiState: StateFlow<ScheduleFormUiState> = _uiState.asStateFlow()

    fun loadSchedule(id: Long) {
        _uiState.update { it.copy(isLoading = true, validationError = null) }
        viewModelScope.launch {
            val schedule = getScheduleByIdUseCase(id)
            if (schedule != null) {
                _uiState.update {
                    it.copy(
                        scheduleId = schedule.id,
                        isEditMode = true,
                        name = schedule.name,
                        startTime = schedule.startTime,
                        endTime = schedule.endTime,
                        recurrenceType = schedule.recurrenceType,
                        customDays = schedule.daysOfWeek,
                        startDate = schedule.startDate,
                        hasExpiration = schedule.endDate != null && schedule.recurrenceType != RecurrenceType.ONE_TIME,
                        endDate = schedule.endDate,
                        isOvernight = schedule.isOvernight,
                        isLoading = false,
                        validationError = null
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false, validationError = "Schedule not found.") }
            }
        }
    }

    fun onNameChange(name: String) {
        _uiState.update { it.copy(name = name, validationError = null) }
    }

    fun onStartTimeChange(time: LocalTime) {
        _uiState.update { current ->
            val isOvernight = !current.endTime.isAfter(time)
            current.copy(startTime = time, isOvernight = isOvernight, validationError = null)
        }
    }

    fun onEndTimeChange(time: LocalTime) {
        _uiState.update { current ->
            val isOvernight = !time.isAfter(current.startTime)
            current.copy(endTime = time, isOvernight = isOvernight, validationError = null)
        }
    }

    fun onRecurrenceChange(type: RecurrenceType) {
        _uiState.update { current ->
            val defaultDays = when (type) {
                RecurrenceType.CUSTOM_DAYS -> if (current.customDays.isEmpty()) setOf(DayOfWeek.MONDAY) else current.customDays
                else -> emptySet()
            }
            current.copy(
                recurrenceType = type,
                customDays = defaultDays,
                validationError = null
            )
        }
    }

    fun onToggleDay(day: DayOfWeek) {
        _uiState.update { current ->
            val updatedDays = if (day in current.customDays) {
                current.customDays - day
            } else {
                current.customDays + day
            }
            current.copy(customDays = updatedDays, validationError = null)
        }
    }

    fun onStartDateChange(date: LocalDate) {
        _uiState.update { it.copy(startDate = date, validationError = null) }
    }

    fun onToggleExpiration(hasExpiration: Boolean) {
        _uiState.update { current ->
            val newEndDate = if (hasExpiration) current.endDate ?: current.startDate.plusWeeks(1) else null
            current.copy(hasExpiration = hasExpiration, endDate = newEndDate, validationError = null)
        }
    }

    fun onEndDateChange(date: LocalDate?) {
        _uiState.update { it.copy(endDate = date, validationError = null) }
    }

    fun saveSchedule() {
        if (_uiState.value.isSaving || _uiState.value.isSaved) return
        val state = _uiState.value

        val scheduleToValidate = Schedule(
            id = state.scheduleId ?: 0,
            name = state.name.trim(),
            startTime = state.startTime,
            endTime = state.endTime,
            recurrenceType = state.recurrenceType,
            daysOfWeek = state.customDays,
            startDate = state.startDate,
            endDate = if (state.recurrenceType == RecurrenceType.ONE_TIME) state.startDate else if (state.hasExpiration) state.endDate else null
        )

        val validation = ScheduleValidator.validate(scheduleToValidate)
        if (validation is ValidationResult.Invalid) {
            _uiState.update { it.copy(validationError = validation.reason) }
            return
        }

        _uiState.update { it.copy(isSaving = true, validationError = null) }

        viewModelScope.launch {
            try {
                if (state.isEditMode && state.scheduleId != null) {
                    val result = updateScheduleUseCase(scheduleToValidate)
                    if (result.isSuccess) {
                        reconciler.reconcile(Instant.now(), zoneId)
                        _uiState.update { it.copy(isSaving = false, isSaved = true) }
                    } else {
                        _uiState.update { it.copy(isSaving = false, validationError = result.exceptionOrNull()?.message ?: "Update failed.") }
                    }
                } else {
                    val result = createScheduleUseCase(scheduleToValidate)
                    if (result.isSuccess) {
                        reconciler.reconcile(Instant.now(), zoneId)
                        _uiState.update { it.copy(isSaving = false, isSaved = true) }
                    } else {
                        _uiState.update { it.copy(isSaving = false, validationError = result.exceptionOrNull()?.message ?: "Creation failed.") }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, validationError = "Error saving schedule: ${e.message}") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(validationError = null) }
    }
}
