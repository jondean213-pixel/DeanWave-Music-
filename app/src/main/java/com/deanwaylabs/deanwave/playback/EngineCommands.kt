package com.deanwaylabs.deanwave.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * The wire protocol between the now-playing UI and the service's sound
 * engine, carried over MediaSession custom commands.
 *
 * Why custom commands, not a service binder (documented choice):
 * custom commands are Media3's extension point for app-specific actions.
 * They work for any controller — the app's UI today, and the system media
 * notification, Android Auto, or a wearable tomorrow — while a binder only
 * works in-process for this one activity. Transport (play/pause/next/prev)
 * needs no custom work: those are standard Player commands the controller
 * already sends.
 *
 * State sync in this phase is optimistic and single-controller: the UI
 * updates its own preset/bypass state to what it just sent. Two-way sync
 * (service pushing engine state back to the UI) is a later polish item.
 */
object EngineCommands {
    /** Args: [ARG_PRESET_NAME] (String). */
    const val APPLY_PRESET = "com.deanwaylabs.deanwave.APPLY_PRESET"
    const val ARG_PRESET_NAME = "preset_name"

    /** Args: [ARG_BYPASS] (Boolean). */
    const val SET_BYPASS = "com.deanwaylabs.deanwave.SET_BYPASS"
    const val ARG_BYPASS = "bypass"

    /** Args: [ARG_REVERB_MIX] (Float, 0.0 dry to 1.0 fully wet). */
    const val SET_REVERB_MIX = "com.deanwaylabs.deanwave.SET_REVERB_MIX"
    const val ARG_REVERB_MIX = "reverb_mix"

    /** Args: [ARG_WIDE_AMOUNT] (Float, 0.0 untouched to 1.0 side doubled). */
    const val SET_WIDE_AMOUNT = "com.deanwaylabs.deanwave.SET_WIDE_AMOUNT"
    const val ARG_WIDE_AMOUNT = "wide_amount"

    /** Args: [ARG_AGC_ENABLED] (Boolean). */
    const val SET_AGC_ENABLED = "com.deanwaylabs.deanwave.SET_AGC_ENABLED"
    const val ARG_AGC_ENABLED = "agc_enabled"

    /**
     * Sleep timer. Args: [ARG_SLEEP_MINUTES] (Long, 0 = off). Stops
     * playback when it expires; the timer lives in the service, so it
     * survives the screen turning off.
     */
    const val SET_SLEEP_TIMER = "com.deanwaylabs.deanwave.SET_SLEEP_TIMER"
    const val ARG_SLEEP_MINUTES = "sleep_minutes"

    /**
     * Sleep timer read: no args. The result extras carry
     * [ARG_SLEEP_REMAINING_MS] (Long) and [ARG_SLEEP_ACTIVE] (Boolean).
     * The screen polls this while a timer is set.
     */
    const val GET_SLEEP_REMAINING = "com.deanwaylabs.deanwave.GET_SLEEP_REMAINING"
    const val ARG_SLEEP_REMAINING_MS = "sleep_remaining_ms"
    const val ARG_SLEEP_ACTIVE = "sleep_active"

    /**
     * Visualizer read: no args. The result extras carry [ARG_ENERGIES]
     * (FloatArray, one 0..1 value per band) from the engine's own output
     * frames. The screen polls this at ~30 Hz while playing; the beams
     * draw from whatever it last returned.
     */
    const val GET_BAND_ENERGIES = "com.deanwaylabs.deanwave.GET_BAND_ENERGIES"
    const val ARG_ENERGIES = "energies"

    fun getBandEnergiesCommand(): SessionCommand =
        SessionCommand(GET_BAND_ENERGIES, Bundle.EMPTY)

    fun applyPresetCommand(): SessionCommand =
        SessionCommand(APPLY_PRESET, Bundle.EMPTY)

    fun applyPresetArgs(presetName: String): Bundle =
        Bundle().apply { putString(ARG_PRESET_NAME, presetName) }

    fun setBypassCommand(): SessionCommand =
        SessionCommand(SET_BYPASS, Bundle.EMPTY)

    fun setBypassArgs(bypass: Boolean): Bundle =
        Bundle().apply { putBoolean(ARG_BYPASS, bypass) }

    fun setReverbMixCommand(): SessionCommand =
        SessionCommand(SET_REVERB_MIX, Bundle.EMPTY)

    fun setReverbMixArgs(mix: Float): Bundle =
        Bundle().apply { putFloat(ARG_REVERB_MIX, mix) }

    fun setWideAmountCommand(): SessionCommand =
        SessionCommand(SET_WIDE_AMOUNT, Bundle.EMPTY)

    fun setWideAmountArgs(amount: Float): Bundle =
        Bundle().apply { putFloat(ARG_WIDE_AMOUNT, amount) }

    fun setAgcEnabledCommand(): SessionCommand =
        SessionCommand(SET_AGC_ENABLED, Bundle.EMPTY)

    fun setAgcEnabledArgs(enabled: Boolean): Bundle =
        Bundle().apply { putBoolean(ARG_AGC_ENABLED, enabled) }

    fun setSleepTimerCommand(): SessionCommand =
        SessionCommand(SET_SLEEP_TIMER, Bundle.EMPTY)

    fun setSleepTimerArgs(minutes: Long): Bundle =
        Bundle().apply { putLong(ARG_SLEEP_MINUTES, minutes) }

    fun getSleepRemainingCommand(): SessionCommand =
        SessionCommand(GET_SLEEP_REMAINING, Bundle.EMPTY)
}
