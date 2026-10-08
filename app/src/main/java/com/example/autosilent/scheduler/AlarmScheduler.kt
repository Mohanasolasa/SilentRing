package com.example.autosilent.scheduler

import com.example.autosilent.domain.model.BoundaryEvent
import com.example.autosilent.domain.model.BoundaryType
import java.time.Instant

/**
 * Interface abstracting AlarmManager operations.
 */
interface AlarmScheduler {
    /**
     * Checks if the application currently has permission to schedule exact alarms.
     */
    fun canScheduleExactAlarms(): Boolean

    /**
     * Schedules the single-shot exact boundary event.
     */
    fun scheduleBoundaryEvent(event: BoundaryEvent): Boolean

    /**
     * Cancels a scheduled boundary event for a specific schedule and occurrence.
     */
    fun cancelBoundaryEvent(scheduleId: Long, type: BoundaryType, occurrenceStart: Instant)

    /**
     * Cancels all scheduled alarms for a specific schedule.
     */
    fun cancelAllForSchedule(scheduleId: Long)

    /**
     * Cancels all scheduled alarms managed by the application.
     */
    fun cancelAll()
}
