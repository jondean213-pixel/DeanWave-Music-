package com.deanwaylabs.deanwave.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max

/**
 * Simple feedforward peak limiter, linked across channels.
 *
 * Linked matters: the envelope follows the loudest channel, and one gain
 * applies to the whole frame, so heavy limiting never shifts the stereo image.
 *
 * Behavior: instant attack (a peak over the ceiling is caught on the same
 * frame), smooth release. Ceiling defaults to -0.5 dB — boosted sound stays
 * under full scale instead of clipping.
 *
 * [gainReductionDb] exposes current reduction for a future UI meter.
 */
class Limiter(
    ceilingDb: Float = -0.5f,
    private val releaseMs: Float = 100f
) {
    private val ceiling: Float =
        Math.pow(10.0, (ceilingDb / 20.0).toDouble()).toFloat()
    private var releaseCoeff = 0.999f
    private var envelope = 0f
    private var gain = 1f

    fun configure(sampleRate: Int) {
        val releaseSeconds = releaseMs / 1000f
        releaseCoeff = exp(-1.0 / (releaseSeconds * sampleRate)).toFloat()
    }

    /** Processes one interleaved frame in place (frame size == channel count). */
    fun processFrame(frame: FloatArray) {
        var peak = 0f
        for (sample in frame) peak = max(peak, abs(sample))
        envelope = if (peak > envelope) peak else envelope * releaseCoeff
        val target = if (envelope > ceiling) ceiling / envelope else 1f
        gain = if (target < gain) {
            target // instant attack
        } else {
            gain + (target - gain) * (1f - releaseCoeff) // smooth release
        }
        for (i in frame.indices) frame[i] = frame[i] * gain
    }

    /** Negative when limiting, 0 dB when idle. */
    val gainReductionDb: Float
        get() = if (gain <= 0f) -96f else 20f * log10(gain)

    fun reset() {
        envelope = 0f
        gain = 1f
    }
}
