package com.example.autosilent.ui.home

import com.example.autosilent.domain.model.BoundaryEvent
import com.example.autosilent.domain.model.BoundaryType
import com.example.autosilent.domain.model.ScheduleOccurrence
import com.example.autosilent.domain.model.SoundMode
import com.example.autosilent.ui.util.DateTimeUiFormatter
import java.time.ZoneId

/**
 * Formatter for Home screen status card headings, reasons, and next-event transitions.
 * Ensures the primary heading truthfully reflects the hardware/actual ringer mode,
 * accurately articulates why the phone is in that state, and clearly describes upcoming transitions.
 */
object HomeStatusFormatter {

    /**
     * Formats the primary status heading based strictly on the current actual ringer mode.
     */
    fun formatPrimaryHeading(currentRingerMode: SoundMode): String {
        return if (currentRingerMode == SoundMode.SILENT) "Phone is Silent" else "Phone is Ringing"
    }

    /**
     * Formats the status reason using clear, truthful text distinguishing:
     * - "Controlled by an active schedule"
     * - "Manual override is active"
     * - "Another active schedule requires Silent mode"
     */
    fun formatStatusReason(
        currentRingerMode: SoundMode,
        activeSchedules: List<ScheduleOccurrence>,
        overriddenSchedules: List<ScheduleOccurrence>,
        totalScheduleCount: Int,
        enabledScheduleCount: Int
    ): String {
        val isSilent = currentRingerMode == SoundMode.SILENT
        val hasEffective = activeSchedules.isNotEmpty()
        val hasOverridden = overriddenSchedules.isNotEmpty()

        return if (isSilent) {
            when {
                hasEffective && hasOverridden -> {
                    if (activeSchedules.size == 1) {
                        "Another active schedule requires Silent mode (${activeSchedules.first().scheduleName})"
                    } else {
                        "Another active schedule requires Silent mode (${activeSchedules.size} active)"
                    }
                }
                hasEffective -> {
                    if (activeSchedules.size == 1) {
                        "Controlled by an active schedule (${activeSchedules.first().scheduleName})"
                    } else {
                        "Controlled by an active schedule (${activeSchedules.size} active)"
                    }
                }
                hasOverridden -> "Manual override is active (phone set to Silent on device)"
                else -> "Silent mode set manually on device"
            }
        } else {
            when {
                hasOverridden && !hasEffective -> {
                    if (overriddenSchedules.size == 1) {
                        "Manual override is active (${overriddenSchedules.first().scheduleName})"
                    } else {
                        "Manual override is active (${overriddenSchedules.size} overridden)"
                    }
                }
                hasEffective -> {
                    if (activeSchedules.size == 1) {
                        "Controlled by an active schedule (${activeSchedules.first().scheduleName}), but phone is Ringing"
                    } else {
                        "Controlled by active schedules, but phone is Ringing"
                    }
                }
                totalScheduleCount == 0 -> "No schedules created"
                enabledScheduleCount == 0 -> "All schedules are disabled"
                else -> "No active schedule"
            }
        }
    }

    /**
     * Formats the next event description identifying the actual upcoming transition clearly.
     * When the phone is already silent, an upcoming schedule start does not mean "silence starts",
     * but that silence continues with the new schedule.
     */
    fun formatNextEventDescription(
        next: BoundaryEvent?,
        currentRingerMode: SoundMode,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String? {
        if (next == null) return null
        val localTime = next.triggerInstant.atZone(zoneId).toLocalTime()
        val formattedTime = DateTimeUiFormatter.formatTime(localTime)

        return if (currentRingerMode == SoundMode.SILENT) {
            if (next.type == BoundaryType.START) {
                "Next event: ${next.scheduleName} starts at $formattedTime (Silence continues)"
            } else {
                "Next event: Silence ends at $formattedTime (${next.scheduleName})"
            }
        } else {
            if (next.type == BoundaryType.START) {
                "Next event: Silence starts at $formattedTime (${next.scheduleName})"
            } else {
                "Next event: ${next.scheduleName} ends at $formattedTime"
            }
        }
    }
}
