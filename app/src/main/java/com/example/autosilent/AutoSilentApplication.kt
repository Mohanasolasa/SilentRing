package com.example.autosilent

import android.app.Application
import com.example.autosilent.data.local.AppDatabase
import com.example.autosilent.data.repository.ScheduleRepositoryImpl
import com.example.autosilent.domain.repository.ScheduleRepository
import com.example.autosilent.scheduler.AlarmScheduler
import com.example.autosilent.scheduler.AndroidAlarmScheduler
import com.example.autosilent.scheduler.ScheduleReconciler

class AutoSilentApplication : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    val repository: ScheduleRepository by lazy {
        ScheduleRepositoryImpl(database.scheduleDao(), database.occurrenceOverrideDao())
    }

    val alarmScheduler: AlarmScheduler by lazy {
        AndroidAlarmScheduler(this)
    }

    val soundController: com.example.autosilent.domain.controller.SoundController by lazy {
        com.example.autosilent.system.AndroidSoundController(this)
    }

    val reconciler: ScheduleReconciler by lazy {
        ScheduleReconciler(
            repository = repository,
            alarmScheduler = alarmScheduler,
            soundController = soundController
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Authoritative broadcast registration for TimeChangeReceiver is in AndroidManifest.xml.
        // Dynamic registration removed to prevent duplicate broadcast delivery.
    }
}
