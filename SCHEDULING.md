# AUTO SILENT — Scheduling & Engine Specification

## 1. Schedule Data Model vs. Occurrence Model

A fundamental rule of Auto Silent is the separation between a **Schedule** (the recurring template definition) and an **Occurrence** (a specific, bounded time interval in the real world).

### 1.1 Schedule Model
```kotlin
data class Schedule(
    val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val recurrenceType: RecurrenceType, // ONE_TIME, DAILY, WEEKDAYS, WEEKENDS, CUSTOM_DAYS
    val daysOfWeek: Set<DayOfWeek>,      // e.g. {MONDAY, WEDNESDAY, FRIDAY}
    val startDate: LocalDate,            // Initial effective date
    val endDate: LocalDate? = null,      // Optional expiration date
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
```

### 1.2 Occurrence Model
```kotlin
data class ScheduleOccurrence(
    val scheduleId: Long,
    val startDateTime: ZonedDateTime,
    val endDateTime: ZonedDateTime
) {
    fun isActiveAt(dateTime: ZonedDateTime): Boolean =
        !dateTime.isBefore(startDateTime) && dateTime.isBefore(endDateTime)
}
```

---

## 2. Overnight Schedules & Recurrence Calculation

### 2.1 Daytime vs. Overnight Schedules
* **Daytime Schedule** (`startTime < endTime`):
  - Example: `07:00` to `10:00`.
  - On an applicable date $D$, occurrence is $[D\text{T}07:00, D\text{T}10:00)$.
* **Overnight Schedule** (`startTime >= endTime`):
  - Example: `22:00` to `06:00`.
  - On an applicable date $D$, occurrence begins at $D\text{T}22:00$ and ends at $(D+1)\text{T}06:00$.
  - At any given evaluation time $T$:
    - Check if $T$ falls within today's evening start: $[T_{date}\text{T}22:00, (T_{date}+1)\text{T}06:00)$, IF $T_{date}$ is an applicable day of week.
    - Check if $T$ falls within yesterday's overnight spillover: $[(T_{date}-1)\text{T}22:00, T_{date}\text{T}06:00)$, IF $(T_{date}-1)$ was an applicable day of week.

### 2.2 Supported Recurrence Types
1. **ONE_TIME**: Applies strictly on `startDate`. If `startDate < today` (or end time passed), schedule is marked completed/expired.
2. **DAILY**: Applies every day of the week.
3. **WEEKDAYS**: Applies Monday through Friday (`DayOfWeek.MONDAY..DayOfWeek.FRIDAY`).
4. **WEEKENDS**: Applies Saturday and Sunday (`DayOfWeek.SATURDAY`, `DayOfWeek.SUNDAY`).
5. **CUSTOM_DAYS**: Applies only on explicitly selected days in `daysOfWeek`.
6. **Expiration (`endDate`)**: If `endDate` is set, occurrences starting on or after `endDate.plusDays(1)` are not scheduled.

---

## 3. Global State Algorithm & Overlap Resolution

The device sound mode is never decided by an isolated schedule event. It is calculated globally from all enabled schedules.

```
                              All Enabled Schedules
                                        │
                                        ▼
                  Filter Occurrences Active at `currentTime`
                                        │
                                        ▼
                  Exclude Manually Overridden Occurrences
                                        │
                                        ▼
                     Count Active Non-Overridden Occurrences
                                   /         \
                         Count > 0             Count == 0
                            │                      │
                            ▼                      ▼
                     State: SILENT            State: RING
```

### Overlap Scenarios
* **Scenario 1 (Direct Overlap)**:
  - Schedule A: `07:00 – 10:00`
  - Schedule B: `09:00 – 11:00`
  - `07:00`: A starts -> 1 active -> SILENT.
  - `09:00`: B starts -> 2 active -> SILENT.
  - `10:00`: A ends -> 1 active (B remains active) -> SILENT.
  - `11:00`: B ends -> 0 active -> RING.
* **Scenario 2 (Contiguous Boundary)**:
  - Schedule A: `07:00 – 10:00`
  - Schedule B: `10:00 – 12:00`
  - At `10:00`: A ends, B starts. Evaluation yields 1 active (B) -> SILENT remains continuous with no sound glitch.
* **Scenario 3 (Gap)**:
  - Schedule A: `07:00 – 10:00`
  - Schedule B: `12:00 – 14:00`
  - `10:00 – 12:00`: 0 active -> RING.

