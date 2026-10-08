package com.example.autosilent.data.local

import androidx.room.TypeConverter
import com.example.autosilent.domain.model.RecurrenceType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class Converters {

    @TypeConverter
    fun fromLocalTime(time: LocalTime?): String? = time?.toString()

    @TypeConverter
    fun toLocalTime(value: String?): LocalTime? = value?.let { LocalTime.parse(it) }

    @TypeConverter
    fun fromLocalDate(date: LocalDate?): String? = date?.toString()

    @TypeConverter
    fun toLocalDate(value: String?): LocalDate? = value?.let { LocalDate.parse(it) }

    @TypeConverter
    fun fromInstant(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun toInstant(value: Long?): Instant? = value?.let { Instant.ofEpochMilli(it) }

    @TypeConverter
    fun fromRecurrenceType(type: RecurrenceType?): String? = type?.name

    @TypeConverter
    fun toRecurrenceType(value: String?): RecurrenceType? =
        value?.let { runCatching { RecurrenceType.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromDayOfWeekSet(days: Set<DayOfWeek>?): String? {
        if (days == null || days.isEmpty()) return ""
        return days.joinToString(",") { it.name }
    }

    @TypeConverter
    fun toDayOfWeekSet(value: String?): Set<DayOfWeek> {
        if (value.isNullOrBlank()) return emptySet()
        return value.split(",")
            .mapNotNull { name -> runCatching { DayOfWeek.valueOf(name.trim()) }.getOrNull() }
            .toSet()
    }
}
