package com.example.autosilent.domain.engine

import com.example.autosilent.domain.model.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure Kotlin deterministic Schedule Engine.
 *
 * Requirements:
 * 1. Zero dependencies on Android SDK, Room, System services, or global clocks.
 * 2. Explicit ZoneId and Instant passed to all functions.
 * 3. Strictly adheres to [startInstant, endInstant) half-open interval semantics:
 *    - At startInstant -> ACTIVE
 *    - Between start and end -> ACTIVE
 *    - At endInstant -> INACTIVE
 */
object ScheduleEngine {

    private const val MAX_LOOKAHEAD_DAYS = 30L

    /**
     * Checks if a schedule applies to a given calendar date in the local timezone.
     */
    fun isScheduleApplicableOnDate(schedule: Schedule, date: LocalDate): Boolean {
        if (!schedule.enabled) return false
        if (date.isBefore(schedule.startDate)) return false
        if (schedule.endDate != null && date.isAfter(schedule.endDate)) return false

        return when (schedule.recurrenceType) {
            RecurrenceType.ONE_TIME -> date == schedule.startDate
            RecurrenceType.DAILY -> true
            RecurrenceType.WEEKDAYS -> date.dayOfWeek in listOf(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY
            )
            RecurrenceType.WEEKENDS -> date.dayOfWeek in listOf(
                DayOfWeek.SATURDAY,
                DayOfWeek.SUNDAY
            )
            RecurrenceType.CUSTOM_DAYS -> schedule.daysOfWeek.contains(date.dayOfWeek)
        }
    }

    /**
     * Computes the concrete [ScheduleOccurrence] initiated on a specific calendar date.
     *
     * - Daytime schedule: starts on date at startTime, ends on date at endTime.
     * - Overnight schedule: starts on date at startTime, ends on date + 1 day at endTime.
     */
    fun getOccurrenceInitiatedOnDate(
        schedule: Schedule,
        date: LocalDate,
        zoneId: ZoneId
    ): ScheduleOccurrence? {
        if (!isScheduleApplicableOnDate(schedule, date)) return null

        val startZoned = date.atTime(schedule.startTime).atZone(zoneId)
        val endZoned = if (schedule.isOvernight) {
            date.plusDays(1).atTime(schedule.endTime).atZone(zoneId)
        } else {
            date.atTime(schedule.endTime).atZone(zoneId)
        }

        return ScheduleOccurrence(
            scheduleId = schedule.id,
            scheduleName = schedule.name,
            startInstant = startZoned.toInstant(),
            endInstant = endZoned.toInstant()
        )
    }

    /**
     * Returns all occurrences from this schedule that are active at the given [instant].
     * Takes into account overnight schedules initiated on the previous calendar day.
     */
    fun getOccurrencesCoveringInstant(
        schedule: Schedule,
        instant: Instant,
        zoneId: ZoneId
    ): List<ScheduleOccurrence> {
        if (!schedule.enabled) return emptyList()

        val currentDate = instant.atZone(zoneId).toLocalDate()
        val candidates = mutableListOf<ScheduleOccurrence>()

        // 1. Occurrence initiated today
        getOccurrenceInitiatedOnDate(schedule, currentDate, zoneId)?.let { candidates.add(it) }

        // 2. If schedule is overnight, check occurrence initiated yesterday
        if (schedule.isOvernight) {
            getOccurrenceInitiatedOnDate(schedule, currentDate.minusDays(1), zoneId)?.let { candidates.add(it) }
        }

        return candidates.filter { it.isActiveAt(instant) }
    }

    /**
     * Determines all active schedule occurrences across all supplied schedules at [instant].
     */
    fun getActiveOccurrences(
        schedules: List<Schedule>,
        instant: Instant,
        zoneId: ZoneId
    ): List<ScheduleOccurrence> {
        return schedules.flatMap { schedule ->
            getOccurrencesCoveringInstant(schedule, instant, zoneId)
        }
    }

    /**
     * Checks if a specific occurrence has a valid, non-expired manual override.
     */
    fun isOccurrenceOverridden(
        occurrence: ScheduleOccurrence,
        overrides: List<OccurrenceOverride>,
        instant: Instant
    ): Boolean {
        return overrides.any { override ->
            override.scheduleId == occurrence.scheduleId &&
            override.occurrenceStart.toEpochMilli() == occurrence.startInstant.toEpochMilli() &&
            override.occurrenceEnd.toEpochMilli() == occurrence.endInstant.toEpochMilli() &&
            override.occurrenceEnd.isAfter(instant)
        }
    }

