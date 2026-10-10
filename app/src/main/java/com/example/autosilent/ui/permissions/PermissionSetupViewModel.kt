package com.example.autosilent.ui.permissions

import androidx.lifecycle.ViewModel
import com.example.autosilent.domain.controller.SoundController
import com.example.autosilent.scheduler.AlarmScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PermissionSetupUiState(
    val isSoundControlGranted: Boolean = false,
    val isExactAlarmGranted: Boolean = false
) {
    val allGranted: Boolean get() = isSoundControlGranted && isExactAlarmGranted
}

class PermissionSetupViewModel(
    private val alarmScheduler: AlarmScheduler,
    private val soundController: SoundController
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        PermissionSetupUiState(
            isSoundControlGranted = soundController.canControlSound(),
            isExactAlarmGranted = alarmScheduler.canScheduleExactAlarms()
        )
    )
    val uiState: StateFlow<PermissionSetupUiState> = _uiState.asStateFlow()

    fun refreshPermissions() {
        _uiState.update {
            it.copy(
                isSoundControlGranted = soundController.canControlSound(),
                isExactAlarmGranted = alarmScheduler.canScheduleExactAlarms()
            )
        }
    }
}
