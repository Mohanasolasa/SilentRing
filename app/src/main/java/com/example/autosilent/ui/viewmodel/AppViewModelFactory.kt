package com.example.autosilent.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.autosilent.AutoSilentApplication
import com.example.autosilent.domain.usecase.CreateScheduleUseCase
import com.example.autosilent.domain.usecase.DeleteScheduleUseCase
import com.example.autosilent.domain.usecase.GetScheduleByIdUseCase
import com.example.autosilent.domain.usecase.GetSchedulesUseCase
import com.example.autosilent.domain.usecase.ResumeScheduleUseCase
import com.example.autosilent.domain.usecase.ToggleScheduleUseCase
import com.example.autosilent.domain.usecase.UpdateScheduleUseCase
import com.example.autosilent.ui.details.ScheduleDetailsViewModel
import com.example.autosilent.ui.form.ScheduleFormViewModel
import com.example.autosilent.ui.home.HomeViewModel
import com.example.autosilent.ui.permissions.PermissionSetupViewModel

class AppViewModelFactory(
    private val app: AutoSilentApplication
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(HomeViewModel::class.java) -> {
                HomeViewModel(
                    getSchedulesUseCase = GetSchedulesUseCase(app.repository),
                    toggleScheduleUseCase = ToggleScheduleUseCase(app.repository),
                    deleteScheduleUseCase = DeleteScheduleUseCase(app.repository),
                    resumeScheduleUseCase = ResumeScheduleUseCase(app.repository),
                    reconciler = app.reconciler,
                    alarmScheduler = app.alarmScheduler,
                    soundController = app.soundController
                ) as T
            }
            modelClass.isAssignableFrom(ScheduleFormViewModel::class.java) -> {
                ScheduleFormViewModel(
                    createScheduleUseCase = CreateScheduleUseCase(app.repository),
                    updateScheduleUseCase = UpdateScheduleUseCase(app.repository),
                    getScheduleByIdUseCase = GetScheduleByIdUseCase(app.repository),
                    reconciler = app.reconciler
                ) as T
            }
            modelClass.isAssignableFrom(ScheduleDetailsViewModel::class.java) -> {
                ScheduleDetailsViewModel(
                    getSchedulesUseCase = GetSchedulesUseCase(app.repository),
                    toggleScheduleUseCase = ToggleScheduleUseCase(app.repository),
                    deleteScheduleUseCase = DeleteScheduleUseCase(app.repository),
                    resumeScheduleUseCase = ResumeScheduleUseCase(app.repository),
                    reconciler = app.reconciler
                ) as T
            }
            modelClass.isAssignableFrom(PermissionSetupViewModel::class.java) -> {
                PermissionSetupViewModel(
                    alarmScheduler = app.alarmScheduler,
                    soundController = app.soundController
                ) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
