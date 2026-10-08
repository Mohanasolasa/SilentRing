package com.example.autosilent.system

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.example.autosilent.domain.controller.SoundController
import com.example.autosilent.domain.model.SoundMode
import com.example.autosilent.scheduler.DiagnosticLogger

/**
 * Production Android implementation of [SoundController] using system [AudioManager].
 *
 * Responsibilities:
 * - Translates high-level [SoundMode] requests into Android ringer mode transitions.
 * - Enforces idempotency: does not perform redundant audio mutations when the device
 *   is already in the desired state.
 * - Protects against system crashes by safely catching [SecurityException] if platform
 *   policy access is withheld or revoked.
 * - Reads back actual hardware/system state post-actuation and reports diagnostic outcomes.
 */
class AndroidSoundController(
    private val context: Context
) : SoundController {

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val notificationManager: NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    override fun getCurrentMode(): SoundMode {
        if (audioManager == null) {
            DiagnosticLogger.w("AutoSilentSound", "AudioManager is null; defaulting getCurrentMode to RING")
            return SoundMode.RING
        }
        return when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> SoundMode.SILENT
            else -> SoundMode.RING
        }
    }

    override fun canControlSound(): Boolean {
        if (audioManager == null) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            notificationManager?.isNotificationPolicyAccessGranted == true
        } else {
            true
        }
    }

    override fun setSilent(): Boolean {
        if (audioManager == null) {
            DiagnosticLogger.w("AutoSilentSound", "SOUND_ACTUATION_FAILED: AudioManager is null")
            return false
        }

        val current = getCurrentMode()
        if (current == SoundMode.SILENT) {
            DiagnosticLogger.i("AutoSilentSound", "SOUND_ACTUATION_NOOP: Already in SILENT mode")
            return true
        }

        DiagnosticLogger.i("AutoSilentSound", "SOUND_ACTUATION_REQUEST: desired=SILENT, current=$current")

        if (!canControlSound()) {
            DiagnosticLogger.w(
                "AutoSilentSound",
                "SOUND_ACTUATION_REFUSED: Lacking notification policy access (canControlSound=false)"
            )
            return false
        }

        return try {
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            val actual = getCurrentMode()
            val success = actual == SoundMode.SILENT
            DiagnosticLogger.i(
                "AutoSilentSound",
                "SOUND_ACTUATION_RESULT: desired=SILENT, actual=$actual, success=$success"
            )
            success
        } catch (e: SecurityException) {
            DiagnosticLogger.e(
                "AutoSilentSound",
                "SOUND_ACTUATION_SECURITY_EXCEPTION: Permission denied by OS when setting SILENT",
                e
            )
            false
        } catch (t: Throwable) {
            DiagnosticLogger.e(
                "AutoSilentSound",
                "SOUND_ACTUATION_ERROR: Unexpected error setting SILENT",
                t
            )
            false
        }
    }

    override fun setRing(): Boolean {
        if (audioManager == null) {
            DiagnosticLogger.w("AutoSilentSound", "SOUND_ACTUATION_FAILED: AudioManager is null")
            return false
        }

        val current = getCurrentMode()
        if (current == SoundMode.RING) {
            DiagnosticLogger.i("AutoSilentSound", "SOUND_ACTUATION_NOOP: Already in RING mode")
            return true
        }

        DiagnosticLogger.i("AutoSilentSound", "SOUND_ACTUATION_REQUEST: desired=RING, current=$current")

        if (!canControlSound()) {
            DiagnosticLogger.w(
                "AutoSilentSound",
                "SOUND_ACTUATION_REFUSED: Lacking notification policy access (canControlSound=false)"
            )
            return false
        }

        return try {
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            val actual = getCurrentMode()
            val success = actual == SoundMode.RING
            DiagnosticLogger.i(
                "AutoSilentSound",
                "SOUND_ACTUATION_RESULT: desired=RING, actual=$actual, success=$success"
            )
            success
        } catch (e: SecurityException) {
            DiagnosticLogger.e(
                "AutoSilentSound",
                "SOUND_ACTUATION_SECURITY_EXCEPTION: Permission denied by OS when setting RING",
                e
            )
            false
        } catch (t: Throwable) {
            DiagnosticLogger.e(
                "AutoSilentSound",
                "SOUND_ACTUATION_ERROR: Unexpected error setting RING",
                t
            )
            false
        }
    }
}
