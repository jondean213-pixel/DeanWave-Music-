package com.deanwaylabs.deanwave.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Gentle automatic gain control: evens out volume between tracks so a
 * quiet track and a loud track play at a similar level.
 *
 * How it behaves, on purpose:
 * - A slow level follower watches the signal's peak. The target gain
 *   brings that level toward -12 dB, never more than +12 dB of makeup
 *   and never more than -12 dB of cut.
 * - Gain turns down fast (~100 ms) so a sudden loud track never blasts,
 *   and turns up slowly (~1 s) so quiet passages never pump.
 * - Below a silence gate the gain holds instead of chasing noise — a
 *   quiet intro must not make the noise floor swell.
 * - It sits BEFORE the limiter in [SoundEngine], and the limiter stays
 *   last, so the two never fight: AGC sets the level, the limiter only
 *   catches peaks.
 *
 * Disabled is an exact bypass: [processFrame] returns without touching
 * the frame.
 */
class Agc {
    companion object {
        private const val TARGET_LEVEL = 0.25f
        private const val MAX_GAIN = 4f
        private const val MIN_GAIN = 0.25f
        private const val SILENCE_GATE = 0.001f
        private const val ENVELOPE_ATTACK_MS = 50f
        private const val ENVELOPE_RELEASE_MS = 1000f
        private const val GAIN_ATTACK_MS = 100f
        private const val GAIN_RELEASE_MS = 1000f
    }

    private var enabled = false
    private var gain = 1f
    private var envelope = 0f
    private var envelopeAttackCoeff = 0f
    private var envelopeReleaseCoeff = 0f
    private var gainAttackCoeff = 0f
    private var gainReleaseCoeff = 0f

    fun configure(sampleRate: Int) {
        envelopeAttackCoeff = smoothingCoeff(ENVELOPE_ATTACK_MS, sampleRate)
        envelopeReleaseCoeff = smoothingCoeff(ENVELOPE_RELEASE_MS, sampleRate)
        gainAttackCoeff = smoothingCoeff(GAIN_ATTACK_MS, sampleRate)
        gainReleaseCoeff = smoothingCoeff(GAIN_RELEASE_MS, sampleRate)
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    fun isEnabled(): Boolean = enabled

    /** Processes one interleaved frame in place (frame size == channel count). */
    fun processFrame(frame: FloatArray) {
        if (!enabled) return
        var peak = 0f
        for (sample in frame) peak = max(peak, abs(sample))
        envelope = if (peak > envelope) {
            envelope + (peak - envelope) * envelopeAttackCoeff
        } else {
            envelope * (1f - envelopeReleaseCoeff)
        }
        if (envelope >= SILENCE_GATE) {
            val targetGain = (TARGET_LEVEL / envelope).coerceIn(MIN_GAIN, MAX_GAIN)
            val coeff = if (targetGain < gain) gainAttackCoeff else gainReleaseCoeff
            gain += (targetGain - gain) * coeff
        }
        for (i in frame.indices) frame[i] = frame[i] * gain
    }

    fun reset() {
        envelope = 0f
        gain = 1f
    }

    private fun smoothingCoeff(timeMs: Float, sampleRate: Int): Float =
        1f - exp(-1.0 / (timeMs / 1000f * sampleRate)).toFloat()
}
