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

---

## 3. Boot & Broadcast Permissions
* `android.permission.RECEIVE_BOOT_COMPLETED`:
  - Required for `BootReceiver` to restore schedules and alarms when the device powers on or reboots.
  - Normal permission (granted automatically at install time).
* `android.permission.POST_NOTIFICATIONS` (Android 13+):
  - Required if foreground or status notifications are posted to inform the user why the phone is silent.
