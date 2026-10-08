package com.example.autosilent.domain.validation

import com.example.autosilent.domain.model.RecurrenceType
import com.example.autosilent.domain.model.Schedule
import java.time.DayOfWeek

sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()
}

object ScheduleValidator {

    private const val MAX_NAME_LENGTH = 100

    fun validate(schedule: Schedule): ValidationResult {
        // 1. Name validation
        if (schedule.name.isBlank()) {
            return ValidationResult.Invalid("Schedule name cannot be empty.")
        }
        if (schedule.name.length > MAX_NAME_LENGTH) {
            return ValidationResult.Invalid("Schedule name cannot exceed $MAX_NAME_LENGTH characters.")
        }

        // 2. Start and End Time validation
        if (schedule.startTime == schedule.endTime) {
            return ValidationResult.Invalid("Start time and end time cannot be identical.")
        }

        // 3. Date / Expiration validation
        schedule.endDate?.let { end ->
            if (end.isBefore(schedule.startDate)) {
                return ValidationResult.Invalid("Expiration date cannot be earlier than start date.")
            }
        }

        // 4. Recurrence configuration validation
        when (schedule.recurrenceType) {
            RecurrenceType.ONE_TIME -> {
                if (schedule.daysOfWeek.isNotEmpty()) {
                    return ValidationResult.Invalid("One-time schedule cannot have recurring days of the week.")
                }
                if (schedule.endDate != null && schedule.endDate != schedule.startDate) {
                    return ValidationResult.Invalid("One-time schedule end date must match start date.")
                }
            }
            RecurrenceType.CUSTOM_DAYS -> {
                if (schedule.daysOfWeek.isEmpty()) {
                    return ValidationResult.Invalid("Custom days schedule must select at least one day of the week.")
                }
            }
            RecurrenceType.WEEKDAYS -> {
                val weekdays = setOf(
                    DayOfWeek.MONDAY,
                    DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY,
                    DayOfWeek.FRIDAY
                )
                if (schedule.daysOfWeek.isNotEmpty() && schedule.daysOfWeek != weekdays) {
                    return ValidationResult.Invalid("Weekdays schedule days must strictly be Monday through Friday.")
                }
            }
            RecurrenceType.WEEKENDS -> {
                val weekends = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                if (schedule.daysOfWeek.isNotEmpty() && schedule.daysOfWeek != weekends) {
                    return ValidationResult.Invalid("Weekends schedule days must strictly be Saturday and Sunday.")
                }
            }
            RecurrenceType.DAILY -> {
                // DAILY does not require specific daysOfWeek or can have full week
            }
        }

        return ValidationResult.Valid
    }

    /**
     * Normalizes daysOfWeek based on RecurrenceType to guarantee data consistency.
     */
    fun normalize(schedule: Schedule): Schedule {
        val normalizedDays = when (schedule.recurrenceType) {
            RecurrenceType.ONE_TIME -> emptySet()
            RecurrenceType.DAILY -> DayOfWeek.values().toSet()
            RecurrenceType.WEEKDAYS -> setOf(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY
            )
            RecurrenceType.WEEKENDS -> setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            RecurrenceType.CUSTOM_DAYS -> schedule.daysOfWeek
        }
        return schedule.copy(
            name = schedule.name.trim(),
            daysOfWeek = normalizedDays,
            endDate = if (schedule.recurrenceType == RecurrenceType.ONE_TIME) schedule.startDate else schedule.endDate
        )
    }
}
