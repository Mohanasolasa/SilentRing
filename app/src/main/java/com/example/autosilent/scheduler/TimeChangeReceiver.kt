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

/**
 * Dedicated BroadcastReceiver for handling system clock, date, and timezone modifications.
 * Triggers a fresh reconciliation against the updated wall-clock time and timezone.
 */
class TimeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_DATE_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED
        ) {
            return
        }

        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        DiagnosticLogger.i("AutoSilentTime", "TIME_EVENT_RECEIVED: action=$action at $now in zone $zone. Reconciling...")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val app = context.applicationContext as? AutoSilentApplication
                if (app != null) {
                    val evaluation = app.reconciler.reconcile(
                        currentInstant = now,
                        zoneId = zone
                    )
                    DiagnosticLogger.i(
                        "AutoSilentTime",
                        "TIME_RECONCILIATION_COMPLETED: mode=${evaluation.desiredSoundMode}, activeCount=${evaluation.activeOccurrences.size}, nextBoundary=${evaluation.nextBoundaryEvent?.triggerInstant}"
                    )
                }
            } catch (t: Throwable) {
                DiagnosticLogger.e("AutoSilentTime", "Error during time change reconciliation", t)
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
