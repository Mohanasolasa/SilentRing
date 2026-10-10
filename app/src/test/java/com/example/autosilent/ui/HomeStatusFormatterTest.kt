package com.example.autosilent.ui

import com.example.autosilent.domain.model.BoundaryEvent
import com.example.autosilent.domain.model.BoundaryType
import com.example.autosilent.domain.model.ScheduleOccurrence
import com.example.autosilent.domain.model.SoundMode
import com.example.autosilent.ui.home.HomeStatusFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class HomeStatusFormatterTest {

    private val zone = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 10, 10, 12, 43, 0, 0, zone).toInstant()

    private fun createOccurrence(id: Long, name: String): ScheduleOccurrence {
        val start = ZonedDateTime.of(2026, 10, 10, 12, 42, 0, 0, zone).toInstant()
        val end = ZonedDateTime.of(2026, 10, 10, 12, 45, 0, 0, zone).toInstant()
        return ScheduleOccurrence(
            scheduleId = id,
            scheduleName = name,
            startInstant = start,
            endInstant = end
        )
    }

    // 1. Primary Heading Tests
    @Test
    fun primaryHeading_silent_returnsPhoneIsSilent() {
        assertEquals("Phone is Silent", HomeStatusFormatter.formatPrimaryHeading(SoundMode.SILENT))
    }

    @Test
    fun primaryHeading_ring_returnsPhoneIsRinging() {
        assertEquals("Phone is Ringing", HomeStatusFormatter.formatPrimaryHeading(SoundMode.RING))
    }

    // 2. Status Reason: Single Active Schedule
    @Test
    fun statusReason_silent_singleActiveSchedule_showsControlledByActiveSchedule() {
        val active = listOf(createOccurrence(1, "Manual override"))
        val reason = HomeStatusFormatter.formatStatusReason(
            currentRingerMode = SoundMode.SILENT,
            activeSchedules = active,
            overriddenSchedules = emptyList(),
            totalScheduleCount = 2,
            enabledScheduleCount = 2
        )
        assertEquals("Controlled by an active schedule (Manual override)", reason)
    }

    // 3. Status Reason: Multiple Active Schedules
    @Test
    fun statusReason_silent_multipleActiveSchedules_showsCount() {
        val active = listOf(
            createOccurrence(1, "Study"),
            createOccurrence(2, "Class")
        )
        val reason = HomeStatusFormatter.formatStatusReason(
            currentRingerMode = SoundMode.SILENT,
            activeSchedules = active,
            overriddenSchedules = emptyList(),
            totalScheduleCount = 2,
            enabledScheduleCount = 2
        )
        assertEquals("Controlled by an active schedule (2 active)", reason)
    }

    // 4. Status Reason: Override Active BUT another active schedule requires Silent mode
    @Test
    fun statusReason_silent_overrideWithAnotherActiveSchedule_showsAnotherActiveRequiresSilent() {
        val active = listOf(createOccurrence(2, "Manual override b"))
        val overridden = listOf(createOccurrence(1, "Manual override"))
        val reason = HomeStatusFormatter.formatStatusReason(
            currentRingerMode = SoundMode.SILENT,
            activeSchedules = active,
            overriddenSchedules = overridden,
            totalScheduleCount = 2,
            enabledScheduleCount = 2
        )
        assertEquals("Another active schedule requires Silent mode (Manual override b)", reason)
    }

    // 5. Status Reason: Silent manually on device without active schedule
    @Test
    fun statusReason_silent_noActiveSchedule_showsManualSilent() {
        val reason = HomeStatusFormatter.formatStatusReason(
            currentRingerMode = SoundMode.SILENT,
            activeSchedules = emptyList(),
            overriddenSchedules = emptyList(),
            totalScheduleCount = 1,
            enabledScheduleCount = 1
        )
        assertEquals("Silent mode set manually on device", reason)
    }

    // 6. Status Reason: Ringing with manual override active
    @Test
    fun statusReason_ring_manualOverrideActive_showsManualOverrideIsActive() {
        val overridden = listOf(createOccurrence(1, "Focus"))
        val reason = HomeStatusFormatter.formatStatusReason(
            currentRingerMode = SoundMode.RING,
            activeSchedules = emptyList(),
            overriddenSchedules = overridden,
            totalScheduleCount = 1,
            enabledScheduleCount = 1
        )
        assertEquals("Manual override is active (Focus)", reason)
    }

    // 7. Status Reason: Ringing with multiple overrides active
    @Test
    fun statusReason_ring_multipleOverridesActive_showsCount() {
        val overridden = listOf(
            createOccurrence(1, "Focus"),
            createOccurrence(2, "Reading")
        )
        val reason = HomeStatusFormatter.formatStatusReason(
            currentRingerMode = SoundMode.RING,
            activeSchedules = emptyList(),
            overriddenSchedules = overridden,
            totalScheduleCount = 2,
            enabledScheduleCount = 2
        )
        assertEquals("Manual override is active (2 overridden)", reason)
    }

    // 8. Status Reason: Ringing with active schedule (actuation mismatch or unmuted)
    @Test
    fun statusReason_ring_scheduleActive_showsMismatchedState() {
        val active = listOf(createOccurrence(1, "Focus"))
        val reason = HomeStatusFormatter.formatStatusReason(
            currentRingerMode = SoundMode.RING,
            activeSchedules = active,
            overriddenSchedules = emptyList(),
            totalScheduleCount = 1,
            enabledScheduleCount = 1
        )
        assertEquals("Controlled by an active schedule (Focus), but phone is Ringing", reason)
    }

    // 9. Status Reason: Idle ringing states
    @Test
    fun statusReason_ring_idleStates() {
        // No schedules created
        val r1 = HomeStatusFormatter.formatStatusReason(
            currentRingerMode = SoundMode.RING,
            activeSchedules = emptyList(),
            overriddenSchedules = emptyList(),
            totalScheduleCount = 0,
            enabledScheduleCount = 0
        )
        assertEquals("No schedules created", r1)

        // All schedules disabled
        val r2 = HomeStatusFormatter.formatStatusReason(
            currentRingerMode = SoundMode.RING,
            activeSchedules = emptyList(),
            overriddenSchedules = emptyList(),
            totalScheduleCount = 2,
            enabledScheduleCount = 0
        )
        assertEquals("All schedules are disabled", r2)

        // No active schedule
        val r3 = HomeStatusFormatter.formatStatusReason(
            currentRingerMode = SoundMode.RING,
            activeSchedules = emptyList(),
            overriddenSchedules = emptyList(),
            totalScheduleCount = 2,
            enabledScheduleCount = 2
        )
        assertEquals("No active schedule", r3)
    }

    // 10. Next Event Description: Silent Mode
    @Test
    fun nextEventDescription_silent_nextIsStart_showsSilenceContinues() {
        val trigger = ZonedDateTime.of(2026, 10, 10, 12, 45, 0, 0, zone).toInstant()
        val event = BoundaryEvent(
            scheduleId = 2,
            scheduleName = "Manual override b",
            type = BoundaryType.START,
            triggerInstant = trigger,
            occurrenceStart = trigger,
            occurrenceEnd = trigger.plusSeconds(120)
        )
        val text = HomeStatusFormatter.formatNextEventDescription(
            next = event,
            currentRingerMode = SoundMode.SILENT,
            zoneId = zone
        )
        assertEquals("Next event: Manual override b starts at 12:45 PM (Silence continues)", text)
    }

    @Test
    fun nextEventDescription_silent_nextIsEnd_showsSilenceEnds() {
        val trigger = ZonedDateTime.of(2026, 10, 10, 12, 45, 0, 0, zone).toInstant()
        val event = BoundaryEvent(
            scheduleId = 1,
            scheduleName = "Manual override",
            type = BoundaryType.END,
            triggerInstant = trigger,
            occurrenceStart = trigger.minusSeconds(180),
            occurrenceEnd = trigger
        )
        val text = HomeStatusFormatter.formatNextEventDescription(
            next = event,
            currentRingerMode = SoundMode.SILENT,
            zoneId = zone
        )
        assertEquals("Next event: Silence ends at 12:45 PM (Manual override)", text)
    }

    // 11. Next Event Description: Ring Mode
    @Test
    fun nextEventDescription_ring_nextIsStart_showsSilenceStarts() {
        val trigger = ZonedDateTime.of(2026, 10, 10, 12, 45, 0, 0, zone).toInstant()
        val event = BoundaryEvent(
            scheduleId = 1,
            scheduleName = "Manual override",
            type = BoundaryType.START,
            triggerInstant = trigger,
            occurrenceStart = trigger,
            occurrenceEnd = trigger.plusSeconds(180)
        )
        val text = HomeStatusFormatter.formatNextEventDescription(
            next = event,
            currentRingerMode = SoundMode.RING,
            zoneId = zone
        )
        assertEquals("Next event: Silence starts at 12:45 PM (Manual override)", text)
    }

    @Test
    fun nextEventDescription_null_returnsNull() {
        assertNull(HomeStatusFormatter.formatNextEventDescription(null, SoundMode.SILENT, zone))
    }
}
