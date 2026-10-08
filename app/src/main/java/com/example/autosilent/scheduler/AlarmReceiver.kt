package com.example.autosilent.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.autosilent.AutoSilentApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidAlarmScheduler.ACTION_ALARM_BOUNDARY) {
            return
        }

        val event = AndroidAlarmScheduler.parseEventFromIntent(intent)
        if (event == null) {
            DiagnosticLogger.w("AutoSilentDiag", "ALARM_RECEIVED_UNPARSEABLE: intent action=${intent.action}, data=${intent.data}")
            return
        }

        val actualReceiverTime = Instant.now()
        val latencyMillis = actualReceiverTime.toEpochMilli() - event.triggerInstant.toEpochMilli()
        DiagnosticLogger.i(
            "AutoSilentDiag",
            "ALARM_RECEIVED: scheduleId=${event.scheduleId}, type=${event.type}, triggerInstant=${event.triggerInstant}, actualReceiverTime=$actualReceiverTime, latencyMs=$latencyMillis, occurrenceStart=${event.occurrenceStart}, occurrenceEnd=${event.occurrenceEnd}"
        )

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val app = context.applicationContext as? AutoSilentApplication
                app?.reconciler?.reconcile(
                    currentInstant = actualReceiverTime,
                    zoneId = ZoneId.systemDefault(),
                    triggeredByEvent = event
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
