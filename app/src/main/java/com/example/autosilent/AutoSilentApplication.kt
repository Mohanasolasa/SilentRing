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

    val reconciler: ScheduleReconciler by lazy {
        ScheduleReconciler(
            repository = repository,
            alarmScheduler = alarmScheduler
        )
    }

    override fun onCreate() {
        super.onCreate()
    }
}
