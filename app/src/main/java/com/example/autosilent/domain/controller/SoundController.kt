package com.example.autosilent.domain.controller

import com.example.autosilent.domain.model.SoundMode

/**
 * Domain-level abstraction for observing and mutating device audio state.
 *
 * Adheres strictly to Clean Architecture:
 * - Domain layer has zero dependencies on Android framework or AudioManager.
 * - Platform-specific audio manipulation is encapsulated in the system implementation.
 */
interface SoundController {

    /**
     * Reads the current device sound mode.
     *
     * @return [SoundMode.SILENT] if device ringer is muted/silent, or [SoundMode.RING] otherwise.
     */
    fun getCurrentMode(): SoundMode

    /**
     * Observes continuous updates of the device sound mode.
     * Default implementation produces a single emission of [getCurrentMode].
     */
    fun observeCurrentMode(): kotlinx.coroutines.flow.Flow<SoundMode> =
        kotlinx.coroutines.flow.flowOf(getCurrentMode())

    /**
     * Requests transitioning the device sound mode to [SoundMode.SILENT].
     *
     * @return true if the device is confirmed in SILENT mode, false if refused or failed.
     */
    fun setSilent(): Boolean

    /**
     * Requests transitioning the device sound mode to [SoundMode.RING].
     *
     * @return true if the device is confirmed in RING/NORMAL mode, false if refused or failed.
     */
    fun setRing(): Boolean

    /**
     * Determines whether the application currently possesses the platform permissions
     * and capabilities required to adjust ringer mode.
     *
     * @return true if ringer mode mutations can be performed, false otherwise.
     */
    fun canControlSound(): Boolean
}

object NoOpSoundController : SoundController {
    override fun getCurrentMode(): SoundMode = SoundMode.RING
    override fun observeCurrentMode(): kotlinx.coroutines.flow.Flow<SoundMode> =
        kotlinx.coroutines.flow.flowOf(SoundMode.RING)
    override fun setSilent(): Boolean = true
    override fun setRing(): Boolean = true
    override fun canControlSound(): Boolean = true
}
