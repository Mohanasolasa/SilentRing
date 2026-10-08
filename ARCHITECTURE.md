# AUTO SILENT — System Architecture Specification

## 1. High-Level Architectural Overview

Auto Silent is built following Clean Architecture and modern Android architecture standards (MVVM, Layered Architecture, Unidirectional Data Flow).

The application enforces a strict separation between UI, domain business logic, data persistence, and system-level device interactions.

```
+-------------------------------------------------------------------+
|                        Presentation Layer                         |
|  Jetpack Compose (Material 3) + ViewModels (Kotlin StateFlow)    |
+-------------------------------------------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
|                           Domain Layer                            |
|  Pure Kotlin (No Android SDK dependencies)                        |
|  - Schedule Engine (Pure deterministic logic)                     |
|  - Domain Models (Schedule, Occurrence, SoundMode, etc.)          |
|  - Use Cases (ReconcileScheduleState, GetSchedules, etc.)         |
+-------------------------------------------------------------------+
           |                                             |
           v                                             v
+-------------------------+             +---------------------------+
|       Data Layer        |             |   System / Sched Layer    |
| - Room Database         |             | - AlarmScheduler          |
| - DataStore Preferences |             | - BroadcastReceivers      |
| - Repositories (Impl)   |             | - SoundController         |
|                         |             | - PermissionManager       |
+-------------------------+             +---------------------------+
```

---

## 2. Core Architectural Components & Responsibilities

### 2.1 Presentation Layer
* **Composables**: Pure declarative UI rendered using Material 3 design tokens. Renders UI states emitted by ViewModels and sends user intents.
* **ViewModels**: Expose immutable `StateFlow<UiState>` to Composables. Coordinate Use Cases without directly referencing Android system APIs or Room DAOs.

### 2.2 Domain Layer
* **Domain Models**:
  * `Schedule`: Persistent recurring or one-time configuration definition.
  * `ScheduleOccurrence`: Instantiated window in time with `startDateTime` and `endDateTime`.
  * `OccurrenceOverride`: Manual override tied strictly to a specific schedule occurrence (`scheduleId` and `occurrenceStartInstant`).
  * `SoundMode`: Target device audio state (`SILENT`, `RING`).
* **Schedule Engine**:
  * Fully deterministic, unit-testable pure Kotlin engine.
  * Evaluates active occurrences at any given `Instant` / `ZonedDateTime`.
  * Calculates global sound state across all active non-overridden schedules.
  * Calculates future event timestamps for alarm scheduling.
* **Use Cases**: Encapsulate single business operations (`CreateScheduleUseCase`, `UpdateScheduleUseCase`, `DeleteScheduleUseCase`, `ToggleScheduleUseCase`, `ReconcileScheduleStateUseCase`, `RecordManualOverrideUseCase`).

### 2.3 Data Layer
* **Room Database (`AppDatabase`)**:
  * `ScheduleEntity`: Persistent schedule configuration.
  * `OccurrenceOverrideEntity`: Active manual overrides tied to a specific schedule and occurrence timestamp. Overrides automatically clean up once their occurrence end timestamp passes.
* **DataStore (`UserPreferencesDataStore`)**:
  * Stores non-schedule application preferences (e.g., onboarding completion, diagnostic settings, last known app-controlled sound mode).
* **ScheduleRepository**:
  * Coordinates database reads/writes, exposes reactive `Flow<List<Schedule>>`, and manages occurrence override transactions.

### 2.4 Scheduling & System Layer
* **`AlarmScheduler`**:
  * Interacts with `AlarmManager` using `setExactAndAllowWhileIdle()`.
  * Schedules only the next immediate system event (or individual next events per enabled schedule) as single-shot alarms.
* **`BroadcastReceivers`**:
  * `AlarmReceiver`: Triggered when an alarm fires. Forwards intent to `ScheduleReconciler`.
  * `BootReceiver`: Listens to `ACTION_BOOT_COMPLETED` and `ACTION_LOCKED_BOOT_COMPLETED`. Initiates full state reconciliation.
  * `TimeChangeReceiver`: Listens to `ACTION_TIME_SET`, `ACTION_DATE_CHANGED`, and `ACTION_TIMEZONE_CHANGED`. Cancels stale alarms and re-reconciles.
* **`SoundController`**:
  * Abstraction over `AudioManager` and `NotificationManager`.
  * Encapsulates hardware sound toggles (`RINGER_MODE_SILENT` / `RINGER_MODE_NORMAL` / DND access).
* **`PermissionManager`**:
  * Central authority for checking and requesting exact alarm scheduling permissions and notification policy / DND access.

---

## 3. Data Flow & Unidirectional State Flow

### 3.1 Schedule Lifecycle Data Flow
```
User edits schedule
    -> ScheduleViewModel
    -> UpdateScheduleUseCase
    -> ScheduleRepository
    -> Room Database (Save)
    -> ReconcileScheduleStateUseCase
        -> ScheduleEngine.calculateActiveOccurrences(now)
        -> ScheduleEngine.calculateDesiredSoundState(active)
        -> SoundController.apply(desiredSoundState)
        -> ScheduleEngine.calculateNextEvent(now)
        -> AlarmScheduler.scheduleNextEvent(nextEvent)
    -> Room Flow updates ViewModel -> UI re-renders
```

### 3.2 Alarm Execution Data Flow
```
AlarmManager fires
    -> AlarmReceiver
    -> ReconcileScheduleStateUseCase
        -> Read enabled schedules & active overrides from Room
        -> ScheduleEngine calculates desired state at current Instant
        -> SoundController sets SILENT or RING
        -> ScheduleEngine computes next future event
        -> AlarmScheduler sets next one-shot alarm
```

---

## 4. Manual Override Lifecycle (Per-Occurrence)

1. A manual override occurs when the user manually changes the device sound mode while one or more schedules are active, or taps "Turn Off For Now" in the UI.
2. The override is recorded in `OccurrenceOverrideEntity` with:
   - `scheduleId`: ID of the active schedule.
   - `occurrenceStart`: The exact start timestamp (`Instant`) of the currently active occurrence.
   - `occurrenceEnd`: The exact end timestamp (`Instant`) of the active occurrence.
   - `createdAt`: Timestamp when override occurred.
3. During reconciliation:
   - A schedule is considered active if `now` is between `occurrenceStart` and `occurrenceEnd`.
   - If an active schedule has a matching `OccurrenceOverrideEntity` for that specific occurrence, its contribution is marked as `MANUALLY_OVERRIDDEN`.
4. Expiration:
   - When `now >= occurrenceEnd`, the occurrence has completed. The override is expired and pruned.
   - Subsequent occurrences (e.g. next day for a recurring schedule) are **NOT** overridden.
