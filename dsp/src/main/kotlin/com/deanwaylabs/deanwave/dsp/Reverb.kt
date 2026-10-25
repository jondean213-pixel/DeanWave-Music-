package com.deanwaylabs.deanwave.dsp

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Freeverb-style stereo reverb: parallel comb filters feeding series
 * allpass filters, per channel. Implemented here from the public-domain
 * Freeverb algorithm's structure (comb + allpass topology and its classic
 * delay tunings) — the code itself is original to this project.
 *
 * Mix 0 is an exact bypass: [processFrame] returns without running a
 * single filter, so "off" costs nothing and changes nothing. The limiter
 * downstream in [SoundEngine] keeps heavy wet mixes from clipping.
 */
class Reverb {
    companion object {
        // Classic Freeverb tunings, in samples at 44.1 kHz.
        private val COMB_TUNINGS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val ALLPASS_TUNINGS = intArrayOf(556, 441, 341, 225)

        // Right-channel combs sit slightly longer than the left ones, so
        // the tail spreads across the stereo field instead of collapsing
        // to the middle.
        private const val STEREO_SPREAD = 23
        private const val COMB_FEEDBACK = 0.84f
        private const val COMB_DAMP = 0.2f
        private const val ALLPASS_FEEDBACK = 0.5f
        private const val WET_GAIN = 0.6f
    }

    private var mix = 0f
    private var combs: Array<Array<CombFilter>> = emptyArray()
    private var allpasses: Array<Array<AllpassFilter>> = emptyArray()

    fun configure(sampleRate: Int, channelCount: Int) {
        val scale = sampleRate / 44100f
        combs = Array(channelCount) { channel ->
            Array(COMB_TUNINGS.size) { i ->
                val spread = if (channel == 1) STEREO_SPREAD else 0
                CombFilter(
                    delaySamples = max(1, ((COMB_TUNINGS[i] + spread) * scale).roundToInt()),
                    feedback = COMB_FEEDBACK,
                    damp = COMB_DAMP
                )
            }
        }
        allpasses = Array(channelCount) { _ ->
            Array(ALLPASS_TUNINGS.size) { i ->
                AllpassFilter(
                    delaySamples = max(1, (ALLPASS_TUNINGS[i] * scale).roundToInt()),
                    feedback = ALLPASS_FEEDBACK
                )
            }
        }
    }

    /** 0.0 = dry only (exact bypass), 1.0 = fully wet. */
    fun setMix(mix: Float) {
        this.mix = mix.coerceIn(0f, 1f)
    }

    fun mix(): Float = mix

    /** Processes one interleaved frame in place (frame size == channel count). */
    fun processFrame(frame: FloatArray) {
        if (mix <= 0f) return
        for (channel in frame.indices) {
            if (channel >= combs.size) continue
            val dry = frame[channel]
            var wet = 0f
            for (comb in combs[channel]) wet += comb.process(dry)
            wet /= combs[channel].size
            for (allpass in allpasses[channel]) wet = allpass.process(wet)
            frame[channel] = dry * (1f - mix) + wet * WET_GAIN * mix
        }
    }

    fun reset() {
        combs.forEach { channel -> channel.forEach { it.reset() } }
        allpasses.forEach { channel -> channel.forEach { it.reset() } }
    }

    /**
     * Feedback comb with damping: the stored value blends the old output
     * with the previous stored value, which rolls highs off the tail the
     * way a real room does.
     */
    private class CombFilter(
        delaySamples: Int,
        private val feedback: Float,
        private val damp: Float
    ) {
        private val buffer = FloatArray(delaySamples)
        private var index = 0
        private var filterStore = 0f

        fun process(input: Float): Float {
            val output = buffer[index]
            filterStore = output * (1f - damp) + filterStore * damp
            buffer[index] = input + filterStore * feedback
            index = (index + 1) % buffer.size
            return output
        }

        fun reset() {
            buffer.fill(0f)
            index = 0
            filterStore = 0f
        }
    }

    private class AllpassFilter(
        delaySamples: Int,
        private val feedback: Float
    ) {
        private val buffer = FloatArray(delaySamples)
        private var index = 0

        fun process(input: Float): Float {
            val bufOut = buffer[index]
            val output = -input + bufOut
            buffer[index] = input + bufOut * feedback
            index = (index + 1) % buffer.size
            return output
        }

        fun reset() {
            buffer.fill(0f)
            index = 0
        }
    }
}
