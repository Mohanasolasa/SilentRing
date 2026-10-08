package com.example.autosilent.data.local

import com.example.autosilent.domain.model.RecurrenceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class ConvertersTest {

    private val converters = Converters()

    @Test
    fun localTime_roundTrip_preservesTime() {
        val original = LocalTime.of(14, 30, 45)
        val serialized = converters.fromLocalTime(original)
        val deserialized = converters.toLocalTime(serialized)

        assertEquals(original, deserialized)
    }

    @Test
    fun localTime_null_handledSafely() {
        assertNull(converters.fromLocalTime(null))
        assertNull(converters.toLocalTime(null))
    }

    @Test
    fun localDate_roundTrip_preservesDate() {
        val original = LocalDate.of(2026, 10, 12)
        val serialized = converters.fromLocalDate(original)
        val deserialized = converters.toLocalDate(serialized)

        assertEquals(original, deserialized)
    }

    @Test
    fun localDate_null_handledSafely() {
        assertNull(converters.fromLocalDate(null))
        assertNull(converters.toLocalDate(null))
    }

    @Test
    fun instant_roundTrip_preservesEpochMilli() {
        val original = Instant.ofEpochMilli(1728394800000L)
        val serialized = converters.fromInstant(original)
        val deserialized = converters.toInstant(serialized)

        assertEquals(original, deserialized)
    }

    @Test
    fun instant_null_handledSafely() {
        assertNull(converters.fromInstant(null))
        assertNull(converters.toInstant(null))
    }

    @Test
    fun recurrenceType_roundTrip_preservesEnum() {
        RecurrenceType.values().forEach { type ->
            val serialized = converters.fromRecurrenceType(type)
            val deserialized = converters.toRecurrenceType(serialized)
            assertEquals(type, deserialized)
        }
    }

    @Test
    fun recurrenceType_nullAndInvalid_handledSafely() {
        assertNull(converters.fromRecurrenceType(null))
        assertNull(converters.toRecurrenceType(null))
        assertNull(converters.toRecurrenceType("NON_EXISTENT_ENUM"))
    }

    @Test
    fun dayOfWeekSet_roundTrip_preservesSet() {
        val original = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        val serialized = converters.fromDayOfWeekSet(original)
        val deserialized = converters.toDayOfWeekSet(serialized)

        assertEquals(original, deserialized)
    }

    @Test
    fun dayOfWeekSet_emptyAndNull_returnsEmptySet() {
        assertEquals("", converters.fromDayOfWeekSet(emptySet()))
        assertEquals("", converters.fromDayOfWeekSet(null))
        assertTrue(converters.toDayOfWeekSet(null).isEmpty())
        assertTrue(converters.toDayOfWeekSet("").isEmpty())
    }

    @Test
    fun dayOfWeekSet_invalidItem_filtersSafely() {
        val result = converters.toDayOfWeekSet("MONDAY,INVALID_DAY,FRIDAY")
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), result)
    }
}
