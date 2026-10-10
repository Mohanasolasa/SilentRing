package com.example.autosilent.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autosilent.domain.model.Schedule
import com.example.autosilent.domain.usecase.DeleteScheduleUseCase
import com.example.autosilent.domain.usecase.GetSchedulesUseCase
import com.example.autosilent.domain.usecase.ResumeScheduleUseCase
import com.example.autosilent.domain.usecase.ToggleScheduleUseCase
import com.example.autosilent.scheduler.ScheduleReconciler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

data class ScheduleDetailsUiState(
    val schedule: Schedule? = null,
    val isActive: Boolean = false,
    val isOverridden: Boolean = false,
    val isLoading: Boolean = false,
    val isDeleted: Boolean = false,
    val errorMessage: String? = null
)

class ScheduleDetailsViewModel(
    private val getSchedulesUseCase: GetSchedulesUseCase,
    private val toggleScheduleUseCase: ToggleScheduleUseCase,
    private val deleteScheduleUseCase: DeleteScheduleUseCase,
    private val resumeScheduleUseCase: ResumeScheduleUseCase,
    private val reconciler: ScheduleReconciler,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) : ViewModel() {

    private val _scheduleId = MutableStateFlow<Long?>(null)
    private val _isDeleted = MutableStateFlow(false)
    private val _isDeleting = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ScheduleDetailsUiState> = combine(
        _scheduleId,
        getSchedulesUseCase(),
        reconciler.currentEvaluation,
        _isDeleted,
        _errorMessage
    ) { id, schedules, evaluation, isDeleted, error ->
        if (id == null) {
            ScheduleDetailsUiState(isLoading = true)
        } else {
            val schedule = schedules.find { it.id == id }
            val isActive = evaluation?.effectiveOccurrences?.any { it.scheduleId == id } == true
            val isOverridden = evaluation?.overriddenOccurrences?.any { it.scheduleId == id } == true
            ScheduleDetailsUiState(
                schedule = schedule,
                isActive = isActive,
                isOverridden = isOverridden,
                isLoading = false,
                isDeleted = isDeleted,
                errorMessage = if (schedule == null && !isDeleted) "Schedule not found." else error
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ScheduleDetailsUiState(isLoading = true)
    )

    fun loadSchedule(id: Long) {
        _scheduleId.value = id
    }

    fun toggleEnabled(enabled: Boolean) {
        val scheduleId = _scheduleId.value ?: return
        viewModelScope.launch {
            val result = toggleScheduleUseCase(scheduleId, enabled)
            if (result.isSuccess) {
                reconciler.reconcile(Instant.now(), zoneId)
            } else {
                _errorMessage.value = result.exceptionOrNull()?.message ?: "Toggle failed"
            }
        }
    }

    fun resumeSchedule() {
        val scheduleId = _scheduleId.value ?: return
        viewModelScope.launch {
            val result = resumeScheduleUseCase(scheduleId)
            if (result.isSuccess) {
                reconciler.reconcile(Instant.now(), zoneId)
            } else {
                _errorMessage.value = result.exceptionOrNull()?.message ?: "Resume failed"
            }
        }
    }

    fun deleteSchedule() {
        if (_isDeleted.value || _isDeleting.value) return
        val scheduleId = _scheduleId.value ?: return
        _isDeleting.value = true
        viewModelScope.launch {
            try {
                val result = deleteScheduleUseCase(scheduleId)
                if (result.isSuccess) {
                    reconciler.reconcile(Instant.now(), zoneId)
                    _isDeleted.value = true
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Delete failed"
                }
            } finally {
                _isDeleting.value = false
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
