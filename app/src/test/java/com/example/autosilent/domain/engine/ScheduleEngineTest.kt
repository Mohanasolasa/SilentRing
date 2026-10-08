package com.example.autosilent.domain.engine

import com.example.autosilent.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ScheduleEngineTest {

    private val zoneUtc = ZoneId.of("UTC")
    private val zoneKolkata = ZoneId.of("Asia/Kolkata")
    private val zoneNewYork = ZoneId.of("America/New_York")

    // 2026-10-12 is Monday
    private val mondayDate = LocalDate.of(2026, 10, 12)
    private val tuesdayDate = LocalDate.of(2026, 10, 13)

    // ==========================================
    // 1. Basic Boundaries & Interval Semantics [start <= t < end)
    // ==========================================

    @Test
    fun basic_halfOpenIntervalSemantics_verified() {
        val schedule = Schedule(
            id = 1L,
            name = "Focus",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = mondayDate
        )

        // 08:59:59 -> Before start (Inactive, RING)
        val before = mondayDate.atTime(8, 59, 59).atZone(zoneUtc).toInstant()
        val evalBefore = ScheduleEngine.evaluateGlobalState(listOf(schedule), emptyList(), before, zoneUtc)
        assertEquals(SoundMode.RING, evalBefore.desiredSoundMode)
        assertTrue(evalBefore.activeOccurrences.isEmpty())

        // 09:00:00 -> Exactly at start (Active, SILENT)
        val atStart = mondayDate.atTime(9, 0, 0).atZone(zoneUtc).toInstant()
        val evalStart = ScheduleEngine.evaluateGlobalState(listOf(schedule), emptyList(), atStart, zoneUtc)
        assertEquals(SoundMode.SILENT, evalStart.desiredSoundMode)
        assertEquals(1, evalStart.activeOccurrences.size)

        // 10:00:00 -> During interval (Active, SILENT)
        val during = mondayDate.atTime(10, 0, 0).atZone(zoneUtc).toInstant()
        val evalDuring = ScheduleEngine.evaluateGlobalState(listOf(schedule), emptyList(), during, zoneUtc)
        assertEquals(SoundMode.SILENT, evalDuring.desiredSoundMode)
        assertEquals(1, evalDuring.activeOccurrences.size)

        // 11:00:00 -> Exactly at end (Inactive, RING)
        val atEnd = mondayDate.atTime(11, 0, 0).atZone(zoneUtc).toInstant()
        val evalEnd = ScheduleEngine.evaluateGlobalState(listOf(schedule), emptyList(), atEnd, zoneUtc)
        assertEquals(SoundMode.RING, evalEnd.desiredSoundMode)
        assertTrue(evalEnd.activeOccurrences.isEmpty())

        // 11:00:01 -> After end (Inactive, RING)
        val after = mondayDate.atTime(11, 0, 1).atZone(zoneUtc).toInstant()
        val evalAfter = ScheduleEngine.evaluateGlobalState(listOf(schedule), emptyList(), after, zoneUtc)
        assertEquals(SoundMode.RING, evalAfter.desiredSoundMode)
        assertTrue(evalAfter.activeOccurrences.isEmpty())
    }

    // ==========================================
    // 2. Overnight Schedules (e.g. 22:00 -> 06:00)
    // ==========================================

    @Test
    fun overnightSchedule_acrossMidnight_evaluatedCorrectly() {
        // Monday 22:00 -> Tuesday 06:00
        val overnight = Schedule(
            id = 2L,
            name = "Sleep",
            startTime = LocalTime.of(22, 0),
            endTime = LocalTime.of(6, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = mondayDate
        )

        // 21:59 Monday -> RING
        val t2159 = mondayDate.atTime(21, 59).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.RING, ScheduleEngine.evaluateGlobalState(listOf(overnight), emptyList(), t2159, zoneUtc).desiredSoundMode)

        // 22:00 Monday -> SILENT
        val t2200 = mondayDate.atTime(22, 0).atZone(zoneUtc).toInstant()
        val eval2200 = ScheduleEngine.evaluateGlobalState(listOf(overnight), emptyList(), t2200, zoneUtc)
        assertEquals(SoundMode.SILENT, eval2200.desiredSoundMode)
        assertEquals(1, eval2200.activeOccurrences.size)

        // 23:59 Monday -> SILENT
        val t2359 = mondayDate.atTime(23, 59).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.SILENT, ScheduleEngine.evaluateGlobalState(listOf(overnight), emptyList(), t2359, zoneUtc).desiredSoundMode)

        // 00:01 Tuesday (Midnight passed) -> SILENT
        val t0001 = tuesdayDate.atTime(0, 1).atZone(zoneUtc).toInstant()
        val eval0001 = ScheduleEngine.evaluateGlobalState(listOf(overnight), emptyList(), t0001, zoneUtc)
        assertEquals(SoundMode.SILENT, eval0001.desiredSoundMode)
        assertEquals(1, eval0001.activeOccurrences.size)

        // 05:59 Tuesday -> SILENT
        val t0559 = tuesdayDate.atTime(5, 59).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.SILENT, ScheduleEngine.evaluateGlobalState(listOf(overnight), emptyList(), t0559, zoneUtc).desiredSoundMode)

        // 06:00 Tuesday (End boundary) -> RING
        val t0600 = tuesdayDate.atTime(6, 0).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.RING, ScheduleEngine.evaluateGlobalState(listOf(overnight), emptyList(), t0600, zoneUtc).desiredSoundMode)

        // 06:01 Tuesday -> RING
        val t0601 = tuesdayDate.atTime(6, 1).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.RING, ScheduleEngine.evaluateGlobalState(listOf(overnight), emptyList(), t0601, zoneUtc).desiredSoundMode)
    }

    // ==========================================
    // 3. Recurrence Patterns & Constraints
    // ==========================================

    @Test
    fun recurrence_weekdays_doesNotTriggerOnWeekends() {
        val weekdaySchedule = Schedule(
            id = 3L,
            name = "Work",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(17, 0),
            recurrenceType = RecurrenceType.WEEKDAYS,
            daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            startDate = mondayDate
        )

        // Monday 10:00 -> Active
        val mondayActive = mondayDate.atTime(10, 0).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.SILENT, ScheduleEngine.evaluateGlobalState(listOf(weekdaySchedule), emptyList(), mondayActive, zoneUtc).desiredSoundMode)

        // Saturday 10:00 (2026-10-17) -> Inactive
        val saturday = LocalDate.of(2026, 10, 17).atTime(10, 0).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.RING, ScheduleEngine.evaluateGlobalState(listOf(weekdaySchedule), emptyList(), saturday, zoneUtc).desiredSoundMode)
    }

    @Test
    fun recurrence_weekends_triggersOnlyOnSaturdayAndSunday() {
        val weekendSchedule = Schedule(
            id = 4L,
            name = "Weekend Relax",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(14, 0),
            recurrenceType = RecurrenceType.WEEKENDS,
            daysOfWeek = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            startDate = mondayDate
        )

        // Sunday 11:00 (2026-10-18) -> Active
        val sunday = LocalDate.of(2026, 10, 18).atTime(11, 0).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.SILENT, ScheduleEngine.evaluateGlobalState(listOf(weekendSchedule), emptyList(), sunday, zoneUtc).desiredSoundMode)

        // Wednesday 11:00 (2026-10-14) -> Inactive
        val wednesday = LocalDate.of(2026, 10, 14).atTime(11, 0).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.RING, ScheduleEngine.evaluateGlobalState(listOf(weekendSchedule), emptyList(), wednesday, zoneUtc).desiredSoundMode)
    }

    @Test
    fun recurrence_customDays_triggersOnlyOnSelectedDays() {
        val customSchedule = Schedule(
            id = 5L,
            name = "Gym",
            startTime = LocalTime.of(6, 0),
            endTime = LocalTime.of(8, 0),
            recurrenceType = RecurrenceType.CUSTOM_DAYS,
            daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            startDate = mondayDate
        )

        // Monday -> Active
        val mondayTime = mondayDate.atTime(7, 0).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.SILENT, ScheduleEngine.evaluateGlobalState(listOf(customSchedule), emptyList(), mondayTime, zoneUtc).desiredSoundMode)

        // Tuesday -> Inactive
        val tuesdayTime = tuesdayDate.atTime(7, 0).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.RING, ScheduleEngine.evaluateGlobalState(listOf(customSchedule), emptyList(), tuesdayTime, zoneUtc).desiredSoundMode)
    }

    @Test
    fun recurrence_oneTime_doesNotRepeatNextDay() {
        val oneTime = Schedule(
            id = 6L,
            name = "Doctor",
            startTime = LocalTime.of(14, 0),
            endTime = LocalTime.of(15, 0),
            recurrenceType = RecurrenceType.ONE_TIME,
            startDate = mondayDate,
            endDate = mondayDate
        )

        // Monday 14:30 -> Active
        val mondayTime = mondayDate.atTime(14, 30).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.SILENT, ScheduleEngine.evaluateGlobalState(listOf(oneTime), emptyList(), mondayTime, zoneUtc).desiredSoundMode)

        // Tuesday 14:30 -> Inactive
        val tuesdayTime = tuesdayDate.atTime(14, 30).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.RING, ScheduleEngine.evaluateGlobalState(listOf(oneTime), emptyList(), tuesdayTime, zoneUtc).desiredSoundMode)
    }

    @Test
    fun recurrence_expiredSchedule_isIgnored() {
        val expired = Schedule(
            id = 7L,
            name = "Temporary Class",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = mondayDate,
            endDate = mondayDate // Expires on Monday
        )

        // Monday 10:00 -> Active
        val mondayTime = mondayDate.atTime(10, 0).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.SILENT, ScheduleEngine.evaluateGlobalState(listOf(expired), emptyList(), mondayTime, zoneUtc).desiredSoundMode)

        // Tuesday 10:00 -> Inactive due to expiration
        val tuesdayTime = tuesdayDate.atTime(10, 0).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.RING, ScheduleEngine.evaluateGlobalState(listOf(expired), emptyList(), tuesdayTime, zoneUtc).desiredSoundMode)
    }

    @Test
    fun disabledSchedule_isNeverActive() {
        val disabled = Schedule(
            id = 8L,
            name = "Paused",
            enabled = false,
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = mondayDate
        )

        val time = mondayDate.atTime(10, 0).atZone(zoneUtc).toInstant()
        val eval = ScheduleEngine.evaluateGlobalState(listOf(disabled), emptyList(), time, zoneUtc)
        assertEquals(SoundMode.RING, eval.desiredSoundMode)
        assertTrue(eval.activeOccurrences.isEmpty())
    }

    // ==========================================
    // 4. Overlapping Schedules
    // ==========================================

    @Test
    fun overlap_studyAndMeeting_evaluatedGlobally() {
        // Study: 07:00 -> 10:00
        val study = Schedule(
            id = 10L,
            name = "Study",
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(10, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = mondayDate
        )

        // Meeting: 09:00 -> 11:00
        val meeting = Schedule(
            id = 11L,
            name = "Meeting",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = mondayDate
        )

        val schedules = listOf(study, meeting)

        // 07:00 -> Silent (Study starts)
        val t0700 = mondayDate.atTime(7, 0).atZone(zoneUtc).toInstant()
        val e0700 = ScheduleEngine.evaluateGlobalState(schedules, emptyList(), t0700, zoneUtc)
        assertEquals(SoundMode.SILENT, e0700.desiredSoundMode)
        assertEquals(1, e0700.activeOccurrences.size)

        // 08:30 -> Silent (Study active)
        val t0830 = mondayDate.atTime(8, 30).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.SILENT, ScheduleEngine.evaluateGlobalState(schedules, emptyList(), t0830, zoneUtc).desiredSoundMode)

        // 09:00 -> Silent (Study and Meeting both active)
        val t0900 = mondayDate.atTime(9, 0).atZone(zoneUtc).toInstant()
        val e0900 = ScheduleEngine.evaluateGlobalState(schedules, emptyList(), t0900, zoneUtc)
        assertEquals(SoundMode.SILENT, e0900.desiredSoundMode)
        assertEquals(2, e0900.activeOccurrences.size)

        // 10:00 -> Silent (Study ends, but Meeting remains active!)
        val t1000 = mondayDate.atTime(10, 0).atZone(zoneUtc).toInstant()
        val e1000 = ScheduleEngine.evaluateGlobalState(schedules, emptyList(), t1000, zoneUtc)
        assertEquals(SoundMode.SILENT, e1000.desiredSoundMode)
        assertEquals(1, e1000.activeOccurrences.size)
        assertEquals("Meeting", e1000.activeOccurrences[0].scheduleName)

        // 11:00 -> Ring (Meeting ends, no remaining active schedules)
        val t1100 = mondayDate.atTime(11, 0).atZone(zoneUtc).toInstant()
        val e1100 = ScheduleEngine.evaluateGlobalState(schedules, emptyList(), t1100, zoneUtc)
        assertEquals(SoundMode.RING, e1100.desiredSoundMode)
        assertTrue(e1100.activeOccurrences.isEmpty())
    }

    @Test
    fun overlap_contiguousSchedules_seamlessTransitionAtBoundary() {
        val a = Schedule(
            id = 20L,
            name = "A",
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(10, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = mondayDate
        )
        val b = Schedule(
            id = 21L,
            name = "B",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(12, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = mondayDate
        )

        // Exactly at 10:00: A ends, B starts. Global state MUST be SILENT without audio glitch!
        val t1000 = mondayDate.atTime(10, 0).atZone(zoneUtc).toInstant()
        val eval = ScheduleEngine.evaluateGlobalState(listOf(a, b), emptyList(), t1000, zoneUtc)
        assertEquals(SoundMode.SILENT, eval.desiredSoundMode)
        assertEquals(1, eval.activeOccurrences.size)
        assertEquals("B", eval.activeOccurrences[0].scheduleName)
    }

    @Test
    fun overlap_nestedAndMultipleSchedules() {
        val outer = Schedule(
            id = 30L,
            name = "Workday",
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(18, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = mondayDate
        )
        val inner1 = Schedule(
            id = 31L,
            name = "Morning Standup",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(10, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = mondayDate
        )
        val inner2 = Schedule(
            id = 32L,
            name = "Sprint Review",
            startTime = LocalTime.of(9, 30),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = mondayDate
        )

        // 09:45 -> 3 schedules active
        val t0945 = mondayDate.atTime(9, 45).atZone(zoneUtc).toInstant()
        val eval = ScheduleEngine.evaluateGlobalState(listOf(outer, inner1, inner2), emptyList(), t0945, zoneUtc)
        assertEquals(SoundMode.SILENT, eval.desiredSoundMode)
        assertEquals(3, eval.activeOccurrences.size)
    }

    // ==========================================
    // 5. Manual Override & Overlap Scenarios
    // ==========================================

    @Test
    fun manualOverride_withOverlappingSchedules_scenarioVerified() {
        // Study: 07:00 -> 10:00
        val study = Schedule(
            id = 40L,
            name = "Study",
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(10, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = mondayDate
        )

        // Meeting: 09:00 -> 11:00
        val meeting = Schedule(
            id = 41L,
            name = "Meeting",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(11, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = mondayDate
        )

        val schedules = listOf(study, meeting)

        // Monday Study occurrence: 07:00 -> 10:00
        val studyMondayStart = mondayDate.atTime(7, 0).atZone(zoneUtc).toInstant()
        val studyMondayEnd = mondayDate.atTime(10, 0).atZone(zoneUtc).toInstant()

        // At 08:00, user manually switches phone to Ring -> Study Monday occurrence is overridden
        val studyOverride = OccurrenceOverride(
            id = 1L,
            scheduleId = study.id,
            occurrenceStart = studyMondayStart,
            occurrenceEnd = studyMondayEnd
        )
        val overrides = listOf(studyOverride)

        // 08:00 -> RING (Study is overridden, Meeting not started)
        val t0800 = mondayDate.atTime(8, 0).atZone(zoneUtc).toInstant()
        val e0800 = ScheduleEngine.evaluateGlobalState(schedules, overrides, t0800, zoneUtc)
        assertEquals(SoundMode.RING, e0800.desiredSoundMode)
        assertEquals(1, e0800.activeOccurrences.size)
        assertEquals(1, e0800.overriddenOccurrences.size)
        assertTrue(e0800.effectiveOccurrences.isEmpty())

        // 09:00 -> SILENT! (Meeting starts and is NOT overridden)
        val t0900 = mondayDate.atTime(9, 0).atZone(zoneUtc).toInstant()
        val e0900 = ScheduleEngine.evaluateGlobalState(schedules, overrides, t0900, zoneUtc)
        assertEquals(SoundMode.SILENT, e0900.desiredSoundMode)
        assertEquals(2, e0900.activeOccurrences.size)
        assertEquals(1, e0900.overriddenOccurrences.size)
        assertEquals(1, e0900.effectiveOccurrences.size)
        assertEquals("Meeting", e0900.effectiveOccurrences[0].scheduleName)

        // 10:00 -> SILENT (Study ends, Meeting remains active)
        val t1000 = mondayDate.atTime(10, 0).atZone(zoneUtc).toInstant()
        val e1000 = ScheduleEngine.evaluateGlobalState(schedules, overrides, t1000, zoneUtc)
        assertEquals(SoundMode.SILENT, e1000.desiredSoundMode)
        assertEquals(1, e1000.effectiveOccurrences.size)

        // 11:00 -> RING (Meeting ends)
        val t1100 = mondayDate.atTime(11, 0).atZone(zoneUtc).toInstant()
        val e1100 = ScheduleEngine.evaluateGlobalState(schedules, overrides, t1100, zoneUtc)
        assertEquals(SoundMode.RING, e1100.desiredSoundMode)

        // Tuesday 07:30 -> SILENT (Study on Tuesday is NOT overridden!)
        val tuesday0730 = tuesdayDate.atTime(7, 30).atZone(zoneUtc).toInstant()
        val eTuesday = ScheduleEngine.evaluateGlobalState(schedules, overrides, tuesday0730, zoneUtc)
        assertEquals(SoundMode.SILENT, eTuesday.desiredSoundMode)
        assertEquals(1, eTuesday.effectiveOccurrences.size)
        assertEquals("Study", eTuesday.effectiveOccurrences[0].scheduleName)
    }

    @Test
    fun manualOverride_allActiveOccurrencesOverridden_returnsRing() {
        val s1 = Schedule(id = 50L, name = "A", startTime = LocalTime.of(8, 0), endTime = LocalTime.of(10, 0), recurrenceType = RecurrenceType.DAILY, startDate = mondayDate)
        val s2 = Schedule(id = 51L, name = "B", startTime = LocalTime.of(8, 30), endTime = LocalTime.of(9, 30), recurrenceType = RecurrenceType.DAILY, startDate = mondayDate)

        val o1 = OccurrenceOverride(
            scheduleId = s1.id,
            occurrenceStart = mondayDate.atTime(8, 0).atZone(zoneUtc).toInstant(),
            occurrenceEnd = mondayDate.atTime(10, 0).atZone(zoneUtc).toInstant()
        )
        val o2 = OccurrenceOverride(
            scheduleId = s2.id,
            occurrenceStart = mondayDate.atTime(8, 30).atZone(zoneUtc).toInstant(),
            occurrenceEnd = mondayDate.atTime(9, 30).atZone(zoneUtc).toInstant()
        )

        val t0900 = mondayDate.atTime(9, 0).atZone(zoneUtc).toInstant()
        val eval = ScheduleEngine.evaluateGlobalState(listOf(s1, s2), listOf(o1, o2), t0900, zoneUtc)

        assertEquals(SoundMode.RING, eval.desiredSoundMode)
        assertEquals(2, eval.activeOccurrences.size)
        assertEquals(2, eval.overriddenOccurrences.size)
        assertTrue(eval.effectiveOccurrences.isEmpty())
    }

    // ==========================================
    // 6. Next Boundary Calculation
    // ==========================================

    @Test
    fun nextBoundary_calculatesImmediateNextEvent() {
        val schedule = Schedule(
            id = 60L,
            name = "Study",
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(10, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = mondayDate
        )

        // At 06:30 -> Next boundary is START at 07:00
        val t0630 = mondayDate.atTime(6, 30).atZone(zoneUtc).toInstant()
        val nextFrom0630 = ScheduleEngine.calculateNextBoundaryEvent(listOf(schedule), t0630, zoneUtc)
        assertNotNull(nextFrom0630)
        assertEquals(BoundaryType.START, nextFrom0630?.type)
        assertEquals(mondayDate.atTime(7, 0).atZone(zoneUtc).toInstant(), nextFrom0630?.triggerInstant)

        // At 08:00 (during schedule) -> Next boundary is END at 10:00
        val t0800 = mondayDate.atTime(8, 0).atZone(zoneUtc).toInstant()
        val nextFrom0800 = ScheduleEngine.calculateNextBoundaryEvent(listOf(schedule), t0800, zoneUtc)
        assertNotNull(nextFrom0800)
        assertEquals(BoundaryType.END, nextFrom0800?.type)
        assertEquals(mondayDate.atTime(10, 0).atZone(zoneUtc).toInstant(), nextFrom0800?.triggerInstant)

        // At 11:00 -> Next boundary is Tuesday START at 07:00
        val t1100 = mondayDate.atTime(11, 0).atZone(zoneUtc).toInstant()
        val nextFrom1100 = ScheduleEngine.calculateNextBoundaryEvent(listOf(schedule), t1100, zoneUtc)
        assertNotNull(nextFrom1100)
        assertEquals(BoundaryType.START, nextFrom1100?.type)
        assertEquals(tuesdayDate.atTime(7, 0).atZone(zoneUtc).toInstant(), nextFrom1100?.triggerInstant)
    }

    @Test
    fun nextBoundary_overnightSchedule_boundaryAccuratelyFound() {
        val overnight = Schedule(
            id = 70L,
            name = "Sleep",
            startTime = LocalTime.of(22, 0),
            endTime = LocalTime.of(6, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = mondayDate
        )

        // At 02:00 Tuesday (inside overnight) -> Next boundary is END at 06:00 Tuesday
        val t0200 = tuesdayDate.atTime(2, 0).atZone(zoneUtc).toInstant()
        val next = ScheduleEngine.calculateNextBoundaryEvent(listOf(overnight), t0200, zoneUtc)
        assertNotNull(next)
        assertEquals(BoundaryType.END, next?.type)
        assertEquals(tuesdayDate.atTime(6, 0).atZone(zoneUtc).toInstant(), next?.triggerInstant)
    }

    // ==========================================
    // 7. Timezones & Stale-Event Protection
    // ==========================================

    @Test
    fun timezones_differentZones_deterministicEvaluation() {
        val schedule = Schedule(
            id = 80L,
            name = "Local Work",
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(17, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = mondayDate
        )

        // Check in Asia/Kolkata vs America/New_York
        val kolkataInstant = mondayDate.atTime(9, 30).atZone(zoneKolkata).toInstant()
        val evalKolkata = ScheduleEngine.evaluateGlobalState(listOf(schedule), emptyList(), kolkataInstant, zoneKolkata)
        assertEquals(SoundMode.SILENT, evalKolkata.desiredSoundMode)

        val nyInstant = mondayDate.atTime(9, 30).atZone(zoneNewYork).toInstant()
        val evalNy = ScheduleEngine.evaluateGlobalState(listOf(schedule), emptyList(), nyInstant, zoneNewYork)
        assertEquals(SoundMode.SILENT, evalNy.desiredSoundMode)
    }

    @Test
    fun staleEventProtection_detectsObsoleteAlarms() {
        val schedule = Schedule(
            id = 90L,
            name = "Study",
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(10, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = mondayDate
        )

        val validEvent = BoundaryEvent(
            scheduleId = 90L,
            scheduleName = "Study",
            type = BoundaryType.START,
            triggerInstant = mondayDate.atTime(7, 0).atZone(zoneUtc).toInstant(),
            occurrenceStart = mondayDate.atTime(7, 0).atZone(zoneUtc).toInstant(),
            occurrenceEnd = mondayDate.atTime(10, 0).atZone(zoneUtc).toInstant()
        )

        // 1. Valid event matching schedule
        assertTrue(ScheduleEngine.isEventValid(validEvent, schedule, zoneUtc))

        // 2. Schedule was edited (start time changed to 08:00)
        val edited = schedule.copy(startTime = LocalTime.of(8, 0))
        assertFalse(ScheduleEngine.isEventValid(validEvent, edited, zoneUtc))

        // 3. Schedule was disabled
        val disabled = schedule.copy(enabled = false)
        assertFalse(ScheduleEngine.isEventValid(validEvent, disabled, zoneUtc))

        // 4. Schedule was deleted (null)
        assertFalse(ScheduleEngine.isEventValid(validEvent, null, zoneUtc))
    }
}
