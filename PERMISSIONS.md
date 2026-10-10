# AUTO SILENT — Android Permissions & System Policies

## 1. Exact Alarm Permissions & Google Play Policy

### 1.1 Android API History for Exact Alarms
* **Android 12 (API 31)**:
  - Introduced `android.permission.SCHEDULE_EXACT_ALARM`.
  - Granted by default to apps upon installation, but users could revoke it in system settings (`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`).
* **Android 13 (API 33)**:
  - Introduced `android.permission.USE_EXACT_ALARM`.
  - Automatically granted to apps without user interaction.
  - **Google Play Policy Restriction**: Google strictly restricts `USE_EXACT_ALARM` to apps whose *core functionality* is an Alarm Clock, Timer, or Calendar. Apps using it outside these specific categories risk app rejection during Play Store review.
* **Android 14 (API 34+)**:
  - `SCHEDULE_EXACT_ALARM` is **no longer granted by default** for newly installed apps targeting Android 14+ (unless transferred via backup or pre-installed).
  - Apps must check `AlarmManager.canScheduleExactAlarms()` before calling `setExactAndAllowWhileIdle()`.
  - If false, the app must guide the user to `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (or `ACTION_APPLICATION_DETAILS_SETTINGS`).

### 1.2 Auto Silent Permission Decision Matrix
1. **Declared Permission**: `android.permission.SCHEDULE_EXACT_ALARM`.
   - Reason: It complies fully with Google Play policy for scheduling apps, avoiding unexpected policy rejections associated with `USE_EXACT_ALARM`.
2. **Runtime Verification**:
   - On Android 12+ (API >= 31):
     ```kotlin
     val alarmManager = context.getSystemService(AlarmManager::class.java)
     val canSchedule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
         alarmManager.canScheduleExactAlarms()
     } else {
         true
     }
     ```
   - If `canSchedule` is `false`, the UI displays a clear explanation card and provides a direct button launching `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`.

### 1.3 Exact-Alarm Policy Requirements & Runtime Constraints
* **Policy Rule**: Maintain `SCHEDULE_EXACT_ALARM`. Do NOT declare `USE_EXACT_ALARM` unless there is a separately justified and approved policy basis. Auto Silent is a sound scheduler, not a bedside alarm-clock or calendar application, so Google Play will reject attempts to misclassify it merely to obtain automatic exact-alarm privileges.
* **Revocable Special Access**: `SCHEDULE_EXACT_ALARM` is user-controlled Special App Access (`AppOpsManager.OP_SCHEDULE_EXACT_ALARM`). Users can revoke it at any time in system settings. The application detects revocation dynamically via `canScheduleExactAlarms()` without crashing and presents informative UI guidance.
* **Precision Limitation**: Android exact alarms via `setExactAndAllowWhileIdle()` operate under system resource constraints and are described by Google as *nearly precise* (typically executed within tens of milliseconds up to small latency deltas depending on CPU wake locks, power management, and Doze unbatching). They are NOT guaranteed to execute at the exact sub-millisecond instant.

---

## 2. Audio Control & Notification Policy Access (Do Not Disturb)

### 2.1 Android Audio APIs & Silent Mode
* To set the device to `SILENT`:
  ```kotlin
  audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
  ```
  or `RINGER_MODE_VIBRATE` (depending on user configuration).
* On Android 7.0 (API 24) and higher, modifying the ringer mode to `RINGER_MODE_SILENT` or changing DND policy requires **Notification Policy Access** (`android.permission.ACCESS_NOTIFICATION_POLICY`).
* Without this access, attempting to set `ringerMode = RINGER_MODE_SILENT` can throw a `SecurityException`.

### 2.2 Notification Policy Access Verification
* Runtime check:
  ```kotlin
  val notificationManager = context.getSystemService(NotificationManager::class.java)
  val hasDndAccess = notificationManager.isNotificationPolicyAccessGranted
  ```
* If `false`, the app guides the user to grant access via `Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`.
* Without this access, switching between Ring and Silent is blocked by the Android security layer. Actuation fails safely with diagnostic logging, leaving the ringer mode unchanged.

### 2.3 Actuation Failure & Security Handling
* The app verifies actual ringer mode readback via `audioManager.ringerMode`.
* Silent mode transitions are only reported successful when the hardware state accurately confirms `RINGER_MODE_SILENT`.
* No DND policy overrides or vibration modes are introduced outside the strict Silent/Ring scope.

---

## 3. Boot & Broadcast Permissions
* `android.permission.RECEIVE_BOOT_COMPLETED`:
  - Required for `BootReceiver` to restore schedules and alarms when the device powers on or reboots.
  - Normal permission (granted automatically at install time).
  - Recovery runs after direct-boot / user unlock (`ACTION_BOOT_COMPLETED`) because the Room database is stored in credential-encrypted storage.
* `android.permission.POST_NOTIFICATIONS` (Android 13+):
  - Declared for posting status notifications informing the user when a schedule is active or overridden.

---

## 4. Exact Alarm Denial Behavior & Fallback Clarification
* **NO INEXACT FALLBACK**: The application does **NOT** implement an inexact or windowed alarm fallback.
* When `canScheduleExactAlarms()` returns `false`:
  1. `AndroidAlarmScheduler.scheduleBoundaryEvent()` refuses to schedule and returns `false`.
  2. Diagnostic log records `SCHEDULING_FAILED: canScheduleExactAlarms=false`.
  3. The UI renders a prominent warning banner informing the user that automatic transitions are paused.
  4. Persistent schedule definitions remain intact in Room.
  5. As soon as the user grants exact alarm permission in system settings, returning to the app immediately reconciles and schedules the next boundary alarm.

---

## 5. OEM Battery & Background Restrictions
* Third-party OEM skins (Samsung OneUI, Xiaomi MIUI/HyperOS, OnePlus OxygenOS, Huawei EMUI) frequently kill background processes or block `AlarmManager` wakeups when aggressive battery optimization is enabled.
* While the application works on standard Android AOSP runtimes using `setExactAndAllowWhileIdle()`, physical device validation on major OEM devices is required prior to public distribution.
* Users encountering missed transitions on OEM devices should disable battery optimization for Auto Silent in system settings.