    /**
     * Evaluates the global device sound mode and state across all schedules and active overrides.
     *
     * Global Algorithm:
     * 1. Find all active occurrences covering [instant].
     * 2. Partition active occurrences into overridden vs non-overridden.
     * 3. If count(non-overridden) > 0 -> SILENT
     *    Else -> RING
     * 4. Calculate the next chronological boundary event.
     */
    fun evaluateGlobalState(
        schedules: List<Schedule>,
        overrides: List<OccurrenceOverride>,
        instant: Instant,
        zoneId: ZoneId
    ): ScheduleEvaluation {
        val active = getActiveOccurrences(schedules, instant, zoneId)

        val overridden = mutableListOf<ScheduleOccurrence>()
        val effective = mutableListOf<ScheduleOccurrence>()

        for (occ in active) {
            if (isOccurrenceOverridden(occ, overrides, instant)) {
                overridden.add(occ)
            } else {
                effective.add(occ)
            }
        }

        val desiredSoundMode = if (effective.isNotEmpty()) SoundMode.SILENT else SoundMode.RING
        val nextBoundary = calculateNextBoundaryEvent(schedules, instant, zoneId)

        return ScheduleEvaluation(
            evaluatedAt = instant,
            activeOccurrences = active,
            overriddenOccurrences = overridden,
            effectiveOccurrences = effective,
            desiredSoundMode = desiredSoundMode,
            nextBoundaryEvent = nextBoundary
        )
    }

    /**
     * Computes the next chronological boundary event (START or END) across all enabled schedules
     * occurring strictly after [afterInstant].
     */
    fun calculateNextBoundaryEvent(
        schedules: List<Schedule>,
        afterInstant: Instant,
        zoneId: ZoneId
    ): BoundaryEvent? {
        val enabledSchedules = schedules.filter { it.enabled }
        if (enabledSchedules.isEmpty()) return null

        val startDate = afterInstant.atZone(zoneId).toLocalDate()
        val candidateEvents = mutableListOf<BoundaryEvent>()

        for (schedule in enabledSchedules) {
            // Check yesterday for active overnight schedule's end boundary
            if (schedule.isOvernight) {
                getOccurrenceInitiatedOnDate(schedule, startDate.minusDays(1), zoneId)?.let { occ ->
                    if (occ.endInstant.isAfter(afterInstant)) {
                        candidateEvents.add(
                            BoundaryEvent(
                                scheduleId = schedule.id,
                                scheduleName = schedule.name,
                                type = BoundaryType.END,
                                triggerInstant = occ.endInstant,
                                occurrenceStart = occ.startInstant,
                                occurrenceEnd = occ.endInstant
                            )
                        )
                    }
                }
            }

            // Inspect upcoming days up to MAX_LOOKAHEAD_DAYS or schedule endDate
            for (dayOffset in 0..MAX_LOOKAHEAD_DAYS) {
                val date = startDate.plusDays(dayOffset)
                if (schedule.endDate != null && date.isAfter(schedule.endDate)) {
                    break
                }

                val occ = getOccurrenceInitiatedOnDate(schedule, date, zoneId) ?: continue

                // Check START event
                if (occ.startInstant.isAfter(afterInstant)) {
                    candidateEvents.add(
                        BoundaryEvent(
                            scheduleId = schedule.id,
                            scheduleName = schedule.name,
                            type = BoundaryType.START,
                            triggerInstant = occ.startInstant,
                            occurrenceStart = occ.startInstant,
                            occurrenceEnd = occ.endInstant
                        )
                    )
                }

                // Check END event (if already active or starts now)
                if (occ.isActiveAt(afterInstant) && occ.endInstant.isAfter(afterInstant)) {
                    candidateEvents.add(
                        BoundaryEvent(
                            scheduleId = schedule.id,
                            scheduleName = schedule.name,
                            type = BoundaryType.END,
                            triggerInstant = occ.endInstant,
                            occurrenceStart = occ.startInstant,
                            occurrenceEnd = occ.endInstant
                        )
                    )
                }

                // If we found a start event far in the future, we have enough events for this schedule
                if (occ.startInstant.isAfter(afterInstant.plusSeconds(86400 * 7))) {
                    break
                }
            }
        }

        // Deterministic tie-breaking rule:
        // 1. Primary: triggerInstant ascending (earliest event first)
        // 2. Secondary: BoundaryType ordinal ascending (START before END)
        // 3. Tertiary: scheduleId ascending
        val eventComparator = compareBy<BoundaryEvent> { it.triggerInstant }
            .thenBy { it.type }
            .thenBy { it.scheduleId }

        return candidateEvents
            .filter { it.triggerInstant.isAfter(afterInstant) }
            .minWithOrNull(eventComparator)
    }

    /**
     * Stale Alarm Protection: Validates whether a received boundary event still corresponds
     * to a valid, active schedule and occurrence in the database.
     */
    fun isEventValid(
        event: BoundaryEvent,
        schedule: Schedule?,
        zoneId: ZoneId
    ): Boolean {
        if (schedule == null || !schedule.enabled || schedule.id != event.scheduleId) {
            return false
        }

        val startDate = event.occurrenceStart.atZone(zoneId).toLocalDate()
        val expectedOccurrence = getOccurrenceInitiatedOnDate(schedule, startDate, zoneId)
            ?: return false

        return expectedOccurrence.startInstant.toEpochMilli() == event.occurrenceStart.toEpochMilli() &&
               expectedOccurrence.endInstant.toEpochMilli() == event.occurrenceEnd.toEpochMilli()
    }
}
