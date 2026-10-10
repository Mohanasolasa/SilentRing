# AUTO SILENT — Troubleshooting & Operational Guide

## Common Issues & Diagnoses

### 1. Alarm Scheduling Issues
* **Symptom**: Schedules do not trigger at the exact expected time.
* **Diagnosis**: Verify if `canScheduleExactAlarms()` is granted. On Android 14+ (API 34), exact alarms require explicit user approval in system settings.
* **Resolution**: Launch `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`.

### 2. Sound State Fails to Switch to Silent
* **Symptom**: Alarm triggers, but device ringer mode remains Ring or throws `SecurityException`.
* **Diagnosis**: Verify Notification Policy (DND) access via `NotificationManager.isNotificationPolicyAccessGranted`.
* **Resolution**: Launch `Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`.

### 3. State Inconsistency After Device Reboot
* **Symptom**: Schedules active before reboot do not re-apply sound state after phone reboots.
* **Diagnosis**: The Room database is stored in standard credential-encrypted storage and is inaccessible prior to initial user unlock. Also ensure `RECEIVE_BOOT_COMPLETED` is declared in the manifest.
* **Resolution**: Ensure the user unlocks the phone once after rebooting (`ACTION_BOOT_COMPLETED`).

### 4. Background Execution Killed on OEM Devices
* **Symptom**: Schedules work while the app is recently used, but fail after hours of device idling or screen off on Xiaomi, Samsung, OnePlus, or Huawei devices.
* **Diagnosis**: Aggressive OEM battery killers (e.g., Samsung Device Care, Xiaomi MIUI Battery Saver) restrict background alarms and freeze processes.
* **Resolution**: In device settings, set Auto Silent's battery usage to "Unrestricted" / "Don't Optimize", and allow background autostart where required by the OEM.
