package com.example.autosilent.domain.model

import java.time.Instant

/**
 * Represents an instantiated concrete window of time for a schedule.
 *
 * Boundary Convention:
 * Interval is strictly half-open: [startInstant, endInstant)
 * - start <= instant < end
 * - At startInstant -> active (true)
 * - During interval -> active (true)
 * - At endInstant   -> inactive (false)
 */
data class ScheduleOccurrence(
    val scheduleId: Long,
    val scheduleName: String,
    val startInstant: Instant,
    val endInstant: Instant
) {
    init {
        require(endInstant.isAfter(startInstant)) {
            "Occurrence endInstant ($endInstant) must be strictly after startInstant ($startInstant)"
        }
    }

    /**
     * Determines whether this occurrence is active at the specified instant
     * using the [startInstant, endInstant) interval convention.
     */
    fun isActiveAt(instant: Instant): Boolean =
        !instant.isBefore(startInstant) && instant.isBefore(endInstant)
}

enum class BoundaryType {
    START,
    END
}

/**
 * Represents an upcoming or current boundary event (START or END of an occurrence).
 */
data class BoundaryEvent(
    val scheduleId: Long,
    val scheduleName: String,
    val type: BoundaryType,
    val triggerInstant: Instant,
    val occurrenceStart: Instant,
    val occurrenceEnd: Instant
)

/**
 * Encapsulates the global evaluated state of all schedules at a specific instant.
 */
data class ScheduleEvaluation(
    val evaluatedAt: Instant,
    val activeOccurrences: List<ScheduleOccurrence>,
    val overriddenOccurrences: List<ScheduleOccurrence>,
    val effectiveOccurrences: List<ScheduleOccurrence>,
    val desiredSoundMode: SoundMode,
    val nextBoundaryEvent: BoundaryEvent? = null
)
