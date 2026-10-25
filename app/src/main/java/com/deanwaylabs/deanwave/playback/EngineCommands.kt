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
}
