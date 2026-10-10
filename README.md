# AUTO SILENT

Auto Silent is a production-quality, local-first Android application that automatically manages device sound profiles (Silent ↔ Ring) based on user-defined schedules.

## Core Capabilities
* **Flexible Schedules**: One-time, Daily, Weekdays, Weekends, Selected Weekdays, Overnight (e.g. 22:00 to 06:00), and Expiration dates.
* **Deterministic Overlap Handling**: Multi-schedule overlap resolution ensures the device remains silent as long as at least one non-overridden schedule is active.
* **Occurrence-Scoped Manual Override**: Manually changing the device sound mode overrides only that specific schedule occurrence without permanently altering the recurring rule.
* **Self-Healing Reconciliation**: Complete state recalculation on every alarm trigger, device reboot, time change, and timezone shift.
* **Offline & Privacy-First**: 100% on-device operation using Room and DataStore; zero cloud or network dependencies. Zero telemetry or personal data collection.

## Architecture
Built following Clean Architecture, MVVM, and Unidirectional Data Flow:
* **Presentation**: Jetpack Compose (Material Design 3) + ViewModels (`StateFlow`).
* **Domain**: Pure Kotlin Schedule Engine (zero Android framework dependencies).
* **Data**: Room Database with SQLite foreign key cascading and DataStore preferences.
* **Platform/System**: `AlarmManager` (`setExactAndAllowWhileIdle`), `BroadcastReceivers`, and `AudioManager`.

Detailed specifications:
* [Architecture Specification](ARCHITECTURE.md)
* [Scheduling & Overlap Engine](SCHEDULING.md)
* [Permissions & Platform Policies](PERMISSIONS.md)
* [Test Matrix & Results](TESTING.md)
* [Troubleshooting & Operational Guide](TROUBLESHOOTING.md)
* [Failure & Incident Registry](FAILURE_LOG.md)

## Requirements & Permissions
* **Minimum SDK**: API 26 (Android 8.0)
* **Target SDK**: API 34 (Android 14)
* **Runtime Permissions / Special Access**:
  - `SCHEDULE_EXACT_ALARM`: Required for precise boundary wakeups. **No inexact fallback exists**; exact alarm access is required.
  - `ACCESS_NOTIFICATION_POLICY`: Required for hardware Silent mode actuation.
  - `RECEIVE_BOOT_COMPLETED`: Automatically registered for restoring alarms after device unlock.

## Quality & Verification
* **Unit Tests**: 114 / 114 passing (Pure Kotlin engine, DST, Leap Year, Overlaps, ViewModels).
* **Instrumentation Tests**: 31 / 31 passing on `Medium_Phone_API_37.0`.
* **Build Targets**: `assembleDebug` and `assembleRelease` compile cleanly.
* **Device Status**: Tested thoroughly on Android Emulator; physical OEM device testing (Samsung, Xiaomi, OnePlus) is the next milestone before public release.

