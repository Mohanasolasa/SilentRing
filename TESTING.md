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

### 2. Instrumentation & Integration Tests (31 Total — PASS)
* Room DAO CRUD and Flow reactivity (`ScheduleDaoTest`, `OccurrenceOverrideDaoTest`).
* Real AlarmManager integration (`RealAlarmManagerIntegrationTest`).
* Sound control actuation (`RealSoundControlIntegrationTest`).
* Boot receiver reconciliation (`RuntimeRebootIntegrationTest`).
* Jetpack Compose UI tests (`ComposeUiIntegrationTest` - 10 tests covering navigation, empty state, long names, form inputs, toggle, delete confirmation).

---

## 3. Test Coverage & Verification Scope
* **Unit Tests**: 114 / 114 passing (Pure Kotlin engine, DST, Leap Year, Overlaps, ViewModels).
* **Instrumentation Tests**: 31 / 31 passing (Android API 34 runtime environment).
* **Execution Environment**:
  - **Emulator**: Tested thoroughly on `Medium_Phone_API_37.0` (AVD `emulator-5554`).
  - **Physical Devices**: NOT yet tested on physical OEM hardware (Samsung OneUI, Xiaomi HyperOS, Google Pixel, OnePlus). Physical device validation is the required next milestone.

---

## 4. Physical Device Validation Runbook (Phase 12)
When a physical device is attached via USB debugging (`adb devices`), execute the following sequential validation:

1. **Device Identification**:
   ```bash
   adb shell getprop ro.product.manufacturer
   adb shell getprop ro.product.model
   adb shell getprop ro.build.version.release
   adb shell getprop ro.build.version.sdk
   ```
2. **Installation & Launch**:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n com.example.autosilent/.ui.MainActivity
   ```
3. **Permission States**:
   * Verify DND / Notification Policy Access prompt: `Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`.
   * Verify Exact Alarm prompt: `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`.
   * Revoke exact alarm permission via adb or settings and verify app remains intact with error warning banner.
4. **Real Hardware Audio Actuation**:
   * Inspect current ringer mode:
     ```bash
     adb shell cmd audio get-ringer-mode
     ```
     (Returns `0` for SILENT, `1` for VIBRATE, `2` for NORMAL/RING)
   * Create short schedule and verify transition: NORMAL (2) $\to$ SILENT (0) $\to$ NORMAL (2).
5. **Reboot Verification**:
   * Reboot physical device: `adb reboot`.
   * Unlock device screen to allow credential-encrypted Room DB decryption.
   * Verify alarms restored via logcat: `adb logcat -s AutoSilentBoot AutoSilentDiag`.
6. **OEM Background & Battery Optimization**:
   * Test with screen turned off and device left idle for 15+ minutes.
   * Verify alarms fire despite OEM battery management (e.g., Samsung Device Care, Xiaomi MIUI Battery Saver).

