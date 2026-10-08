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
        registerTimeChangeReceiver()
    }

    private fun registerTimeChangeReceiver() {
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_TIME_CHANGED)
            addAction(android.content.Intent.ACTION_DATE_CHANGED)
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
        }
        val receiver = com.example.autosilent.scheduler.TimeChangeReceiver()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }
}
