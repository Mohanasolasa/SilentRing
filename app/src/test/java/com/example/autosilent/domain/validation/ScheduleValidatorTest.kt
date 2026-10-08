package com.example.autosilent.domain.validation

import com.example.autosilent.domain.model.RecurrenceType
import com.example.autosilent.domain.model.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class ScheduleValidatorTest {

    private val baseDate = LocalDate.of(2026, 10, 12)

    @Test
    fun valid_dailySchedule_passesValidation() {
        val schedule = Schedule(
            name = "Sleep",
            startTime = LocalTime.of(22, 0),
            endTime = LocalTime.of(6, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )

        val result = ScheduleValidator.validate(schedule)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun reject_blankName() {
        val schedule = Schedule(
            name = "   ",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(10, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )

        val result = ScheduleValidator.validate(schedule)
        assertTrue(result is ValidationResult.Invalid)
        assertEquals("Schedule name cannot be empty.", (result as ValidationResult.Invalid).reason)
    }

    @Test
    fun reject_identicalStartAndEndTime() {
        val schedule = Schedule(
            name = "Meeting",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(10, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate
        )

        val result = ScheduleValidator.validate(schedule)
        assertTrue(result is ValidationResult.Invalid)
        assertEquals("Start time and end time cannot be identical.", (result as ValidationResult.Invalid).reason)
    }

    @Test
    fun reject_expirationBeforeStartDate() {
        val schedule = Schedule(
            name = "Study",
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(10, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = baseDate,
            endDate = baseDate.minusDays(1)
        )

        val result = ScheduleValidator.validate(schedule)
        assertTrue(result is ValidationResult.Invalid)
        assertEquals("Expiration date cannot be earlier than start date.", (result as ValidationResult.Invalid).reason)
    }

    @Test
    fun reject_customDays_whenEmpty() {
        val schedule = Schedule(
            name = "Gym",
            startTime = LocalTime.of(6, 0),
            endTime = LocalTime.of(7, 0),
            recurrenceType = RecurrenceType.CUSTOM_DAYS,
            daysOfWeek = emptySet(),
            startDate = baseDate
        )

        val result = ScheduleValidator.validate(schedule)
        assertTrue(result is ValidationResult.Invalid)
        assertEquals("Custom days schedule must select at least one day of the week.", (result as ValidationResult.Invalid).reason)
    }

    @Test
    fun valid_customDays_whenNotEmpty() {
        val schedule = Schedule(
            name = "Gym",
            startTime = LocalTime.of(6, 0),
            endTime = LocalTime.of(7, 0),
            recurrenceType = RecurrenceType.CUSTOM_DAYS,
            daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            startDate = baseDate
        )

        val result = ScheduleValidator.validate(schedule)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun reject_oneTime_withDaysOfWeek() {
        val schedule = Schedule(
            name = "Doctor Appointment",
            startTime = LocalTime.of(14, 0),
            endTime = LocalTime.of(15, 0),
            recurrenceType = RecurrenceType.ONE_TIME,
            daysOfWeek = setOf(DayOfWeek.MONDAY),
            startDate = baseDate
        )

        val result = ScheduleValidator.validate(schedule)
        assertTrue(result is ValidationResult.Invalid)
        assertEquals("One-time schedule cannot have recurring days of the week.", (result as ValidationResult.Invalid).reason)
    }

    @Test
    fun reject_oneTime_withDifferentEndDate() {
        val schedule = Schedule(
            name = "Doctor Appointment",
            startTime = LocalTime.of(14, 0),
            endTime = LocalTime.of(15, 0),
            recurrenceType = RecurrenceType.ONE_TIME,
            startDate = baseDate,
            endDate = baseDate.plusDays(2)
        )

        val result = ScheduleValidator.validate(schedule)
        assertTrue(result is ValidationResult.Invalid)
        assertEquals("One-time schedule end date must match start date.", (result as ValidationResult.Invalid).reason)
    }

    @Test
    fun normalize_populatesExpectedDays() {
        val weekdaySchedule = Schedule(
            name = "  Work  ",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(17, 0),
            recurrenceType = RecurrenceType.WEEKDAYS,
            startDate = baseDate
        )
        val normalized = ScheduleValidator.normalize(weekdaySchedule)
        assertEquals("Work", normalized.name)
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            normalized.daysOfWeek
        )

        val weekendSchedule = Schedule(
            name = "Rest",
            startTime = LocalTime.of(12, 0),
            endTime = LocalTime.of(15, 0),
            recurrenceType = RecurrenceType.WEEKENDS,
            startDate = baseDate
        )
        val normalizedWeekend = ScheduleValidator.normalize(weekendSchedule)
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), normalizedWeekend.daysOfWeek)
    }
}
