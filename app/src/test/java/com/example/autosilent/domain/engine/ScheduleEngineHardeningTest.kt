package com.example.autosilent.domain.engine

import com.example.autosilent.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ScheduleEngineHardeningTest {

    private val zoneNewYork = ZoneId.of("America/New_York") // Observes DST (EDT/EST)
    private val zoneUtc = ZoneId.of("UTC")

    // ==========================================
    // 1. Daylight Saving Time (DST) Tests
    // ==========================================

    @Test
    fun dst_springForward_scheduleSpanningTransition_correctInstantCalculations() {
        // In US Eastern, 2026-03-08 is spring-forward day (clocks jump from 02:00 to 03:00).
        // Schedule: 01:00 to 04:00 daily
        val springDay = LocalDate.of(2026, 3, 8)
        val schedule = Schedule(
            id = 101L,
            name = "DST Spring Schedule",
            startTime = LocalTime.of(1, 0),
            endTime = LocalTime.of(4, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = springDay
        )

        val occ = ScheduleEngine.getOccurrenceInitiatedOnDate(schedule, springDay, zoneNewYork)
        assertNotNull(occ)
        assertTrue(occ!!.endInstant.isAfter(occ.startInstant))

        // In 2026-03-08, 01:00 is EST (-05:00) -> 06:00 UTC
        // 04:00 is EDT (-04:00) -> 08:00 UTC
        // Duration is 2 hours real time (7200 seconds)
        val durationSeconds = Duration.between(occ.startInstant, occ.endInstant).seconds
        assertEquals(2 * 3600L, durationSeconds)

        // At 01:30 local (06:30 UTC) -> active
        val insideInstant = occ.startInstant.plusSeconds(1800)
        assertTrue(occ.isActiveAt(insideInstant))
    }

    @Test
    fun dst_fallBack_scheduleSpanningTransition_correctInstantCalculations() {
        // In US Eastern, 2026-11-01 is fall-back day (clocks fall back from 02:00 to 01:00).
        // Schedule: 00:30 to 03:30 daily
        val fallDay = LocalDate.of(2026, 11, 1)
        val schedule = Schedule(
            id = 102L,
            name = "DST Fall Schedule",
            startTime = LocalTime.of(0, 30),
            endTime = LocalTime.of(3, 30),
            recurrenceType = RecurrenceType.DAILY,
            startDate = fallDay
        )

        val occ = ScheduleEngine.getOccurrenceInitiatedOnDate(schedule, fallDay, zoneNewYork)
        assertNotNull(occ)
        assertTrue(occ!!.endInstant.isAfter(occ.startInstant))

        // On fall-back day, the span 00:30 EDT to 03:30 EST spans 4 hours of real wall time
        val durationSeconds = Duration.between(occ.startInstant, occ.endInstant).seconds
        assertEquals(4 * 3600L, durationSeconds)

        // Active state during transition
        val insideInstant = occ.startInstant.plusSeconds(3600)
        assertTrue(occ.isActiveAt(insideInstant))
    }

    @Test
    fun dst_overnightSchedule_acrossSpringForward_evaluatedAccurately() {
        // Overnight schedule 23:00 to 07:00 across spring forward (2026-03-07 23:00 to 2026-03-08 07:00)
        val startDay = LocalDate.of(2026, 3, 7)
        val overnight = Schedule(
            id = 103L,
            name = "Overnight Spring Sleep",
            startTime = LocalTime.of(23, 0),
            endTime = LocalTime.of(7, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = startDay
        )

        val occ = ScheduleEngine.getOccurrenceInitiatedOnDate(overnight, startDay, zoneNewYork)
        assertNotNull(occ)
        assertTrue(occ!!.endInstant.isAfter(occ.startInstant))

        // Normal 8h interval becomes 7h real time due to 1h lost at 02:00
        val durationSeconds = Duration.between(occ.startInstant, occ.endInstant).seconds
        assertEquals(7 * 3600L, durationSeconds)

        // Active at 03:30 EDT on March 8
        val morningInstant = LocalDate.of(2026, 3, 8).atTime(3, 30).atZone(zoneNewYork).toInstant()
        assertTrue(occ.isActiveAt(morningInstant))

        val eval = ScheduleEngine.evaluateGlobalState(listOf(overnight), emptyList(), morningInstant, zoneNewYork)
        assertEquals(SoundMode.SILENT, eval.desiredSoundMode)
    }

    // ==========================================
    // 2. Clock Rollback & Pure Arbitrary Time Movement Tests
    // ==========================================

    @Test
    fun clockMovement_evaluatingOutOfOrder_isCompletelyPureAndDeterministic() {
        val schedule = Schedule(
            id = 201L,
            name = "Study",
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(10, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = LocalDate.of(2026, 10, 12)
        )
        val baseDate = LocalDate.of(2026, 10, 12)

        val t0730 = baseDate.atTime(7, 30).atZone(zoneUtc).toInstant()
        val t0800 = baseDate.atTime(8, 0).atZone(zoneUtc).toInstant()
        val t0900 = baseDate.atTime(9, 0).atZone(zoneUtc).toInstant()
        val t1000 = baseDate.atTime(10, 0).atZone(zoneUtc).toInstant()
        val t1030 = baseDate.atTime(10, 30).atZone(zoneUtc).toInstant()

        // Direct evaluation of 07:30
        val direct0730 = ScheduleEngine.evaluateGlobalState(listOf(schedule), emptyList(), t0730, zoneUtc)
        assertEquals(SoundMode.RING, direct0730.desiredSoundMode)

        // Evaluate in jumping sequence: 09:00 -> 10:30 -> 08:00 -> 07:30 (Rollback!)
        val eval0900 = ScheduleEngine.evaluateGlobalState(listOf(schedule), emptyList(), t0900, zoneUtc)
        assertEquals(SoundMode.SILENT, eval0900.desiredSoundMode)

        val eval1000 = ScheduleEngine.evaluateGlobalState(listOf(schedule), emptyList(), t1000, zoneUtc)
        assertEquals(SoundMode.RING, eval1000.desiredSoundMode)

        val eval1030 = ScheduleEngine.evaluateGlobalState(listOf(schedule), emptyList(), t1030, zoneUtc)
        assertEquals(SoundMode.RING, eval1030.desiredSoundMode)

        val eval0800 = ScheduleEngine.evaluateGlobalState(listOf(schedule), emptyList(), t0800, zoneUtc)
        assertEquals(SoundMode.SILENT, eval0800.desiredSoundMode)

        // Rolling back to 07:30 must produce IDENTICAL result to direct0730
        val rollback0730 = ScheduleEngine.evaluateGlobalState(listOf(schedule), emptyList(), t0730, zoneUtc)
        assertEquals(direct0730, rollback0730)

        // Moving backward across END (from 10:30 back to 09:30)
        val t0930 = baseDate.atTime(9, 30).atZone(zoneUtc).toInstant()
        val rollback0930 = ScheduleEngine.evaluateGlobalState(listOf(schedule), emptyList(), t0930, zoneUtc)
        assertEquals(SoundMode.SILENT, rollback0930.desiredSoundMode)

        // Moving backward across START (from 08:30 back to 07:45)
        val t0745 = baseDate.atTime(7, 45).atZone(zoneUtc).toInstant()
        val rollback0745 = ScheduleEngine.evaluateGlobalState(listOf(schedule), emptyList(), t0745, zoneUtc)
        assertEquals(SoundMode.RING, rollback0745.desiredSoundMode)
    }

    // ==========================================
    // 3. Leap Year & Calendar Edge Cases
    // ==========================================

    @Test
    fun calendar_leapYear_february29_evaluatedCorrectly() {
        // 2028 is a leap year
        val feb28 = LocalDate.of(2028, 2, 28)
        val feb29 = LocalDate.of(2028, 2, 29)
        val mar01 = LocalDate.of(2028, 3, 1)

        val leapSchedule = Schedule(
            id = 301L,
            name = "Leap Day Routine",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(12, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = feb28
        )

        // Feb 28 11:00 -> Active
        val tFeb28 = feb28.atTime(11, 0).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.SILENT, ScheduleEngine.evaluateGlobalState(listOf(leapSchedule), emptyList(), tFeb28, zoneUtc).desiredSoundMode)

        // Feb 29 11:00 -> Active on Leap Day!
        val tFeb29 = feb29.atTime(11, 0).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.SILENT, ScheduleEngine.evaluateGlobalState(listOf(leapSchedule), emptyList(), tFeb29, zoneUtc).desiredSoundMode)

        // Mar 01 11:00 -> Active after Leap Day!
        val tMar01 = mar01.atTime(11, 0).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.SILENT, ScheduleEngine.evaluateGlobalState(listOf(leapSchedule), emptyList(), tMar01, zoneUtc).desiredSoundMode)
    }

    @Test
    fun calendar_yearBoundary_december31_to_january1_transitionCorrect() {
        val dec31 = LocalDate.of(2026, 12, 31)
        val jan01 = LocalDate.of(2027, 1, 1)

        val newYearOvernight = Schedule(
            id = 302L,
            name = "New Year Eve Sleep",
            startTime = LocalTime.of(23, 0),
            endTime = LocalTime.of(7, 0),
            recurrenceType = RecurrenceType.DAILY,
            startDate = dec31
        )

        // Dec 31 23:30 -> Silent
        val tDec31 = dec31.atTime(23, 30).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.SILENT, ScheduleEngine.evaluateGlobalState(listOf(newYearOvernight), emptyList(), tDec31, zoneUtc).desiredSoundMode)

        // Jan 01 02:00 -> Silent in the new year!
        val tJan01 = jan01.atTime(2, 0).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.SILENT, ScheduleEngine.evaluateGlobalState(listOf(newYearOvernight), emptyList(), tJan01, zoneUtc).desiredSoundMode)

        // Jan 01 07:00 -> Ring (end boundary)
        val tEndJan01 = jan01.atTime(7, 0).atZone(zoneUtc).toInstant()
        assertEquals(SoundMode.RING, ScheduleEngine.evaluateGlobalState(listOf(newYearOvernight), emptyList(), tEndJan01, zoneUtc).desiredSoundMode)
    }

    // ==========================================
    // 4. Multi-Layer Overlap Matrix
    // ==========================================

    @Test
    fun overlap_ABC_layered_scenarios_verified() {
        val date = LocalDate.of(2026, 10, 12)
        // A: 07-10
        val a = Schedule(id = 401L, name = "A", startTime = LocalTime.of(7, 0), endTime = LocalTime.of(10, 0), recurrenceType = RecurrenceType.DAILY, startDate = date)
        // B: 09-11
        val b = Schedule(id = 402L, name = "B", startTime = LocalTime.of(9, 0), endTime = LocalTime.of(11, 0), recurrenceType = RecurrenceType.DAILY, startDate = date)
        // C: 08-12
        val c = Schedule(id = 403L, name = "C", startTime = LocalTime.of(8, 0), endTime = LocalTime.of(12, 0), recurrenceType = RecurrenceType.DAILY, startDate = date)

        val schedules = listOf(a, b, c)

        // 07-08 -> A
        val t0730 = date.atTime(7, 30).atZone(zoneUtc).toInstant()
        val e0730 = ScheduleEngine.evaluateGlobalState(schedules, emptyList(), t0730, zoneUtc)
        assertEquals(SoundMode.SILENT, e0730.desiredSoundMode)
        assertEquals(listOf("A"), e0730.activeOccurrences.map { it.scheduleName })

        // 08-09 -> A + C
        val t0830 = date.atTime(8, 30).atZone(zoneUtc).toInstant()
        val e0830 = ScheduleEngine.evaluateGlobalState(schedules, emptyList(), t0830, zoneUtc)
        assertEquals(SoundMode.SILENT, e0830.desiredSoundMode)
        assertEquals(setOf("A", "C"), e0830.activeOccurrences.map { it.scheduleName }.toSet())

        // 09-10 -> A + B + C
        val t0930 = date.atTime(9, 30).atZone(zoneUtc).toInstant()
        val e0930 = ScheduleEngine.evaluateGlobalState(schedules, emptyList(), t0930, zoneUtc)
        assertEquals(SoundMode.SILENT, e0930.desiredSoundMode)
        assertEquals(setOf("A", "B", "C"), e0930.activeOccurrences.map { it.scheduleName }.toSet())

        // 10-11 -> B + C (A has ended)
        val t1030 = date.atTime(10, 30).atZone(zoneUtc).toInstant()
        val e1030 = ScheduleEngine.evaluateGlobalState(schedules, emptyList(), t1030, zoneUtc)
        assertEquals(SoundMode.SILENT, e1030.desiredSoundMode)
        assertEquals(setOf("B", "C"), e1030.activeOccurrences.map { it.scheduleName }.toSet())

        // 11-12 -> C (B has ended)
        val t1130 = date.atTime(11, 30).atZone(zoneUtc).toInstant()
        val e1130 = ScheduleEngine.evaluateGlobalState(schedules, emptyList(), t1130, zoneUtc)
        assertEquals(SoundMode.SILENT, e1130.desiredSoundMode)
        assertEquals(listOf("C"), e1130.activeOccurrences.map { it.scheduleName })

        // 12:00+ -> Ring (C has ended)
        val t1200 = date.atTime(12, 0).atZone(zoneUtc).toInstant()
        val e1200 = ScheduleEngine.evaluateGlobalState(schedules, emptyList(), t1200, zoneUtc)
        assertEquals(SoundMode.RING, e1200.desiredSoundMode)
        assertTrue(e1200.activeOccurrences.isEmpty())
    }

    @Test
    fun overlap_nested_schedules_allActiveInsideOuter() {
        val date = LocalDate.of(2026, 10, 12)
        val outer = Schedule(id = 501L, name = "A", startTime = LocalTime.of(7, 0), endTime = LocalTime.of(12, 0), recurrenceType = RecurrenceType.DAILY, startDate = date)
        val inner1 = Schedule(id = 502L, name = "B", startTime = LocalTime.of(8, 0), endTime = LocalTime.of(9, 0), recurrenceType = RecurrenceType.DAILY, startDate = date)
        val inner2 = Schedule(id = 503L, name = "C", startTime = LocalTime.of(9, 0), endTime = LocalTime.of(10, 0), recurrenceType = RecurrenceType.DAILY, startDate = date)
        val inner3 = Schedule(id = 504L, name = "D", startTime = LocalTime.of(10, 0), endTime = LocalTime.of(11, 0), recurrenceType = RecurrenceType.DAILY, startDate = date)

        val schedules = listOf(outer, inner1, inner2, inner3)

        // Inside inner1: 08:30 -> A and B active
        val t0830 = date.atTime(8, 30).atZone(zoneUtc).toInstant()
        assertEquals(setOf("A", "B"), ScheduleEngine.getActiveOccurrences(schedules, t0830, zoneUtc).map { it.scheduleName }.toSet())

        // Inside inner2: 09:30 -> A and C active
        val t0930 = date.atTime(9, 30).atZone(zoneUtc).toInstant()
        assertEquals(setOf("A", "C"), ScheduleEngine.getActiveOccurrences(schedules, t0930, zoneUtc).map { it.scheduleName }.toSet())

        // Inside inner3: 10:30 -> A and D active
        val t1030 = date.atTime(10, 30).atZone(zoneUtc).toInstant()
        assertEquals(setOf("A", "D"), ScheduleEngine.getActiveOccurrences(schedules, t1030, zoneUtc).map { it.scheduleName }.toSet())

        // Outside all inners: 11:30 -> only A active
        val t1130 = date.atTime(11, 30).atZone(zoneUtc).toInstant()
        assertEquals(listOf("A"), ScheduleEngine.getActiveOccurrences(schedules, t1130, zoneUtc).map { it.scheduleName })
    }

    @Test
    fun overlap_oneMinuteOverlap_and_oneMinuteGap() {
        val date = LocalDate.of(2026, 10, 12)

        // One-minute overlap: S1: 08:00-09:01, S2: 09:00-10:00
        val s1 = Schedule(id = 601L, name = "S1", startTime = LocalTime.of(8, 0), endTime = LocalTime.of(9, 1), recurrenceType = RecurrenceType.DAILY, startDate = date)
        val s2 = Schedule(id = 602L, name = "S2", startTime = LocalTime.of(9, 0), endTime = LocalTime.of(10, 0), recurrenceType = RecurrenceType.DAILY, startDate = date)

        // At 09:00:30 -> both active
        val t090030 = date.atTime(9, 0, 30).atZone(zoneUtc).toInstant()
        val evalOverlap = ScheduleEngine.evaluateGlobalState(listOf(s1, s2), emptyList(), t090030, zoneUtc)
        assertEquals(SoundMode.SILENT, evalOverlap.desiredSoundMode)
        assertEquals(2, evalOverlap.activeOccurrences.size)

        // One-minute gap: G1: 08:00-09:00, G2: 09:01-10:00
        val g1 = Schedule(id = 603L, name = "G1", startTime = LocalTime.of(8, 0), endTime = LocalTime.of(9, 0), recurrenceType = RecurrenceType.DAILY, startDate = date)
        val g2 = Schedule(id = 604L, name = "G2", startTime = LocalTime.of(9, 1), endTime = LocalTime.of(10, 0), recurrenceType = RecurrenceType.DAILY, startDate = date)

        // At 09:00:30 -> inside gap (neither active -> RING)
        val evalGap = ScheduleEngine.evaluateGlobalState(listOf(g1, g2), emptyList(), t090030, zoneUtc)
        assertEquals(SoundMode.RING, evalGap.desiredSoundMode)
        assertTrue(evalGap.activeOccurrences.isEmpty())
    }

    // ==========================================
    // 5. Manual Override Matrix Hardening
    // ==========================================

    @Test
    fun manualOverride_threeActiveOccurrences_twoOverridden_remainsSilent() {
        val date = LocalDate.of(2026, 10, 12)
        val s1 = Schedule(id = 701L, name = "S1", startTime = LocalTime.of(8, 0), endTime = LocalTime.of(12, 0), recurrenceType = RecurrenceType.DAILY, startDate = date)
        val s2 = Schedule(id = 702L, name = "S2", startTime = LocalTime.of(9, 0), endTime = LocalTime.of(11, 0), recurrenceType = RecurrenceType.DAILY, startDate = date)
        val s3 = Schedule(id = 703L, name = "S3", startTime = LocalTime.of(9, 30), endTime = LocalTime.of(10, 30), recurrenceType = RecurrenceType.DAILY, startDate = date)

        val o1 = OccurrenceOverride(
            scheduleId = s1.id,
            occurrenceStart = date.atTime(8, 0).atZone(zoneUtc).toInstant(),
            occurrenceEnd = date.atTime(12, 0).atZone(zoneUtc).toInstant()
        )
        val o2 = OccurrenceOverride(
            scheduleId = s2.id,
            occurrenceStart = date.atTime(9, 0).atZone(zoneUtc).toInstant(),
            occurrenceEnd = date.atTime(11, 0).atZone(zoneUtc).toInstant()
        )

        // At 10:00 -> s1 and s2 overridden, but s3 is ACTIVE and NOT overridden!
        val t1000 = date.atTime(10, 0).atZone(zoneUtc).toInstant()
        val eval = ScheduleEngine.evaluateGlobalState(listOf(s1, s2, s3), listOf(o1, o2), t1000, zoneUtc)

        assertEquals(SoundMode.SILENT, eval.desiredSoundMode)
        assertEquals(3, eval.activeOccurrences.size)
        assertEquals(2, eval.overriddenOccurrences.size)
        assertEquals(1, eval.effectiveOccurrences.size)
        assertEquals("S3", eval.effectiveOccurrences[0].scheduleName)
    }

    // ==========================================
    // 6. Next-Boundary Hardening & Deterministic Tie-Breaking
    // ==========================================

    @Test
    fun nextBoundary_tieBreakingRule_isStrictlyDeterministic() {
        val date = LocalDate.of(2026, 10, 12)

        // S1 ends at 10:00
        val s1 = Schedule(id = 801L, name = "Ending Schedule", startTime = LocalTime.of(8, 0), endTime = LocalTime.of(10, 0), recurrenceType = RecurrenceType.DAILY, startDate = date)
        // S2 starts at 10:00
        val s2 = Schedule(id = 802L, name = "Starting Schedule", startTime = LocalTime.of(10, 0), endTime = LocalTime.of(12, 0), recurrenceType = RecurrenceType.DAILY, startDate = date)

        // At 09:30, both s1 and s2 have a boundary at exactly 10:00!
        // S1 has type = END at 10:00
        // S2 has type = START at 10:00
        // Our tie-breaking rule states:
        // 1. triggerInstant ascending
        // 2. BoundaryType.START (ordinal 0) before BoundaryType.END (ordinal 1)
        // 3. scheduleId ascending
        val t0930 = date.atTime(9, 30).atZone(zoneUtc).toInstant()
        val nextBoundary = ScheduleEngine.calculateNextBoundaryEvent(listOf(s1, s2), t0930, zoneUtc)

        assertNotNull(nextBoundary)
        assertEquals(date.atTime(10, 0).atZone(zoneUtc).toInstant(), nextBoundary?.triggerInstant)
        assertEquals(BoundaryType.START, nextBoundary?.type)
        assertEquals(s2.id, nextBoundary?.scheduleId)
    }

    @Test
    fun nextBoundary_noFutureBoundaries_returnsNull() {
        val date = LocalDate.of(2026, 10, 12)
        val expired = Schedule(
            id = 803L,
            name = "Expired One Time",
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(10, 0),
            recurrenceType = RecurrenceType.ONE_TIME,
            startDate = date,
            endDate = date
        )

        // Current time is past the end of the one-time schedule: 2026-10-12 11:00
        val t1100 = date.atTime(11, 0).atZone(zoneUtc).toInstant()
        val next = ScheduleEngine.calculateNextBoundaryEvent(listOf(expired), t1100, zoneUtc)
        assertNull(next)
    }

    // ==========================================
    // 7. Property / Invariant Verification
    // ==========================================

    @Test
    fun invariants_verifiedAcrossSchedules() {
        val date = LocalDate.of(2026, 10, 12)
        val testSchedules = listOf(
            Schedule(id = 901L, name = "Normal", startTime = LocalTime.of(9, 0), endTime = LocalTime.of(17, 0), recurrenceType = RecurrenceType.DAILY, startDate = date),
            Schedule(id = 902L, name = "Overnight", startTime = LocalTime.of(22, 0), endTime = LocalTime.of(6, 0), recurrenceType = RecurrenceType.DAILY, startDate = date),
            Schedule(id = 903L, name = "Weekdays", startTime = LocalTime.of(10, 0), endTime = LocalTime.of(11, 0), recurrenceType = RecurrenceType.WEEKDAYS, startDate = date)
        )

        for (schedule in testSchedules) {
            val occ = ScheduleEngine.getOccurrenceInitiatedOnDate(schedule, date, zoneUtc)
            assertNotNull(occ)
            // Invariant: startInstant < endInstant
            assertTrue("startInstant must be strictly before endInstant", occ!!.endInstant.isAfter(occ.startInstant))

            // Invariant: Cannot be active before start
            assertFalse(occ.isActiveAt(occ.startInstant.minusMillis(1)))

            // Invariant: Cannot be active at or after end
            assertFalse(occ.isActiveAt(occ.endInstant))
            assertFalse(occ.isActiveAt(occ.endInstant.plusMillis(1)))

            // Invariant: Active exactly at start
            assertTrue(occ.isActiveAt(occ.startInstant))
        }
    }
}
