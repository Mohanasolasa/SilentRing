package com.example.autosilent.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.example.autosilent.domain.model.BoundaryEvent
import com.example.autosilent.domain.model.BoundaryType
import java.time.Instant

class AndroidAlarmScheduler(
    private val context: Context
) : AlarmScheduler {

    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    override fun canScheduleExactAlarms(): Boolean {
        if (alarmManager == null) return false
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
        DiagnosticLogger.i("AutoSilentDiag", "EXACT_ALARM_CHECK: canScheduleExactAlarms=$result, sdkInt=${Build.VERSION.SDK_INT}")
        return result
    }

    override fun scheduleBoundaryEvent(event: BoundaryEvent): Boolean {
        if (alarmManager == null) {
            DiagnosticLogger.w("AutoSilentDiag", "SCHEDULING_FAILED: AlarmManager is null")
            return false
        }
        if (!canScheduleExactAlarms()) {
            DiagnosticLogger.w("AutoSilentDiag", "SCHEDULING_FAILED: canScheduleExactAlarms=false for scheduleId=${event.scheduleId}")
            return false
        }

        val pendingIntent = createPendingIntent(event)
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                event.triggerInstant.toEpochMilli(),
                pendingIntent
            )
            DiagnosticLogger.i(
                "AutoSilentDiag",
                "SCHEDULING_SUCCESS: scheduleId=${event.scheduleId}, name=${event.scheduleName}, type=${event.type}, trigger=${event.triggerInstant}, occurrenceStart=${event.occurrenceStart}, occurrenceEnd=${event.occurrenceEnd}"
            )
            return true
        } catch (e: SecurityException) {
            DiagnosticLogger.e("AutoSilentDiag", "SCHEDULING_SECURITY_EXCEPTION: scheduleId=${event.scheduleId}", e)
            return false
        }
    }

    override fun cancelBoundaryEvent(scheduleId: Long, type: BoundaryType, occurrenceStart: Instant) {
        if (alarmManager == null) return
        DiagnosticLogger.i("AutoSilentDiag", "CANCELLING_ALARM: scheduleId=$scheduleId, type=$type, occurrenceStart=$occurrenceStart")
        val pendingIntent = createCancelPendingIntent(scheduleId, type, occurrenceStart)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    override fun cancelAllForSchedule(scheduleId: Long) {
        // Cancel both potential START and END boundaries for this schedule
        cancelBoundaryEvent(scheduleId, BoundaryType.START, Instant.EPOCH)
        cancelBoundaryEvent(scheduleId, BoundaryType.END, Instant.EPOCH)
    }

    override fun cancelAll() {
        // Handled per-schedule or on demand
    }

    private fun createPendingIntent(event: BoundaryEvent): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_ALARM_BOUNDARY
            data = createEventUri(event.scheduleId, event.type, event.occurrenceStart)
            putExtra(EXTRA_SCHEDULE_ID, event.scheduleId)
            putExtra(EXTRA_SCHEDULE_NAME, event.scheduleName)
            putExtra(EXTRA_BOUNDARY_TYPE, event.type.name)
            putExtra(EXTRA_TRIGGER_EPOCH_MILLIS, event.triggerInstant.toEpochMilli())
            putExtra(EXTRA_OCCURRENCE_START_EPOCH_MILLIS, event.occurrenceStart.toEpochMilli())
            putExtra(EXTRA_OCCURRENCE_END_EPOCH_MILLIS, event.occurrenceEnd.toEpochMilli())
        }

        val requestCode = computeRequestCode(event.scheduleId, event.type, event.occurrenceStart)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun createCancelPendingIntent(
        scheduleId: Long,
        type: BoundaryType,
        occurrenceStart: Instant
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_ALARM_BOUNDARY
            data = createEventUri(scheduleId, type, occurrenceStart)
        }
        val requestCode = computeRequestCode(scheduleId, type, occurrenceStart)
        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    companion object {
        const val ACTION_ALARM_BOUNDARY = "com.example.autosilent.ACTION_ALARM_BOUNDARY"
        const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
        const val EXTRA_SCHEDULE_NAME = "extra_schedule_name"
        const val EXTRA_BOUNDARY_TYPE = "extra_boundary_type"
        const val EXTRA_TRIGGER_EPOCH_MILLIS = "extra_trigger_epoch_millis"
        const val EXTRA_OCCURRENCE_START_EPOCH_MILLIS = "extra_occurrence_start_epoch_millis"
        const val EXTRA_OCCURRENCE_END_EPOCH_MILLIS = "extra_occurrence_end_epoch_millis"

        fun createEventUriString(scheduleId: Long, type: BoundaryType, occurrenceStart: Instant): String {
            return "autosilent://boundary/$scheduleId/${type.name}/${occurrenceStart.toEpochMilli()}"
        }

        fun createEventUri(scheduleId: Long, type: BoundaryType, occurrenceStart: Instant): Uri {
            return Uri.parse(createEventUriString(scheduleId, type, occurrenceStart))
        }

        fun computeRequestCode(scheduleId: Long, type: BoundaryType, occurrenceStart: Instant): Int {
            return (scheduleId.hashCode() * 31 + type.hashCode()) * 31 + occurrenceStart.hashCode()
        }

        fun parseEventFromIntent(intent: Intent): BoundaryEvent? {
            if (intent.action != ACTION_ALARM_BOUNDARY) return null

            val scheduleId = intent.getLongExtra(EXTRA_SCHEDULE_ID, -1L)
            if (scheduleId == -1L) return null

            val scheduleName = intent.getStringExtra(EXTRA_SCHEDULE_NAME) ?: ""
            val typeStr = intent.getStringExtra(EXTRA_BOUNDARY_TYPE) ?: return null
            val type = runCatching { BoundaryType.valueOf(typeStr) }.getOrNull() ?: return null

            val triggerMillis = intent.getLongExtra(EXTRA_TRIGGER_EPOCH_MILLIS, -1L)
            val startMillis = intent.getLongExtra(EXTRA_OCCURRENCE_START_EPOCH_MILLIS, -1L)
            val endMillis = intent.getLongExtra(EXTRA_OCCURRENCE_END_EPOCH_MILLIS, -1L)

            if (triggerMillis == -1L || startMillis == -1L || endMillis == -1L) return null

            return BoundaryEvent(
                scheduleId = scheduleId,
                scheduleName = scheduleName,
                type = type,
                triggerInstant = Instant.ofEpochMilli(triggerMillis),
                occurrenceStart = Instant.ofEpochMilli(startMillis),
                occurrenceEnd = Instant.ofEpochMilli(endMillis)
            )
        }
    }
}
