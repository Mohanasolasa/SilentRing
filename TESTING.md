# AUTO SILENT — Test Matrix & Quality Verification

## Test Execution Matrix

### 1. Schedule Engine Unit Tests
* **Single Occurrence**:
  - Daytime schedule: before, during, after.
  - Overnight schedule: before, at start, midnight, morning, at end, after.
* **Recurrence Patterns**:
  - Daily
  - Weekdays (Mon-Fri)
  - Weekends (Sat-Sun)
  - Selected custom weekdays
  - One-time schedule completion
* **Overlap Scenarios**:
  - Contiguous back-to-back schedules (e.g. 07:00-10:00 and 10:00-12:00)
  - Intersecting schedules (e.g. 07:00-10:00 and 09:00-11:00)
  - Enclosed schedule (e.g. 08:00-09:00 inside 07:00-10:00)
  - Multiple overlapping schedules (3+ active simultaneously)
* **Manual Override**:
  - Override active schedule A while B is pending -> B restores Silent at its start.
  - Override active schedule A when B is already active -> A is overridden, but B keeps Silent active.
  - Occurrence end clears override.
* **Edge Cases**:
  - Clock moved backward / forward across boundary.
  - Timezone change across overnight schedule.
  - Expired schedules with `endDate` set.
  - Disabled schedules.

### 2. Instrumentation & Integration Tests
* Room DAO CRUD and Flow reactivity.
* Alarm receiver intent routing.
* Boot receiver reconciliation trigger.
* Compose UI snapshot and user interaction tests.