---

## 4. Manual Override Lifecycle (Per-Occurrence)

Manual overrides are strictly bound to a specific occurrence of a schedule:

1. **Triggering an Override**:
   - When the user manually changes the device to `RING` while Schedule A is active, an `OccurrenceOverride` is stored for `(scheduleId = A.id, occurrenceStart = A.currentOccurrence.startDateTime)`.
2. **Isolation**:
   - The override affects *only* Schedule A's current occurrence.
   - If Schedule B starts at `09:00`, Schedule B is *not* overridden. Phone immediately switches to `SILENT`.
3. **Expiration**:
   - When Schedule A's occurrence reaches its `endDateTime`, the override is no longer applicable.
   - On the next day, Schedule A triggers normally as `SILENT`.
   - No permanent flag on the recurring `Schedule` entity is touched.

---

## 5. Alarm Strategy & Alarm Identity

### 5.1 One-Shot Alarms
* Auto Silent **never** uses repeating `AlarmManager.setRepeating()`.
* Repeating alarms suffer from drift, cannot handle custom weekday patterns accurately across DST changes, and cannot support dynamic overlap evaluation.
* The system schedules precise one-shot alarms using `AlarmManager.setExactAndAllowWhileIdle()`.

### 5.2 Alarm Identity & Payloads
Each scheduled alarm intent carries:
* `SCHEDULE_EVENT_ACTION`: Custom action string.
* `EXTRA_SCHEDULE_ID`: ID of the schedule generating this boundary event.
* `EXTRA_EVENT_TYPE`: `EVENT_START` or `EVENT_END`.
* `EXTRA_OCCURRENCE_TIMESTAMP_MILLIS`: Target trigger instant in epoch milliseconds.

### 5.3 Stale Alarm Rejection
When an alarm receiver wakes up:
1. Verify database state for `scheduleId`. If schedule was deleted or disabled, ignore the event.
2. If the current time is substantially past the expected timestamp or invalid, run a fresh reconciliation rather than blind execution.
3. Alarms serve strictly as **wake-up triggers**. The Schedule Engine evaluates the current clock time against the database to determine the actual target state.

---

## 6. Missed Event Recovery, Reboot & Time Change Reconciliation

The reconciliation algorithm is idempotent and handles all lifecycle disturbances:

```kotlin
suspend fun reconcile(currentTime: ZonedDateTime = ZonedDateTime.now()) {
    // 1. Fetch all enabled schedules
    val schedules = scheduleRepository.getEnabledSchedules()
    
    // 2. Fetch active occurrence overrides
    val overrides = scheduleRepository.getActiveOverrides()
    
    // 3. Purge expired overrides where occurrenceEnd <= currentTime
    scheduleRepository.pruneExpiredOverrides(currentTime.toInstant())
    
    // 4. Determine currently active schedules
    val activeOccurrences = scheduleEngine.getActiveOccurrences(schedules, currentTime)
    
    // 5. Filter out overridden occurrences
    val effectiveActive = activeOccurrences.filterNot { occ ->
        overrides.any { it.scheduleId == occ.scheduleId && it.occurrenceStart == occ.startDateTime.toInstant() }
    }
    
    // 6. Calculate and apply desired sound mode
    val desiredMode = if (effectiveActive.isNotEmpty()) SoundMode.SILENT else SoundMode.RING
    soundController.setSoundMode(desiredMode)
    
    // 7. Find next chronological boundary event (START or END) across all enabled schedules
    val nextEvent = scheduleEngine.calculateNextBoundaryEvent(schedules, currentTime)
    
    // 8. Schedule the next alarm
    alarmScheduler.schedule(nextEvent)
}
```

### 6.1 Reboot Recovery
* Handled by `BootReceiver` listening to `ACTION_BOOT_COMPLETED` and `ACTION_LOCKED_BOOT_COMPLETED`.
* Immediately invokes `reconcile()`. Alarms are re-registered with `AlarmManager`.

### 6.2 Date/Time & Timezone Changes
* Handled by `TimeChangeReceiver` listening to `ACTION_TIME_SET`, `ACTION_DATE_CHANGED`, and `ACTION_TIMEZONE_CHANGED`.
* Cancels obsolete alarms and invokes `reconcile()`.
