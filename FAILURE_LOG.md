# AUTO SILENT — Failure Log & Incident Registry

This registry tracks any technical failures, root causes, and resolutions encountered across all development phases.

| Failure ID | Phase | Severity | Short Description | Fix Result | Status |
|---|---|---|---|---|---|
| INC-001 | Phase 9 | Medium | Integration tests constructing `now.minusMinutes()` with `startDate = LocalDate.now()` failed when executed across midnight. | Derived `startDate` dynamically from actual occurrence local date. All 29 tests pass. | RESOLVED |
| INC-002 | Phase 10 | Low | Rapid asynchronous button clicks on "Save" queued multiple coroutines before state updated. | Set `isSaving = true` synchronously before coroutine dispatch with immediate guard. | RESOLVED |
| INC-003 | Phase 12 | Low | Status card subtitle ambiguously printed "Silent because of <Name>" when schedule was named "Manual override", and next event claimed "silence starts" while phone was already silent. | Introduced `HomeStatusFormatter` separating actual ringer mode, clear state reasons ("Controlled by an active schedule", "Manual override is active", "Another active schedule requires Silent mode"), and next-event transition clarity ("Silence continues"). | RESOLVED |

