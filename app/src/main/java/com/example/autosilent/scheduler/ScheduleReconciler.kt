package com.example.autosilent.scheduler

import com.example.autosilent.domain.controller.SoundController
import com.example.autosilent.domain.engine.ScheduleEngine
import com.example.autosilent.domain.model.BoundaryEvent
import com.example.autosilent.domain.model.ScheduleEvaluation
import com.example.autosilent.domain.model.SoundMode
import com.example.autosilent.domain.repository.ScheduleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/**
 * Central coordinator responsible for serializing state reconciliation, validating alarm events,
 * querying ScheduleEngine, applying desired sound mode, and scheduling the next boundary event.
 */
class ScheduleReconciler(
    private val repository: ScheduleRepository,
    private val alarmScheduler: AlarmScheduler,
    private val soundController: SoundController = com.example.autosilent.domain.controller.NoOpSoundController,
    private val onSoundModeChanged: (suspend (SoundMode) -> Unit)? = null
) {
    private val mutex = Mutex()

    private var currentScheduledBoundary: BoundaryEvent? = null

    private val _currentEvaluation = MutableStateFlow<ScheduleEvaluation?>(null)
    val currentEvaluation: StateFlow<ScheduleEvaluation?> = _currentEvaluation.asStateFlow()

    /**
     * Executes serialized reconciliation of all schedules and active overrides at [currentInstant].
     *
     * @param currentInstant The evaluation instant.
     * @param zoneId The local timezone to use for calendar boundaries.
     * @param triggeredByEvent Optional event that triggered this reconciliation, used for stale-alarm validation.
     * @return The resulting [ScheduleEvaluation].
     */
    suspend fun reconcile(
        currentInstant: Instant,
        zoneId: ZoneId,
        triggeredByEvent: BoundaryEvent? = null
    ): ScheduleEvaluation = mutex.withLock {
        // 1. Stale-event validation
        if (triggeredByEvent != null) {
            val triggeredSchedule = repository.getScheduleById(triggeredByEvent.scheduleId)
            val isEventValid = ScheduleEngine.isEventValid(triggeredByEvent, triggeredSchedule, zoneId)
            val validationResult = if (isEventValid) "VALID" else "STALE_REJECTED"
            DiagnosticLogger.i(
                "AutoSilentDiag",
                "RECONCILE_TRIGGER: scheduleId=${triggeredByEvent.scheduleId}, type=${triggeredByEvent.type}, expectedTrigger=${triggeredByEvent.triggerInstant}, actualTime=$currentInstant, occurrenceStart=${triggeredByEvent.occurrenceStart}, occurrenceEnd=${triggeredByEvent.occurrenceEnd}, validationResult=$validationResult"
            )
        } else {
            DiagnosticLogger.i("AutoSilentDiag", "RECONCILE_MANUAL_OR_BOOT: evaluatedAt=$currentInstant")
        }

        // 2. Prune expired overrides whose occurrence end has passed
        repository.pruneExpiredOverrides(currentInstant)

        // 3. Load active enabled schedules and active overrides from Room
        val enabledSchedules = repository.getEnabledSchedules()
        val activeOverrides = repository.getActiveOverrides(currentInstant)

        // 4. Evaluate global state through pure ScheduleEngine
        val evaluation = ScheduleEngine.evaluateGlobalState(
            schedules = enabledSchedules,
            overrides = activeOverrides,
            instant = currentInstant,
            zoneId = zoneId
        )

        // 5. Apply target sound state via SoundController
        when (evaluation.desiredSoundMode) {
            SoundMode.SILENT -> soundController.setSilent()
            SoundMode.RING -> soundController.setRing()
        }
        onSoundModeChanged?.invoke(evaluation.desiredSoundMode)

        // 6. Schedule the immediate next boundary event if present, or cancel if none
        if (evaluation.nextBoundaryEvent != null) {
            val next = evaluation.nextBoundaryEvent
            val prev = currentScheduledBoundary
            if (prev != null && prev != next) {
                alarmScheduler.cancelBoundaryEvent(prev.scheduleId, prev.type, prev.occurrenceStart)
            }
            alarmScheduler.scheduleBoundaryEvent(next)
            currentScheduledBoundary = next
        } else {
            val prev = currentScheduledBoundary
            if (prev != null) {
                alarmScheduler.cancelBoundaryEvent(prev.scheduleId, prev.type, prev.occurrenceStart)
            }
            alarmScheduler.cancelAll()
            currentScheduledBoundary = null
        }

        DiagnosticLogger.i(
            "AutoSilentDiag",
            "RECONCILE_COMPLETED: desiredSoundMode=${evaluation.desiredSoundMode}, activeCount=${evaluation.activeOccurrences.size}, nextBoundary=${evaluation.nextBoundaryEvent?.let { "scheduleId=${it.scheduleId}, type=${it.type}, trigger=${it.triggerInstant}" } ?: "NONE"}"
        )

        // 7. Update reactive state flow
        _currentEvaluation.value = evaluation

        return evaluation
    }
}
