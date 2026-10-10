package com.example.autosilent.ui.util

import com.example.autosilent.domain.model.RecurrenceType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateTimeUiFormatter {

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    fun formatTime(time: LocalTime): String = time.format(timeFormatter)

    fun formatDate(date: LocalDate): String = date.format(dateFormatter)

    fun formatTimeRange(startTime: LocalTime, endTime: LocalTime, isOvernight: Boolean): String {
        val startStr = formatTime(startTime)
        val endStr = formatTime(endTime)
        return if (isOvernight) {
            "$startStr – $endStr (+1 day)"
        } else {
            "$startStr – $endStr"
        }
    }

    fun formatRecurrence(recurrenceType: RecurrenceType, daysOfWeek: Set<DayOfWeek>): String {
        return when (recurrenceType) {
            RecurrenceType.ONE_TIME -> "One-time"
            RecurrenceType.DAILY -> "Every day"
            RecurrenceType.WEEKDAYS -> "Weekdays (Mon–Fri)"
            RecurrenceType.WEEKENDS -> "Weekends (Sat–Sun)"
            RecurrenceType.CUSTOM_DAYS -> {
                if (daysOfWeek.isEmpty()) {
                    "No days selected"
                } else {
                    val orderedDays = listOf(
                        DayOfWeek.MONDAY,
                        DayOfWeek.TUESDAY,
                        DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY,
                        DayOfWeek.FRIDAY,
                        DayOfWeek.SATURDAY,
                        DayOfWeek.SUNDAY
                    ).filter { it in daysOfWeek }

                    orderedDays.joinToString(", ") { day ->
                        day.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
                    }
                }
            }
        }
    }
}
