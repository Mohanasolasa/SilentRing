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
* **Diagnosis**: Verify `RECEIVE_BOOT_COMPLETED` declaration and ensure `BootReceiver` invokes `ReconcileScheduleStateUseCase`.
