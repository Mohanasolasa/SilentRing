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
 * Dedicated BroadcastReceiver for handling device boot completion.
 * Authoritative recovery point for rebuilding active schedules, applying
 * current global sound mode, and scheduling the next upcoming boundary.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED) {
            DiagnosticLogger.w("AutoSilentBoot", "Ignored unexpected action: $action")
            return
        }

        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        DiagnosticLogger.i("AutoSilentBoot", "BOOT_COMPLETED received at $now in zone $zone. Reconciling state from Room ground truth...")

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
                        "AutoSilentBoot",
                        "BOOT_RECOVERY_COMPLETED: mode=${evaluation.desiredSoundMode}, activeCount=${evaluation.activeOccurrences.size}, nextBoundary=${evaluation.nextBoundaryEvent?.triggerInstant}"
                    )
                } else {
                    DiagnosticLogger.e("AutoSilentBoot", "ApplicationContext is not AutoSilentApplication")
                }
            } catch (t: Throwable) {
                DiagnosticLogger.e("AutoSilentBoot", "Error during boot reconciliation", t)
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
