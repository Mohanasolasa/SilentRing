package com.example.autosilent.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autosilent.domain.controller.SoundController
import com.example.autosilent.domain.model.Schedule
import com.example.autosilent.domain.model.ScheduleEvaluation
import com.example.autosilent.domain.model.SoundMode
import com.example.autosilent.domain.usecase.DeleteScheduleUseCase
import com.example.autosilent.domain.usecase.GetSchedulesUseCase
import com.example.autosilent.domain.usecase.ResumeScheduleUseCase
import com.example.autosilent.domain.usecase.ToggleScheduleUseCase
import com.example.autosilent.scheduler.AlarmScheduler
import com.example.autosilent.scheduler.ScheduleReconciler
import com.example.autosilent.scheduler.DiagnosticLogger
import com.example.autosilent.ui.util.DateTimeUiFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

class HomeViewModel(
    private val getSchedulesUseCase: GetSchedulesUseCase,
    private val toggleScheduleUseCase: ToggleScheduleUseCase,
    private val deleteScheduleUseCase: DeleteScheduleUseCase,
    private val resumeScheduleUseCase: ResumeScheduleUseCase,
    private val reconciler: ScheduleReconciler,
    private val alarmScheduler: AlarmScheduler,
    private val soundController: SoundController,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) : ViewModel() {

    private data class SystemRefreshState(
        val isExactAlarmGranted: Boolean,
        val isSoundControlGranted: Boolean,
        val refreshTick: Long = 0L
    )

    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _systemRefreshState = MutableStateFlow(
        SystemRefreshState(
            isExactAlarmGranted = alarmScheduler.canScheduleExactAlarms(),
            isSoundControlGranted = soundController.canControlSound()
        )
    )

    private var lastObservedRingerMode: SoundMode? = null

    val uiState: StateFlow<HomeUiState> = combine(
        getSchedulesUseCase(),
        reconciler.currentEvaluation,
        soundController.observeCurrentMode(),
        _systemRefreshState,
        _errorMessage
    ) { schedules, evaluation, observedMode, refreshState, error ->
        buildUiState(
            schedules = schedules,
            evaluation = evaluation,
            observedMode = observedMode,
            exactAlarm = refreshState.isExactAlarmGranted,
            soundControl = refreshState.isSoundControlGranted,
            error = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState(isLoading = true)
    )

    init {
        refresh()
        // If global reconciliation evaluation is not yet initialized, reconcile once on cold launch
        if (reconciler.currentEvaluation.value == null) {
            viewModelScope.launch {
                try {
                    reconciler.reconcile(Instant.now(), zoneId)
                } catch (e: Exception) {
                    _errorMessage.value = "Failed to initialize state: ${e.message}"
                }
            }
        }
    }

    fun refresh() {
        val actualMode = soundController.getCurrentMode()
        val exactAlarm = alarmScheduler.canScheduleExactAlarms()
        val soundControl = soundController.canControlSound()
        DiagnosticLogger.i(
            "AutoSilentDiag",
            "HOME_VIEWMODEL_REFRESH_INVOKED: actualAudioManagerMode=$actualMode, exactAlarm=$exactAlarm, soundControl=$soundControl"
        )
        _systemRefreshState.update { prev ->
            SystemRefreshState(
                isExactAlarmGranted = exactAlarm,
                isSoundControlGranted = soundControl,
                refreshTick = prev.refreshTick + 1
            )
        }
    }

    fun toggleSchedule(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            val result = toggleScheduleUseCase(id, enabled)
            if (result.isSuccess) {
                try {
                    reconciler.reconcile(Instant.now(), zoneId)
                } catch (e: Exception) {
                    _errorMessage.value = "Schedule toggled, but reconciliation failed: ${e.message}"
                }
            } else {
                _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to toggle schedule"
            }
        }
    }

    fun deleteSchedule(id: Long) {
        viewModelScope.launch {
            val result = deleteScheduleUseCase(id)
            if (result.isSuccess) {
                try {
                    reconciler.reconcile(Instant.now(), zoneId)
                } catch (e: Exception) {
                    _errorMessage.value = "Schedule deleted, but reconciliation failed: ${e.message}"
                }
            } else {
                _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to delete schedule"
            }
        }
    }

    fun resumeSchedule(scheduleId: Long) {
        viewModelScope.launch {
            val result = resumeScheduleUseCase(scheduleId)
            if (result.isSuccess) {
                try {
                    reconciler.reconcile(Instant.now(), zoneId)
                } catch (e: Exception) {
                    _errorMessage.value = "Schedule resumed, but reconciliation failed: ${e.message}"
                }
            } else {
                _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to resume schedule"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun buildUiState(
        schedules: List<Schedule>,
        evaluation: ScheduleEvaluation?,
        observedMode: SoundMode,
        exactAlarm: Boolean,
        soundControl: Boolean,
        error: String?
    ): HomeUiState {
        val activeOccurrences = evaluation?.effectiveOccurrences ?: emptyList()
        val overriddenOccurrences = evaluation?.overriddenOccurrences ?: emptyList()
        val activeScheduleIds = activeOccurrences.map { it.scheduleId }.toSet()
        val overriddenScheduleIds = overriddenOccurrences.map { it.scheduleId }.toSet()

        val scheduleUiModels = schedules.map { schedule ->
            ScheduleUiModel(
                id = schedule.id,
                name = schedule.name,
                enabled = schedule.enabled,
                startTime = schedule.startTime,
                endTime = schedule.endTime,
                recurrenceType = schedule.recurrenceType,
                daysOfWeek = schedule.daysOfWeek,
                startDate = schedule.startDate,
                endDate = schedule.endDate,
                isOvernight = schedule.isOvernight,
                timeRangeFormatted = DateTimeUiFormatter.formatTimeRange(
                    schedule.startTime,
                    schedule.endTime,
                    schedule.isOvernight
                ),
                recurrenceFormatted = DateTimeUiFormatter.formatRecurrence(
                    schedule.recurrenceType,
                    schedule.daysOfWeek
                ),
                expirationFormatted = schedule.endDate?.let {
                    "Expires ${DateTimeUiFormatter.formatDate(it)}"
                },
                isActive = schedule.id in activeScheduleIds,
                isOverridden = schedule.id in overriddenScheduleIds
            )
        }

        val actualRingerMode = soundController.getCurrentMode()
        if (lastObservedRingerMode != actualRingerMode) {
            DiagnosticLogger.i(
                "AutoSilentDiag",
                "RINGER_MODE_TRANSITION: oldMode=$lastObservedRingerMode -> newMode=$actualRingerMode, desiredMode=${evaluation?.desiredSoundMode}"
            )
            lastObservedRingerMode = actualRingerMode
        }
        DiagnosticLogger.d(
            "AutoSilentDiag",
            "BUILD_UI_STATE: actualRingerMode=$actualRingerMode, observedMode=$observedMode, desiredSoundMode=${evaluation?.desiredSoundMode}, currentSoundMode=$actualRingerMode"
        )

        return HomeUiState(
            schedules = scheduleUiModels,
            currentSoundMode = actualRingerMode,
            desiredSoundMode = evaluation?.desiredSoundMode ?: SoundMode.RING,
            activeSchedules = activeOccurrences,
            activeScheduleCount = activeOccurrences.size,
            overriddenSchedules = overriddenOccurrences,
            nextBoundary = evaluation?.nextBoundaryEvent,
            isExactAlarmGranted = exactAlarm,
            isSoundControlGranted = soundControl,
            isLoading = false,
            errorMessage = error
        )
    }
}
