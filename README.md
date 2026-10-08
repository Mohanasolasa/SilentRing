# AUTO SILENT

Auto Silent is a production-quality Android application that automatically manages device sound profiles based on user-defined schedules.

## Core Capabilities
* **Flexible Schedules**: One-time, Daily, Weekdays, Weekends, Selected Weekdays, Overnight (e.g. 22:00 to 06:00), and Expiration dates.
* **Deterministic Overlap Handling**: Multi-schedule overlap resolution ensures the device remains silent as long as at least one non-overridden schedule is active.
* **Occurrence-Scoped Manual Override**: Manually changing the device sound mode overrides only that specific schedule occurrence without permanently altering the recurring rule.
* **Self-Healing Reconciliation**: Complete state recalculation on every alarm trigger, device reboot, time change, and timezone shift.
* **Offline & Privacy-First**: 100% on-device operation using Room and DataStore; zero cloud or network dependencies.

## Architecture
See [ARCHITECTURE.md](ARCHITECTURE.md) and [SCHEDULING.md](SCHEDULING.md) for comprehensive architectural and algorithmic specifications.
