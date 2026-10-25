package com.deanwaylabs.deanwave.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Second-order IIR filter (biquad) from the RBJ Audio EQ Cookbook.
 *
 * Direct Form I. Coefficients are recomputed whenever parameters change.
 * Known polish item (see README): live parameter changes recompute instantly,
 * which can click; coefficient smoothing across changes comes later.
 *
 * All gains are clamped to +/-15 dB, frequencies to 20 Hz..0.45 * sample rate,
 * Q to 0.1..18. Filters are per-channel instances; a stereo chain owns two.
 */
class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    /** Peaking EQ band: boost/cut centered on [frequencyHz], width set by [q]. */
    fun setPeaking(frequencyHz: Float, q: Float, gainDb: Float, sampleRate: Int) {
        val f = frequencyHz.coerceIn(20f, 0.45f * sampleRate)
        val qClamped = q.coerceIn(0.1f, 18f)
        val gain = gainDb.coerceIn(-15f, 15f)
        val a = Math.pow(10.0, (gain / 40.0).toDouble()).toFloat()
        val w0 = (2.0 * PI * f / sampleRate).toFloat()
        val alpha = sin(w0) / (2f * qClamped)
        val cosW = cos(w0)
        setCoefficients(
            b0 = 1f + alpha * a,
            b1 = -2f * cosW,
            b2 = 1f - alpha * a,
            a0 = 1f + alpha / a,
            a1 = -2f * cosW,
            a2 = 1f - alpha / a
        )
    }

    /** Low shelf: boosts/cuts everything below [frequencyHz]. Shelf slope S = 1. */
    fun setLowShelf(frequencyHz: Float, gainDb: Float, sampleRate: Int) {
        val f = frequencyHz.coerceIn(20f, 0.45f * sampleRate)
        val gain = gainDb.coerceIn(-15f, 15f)
        val a = Math.pow(10.0, (gain / 40.0).toDouble()).toFloat()
        val w0 = (2.0 * PI * f / sampleRate).toFloat()
        val cosW = cos(w0)
        // S = 1 -> alpha = sin(w0) / 2 * sqrt(2).
        val alpha = sin(w0) / 2f * sqrt(2f)
        val sqrtA = sqrt(a)
        setCoefficients(
            b0 = a * ((a + 1f) - (a - 1f) * cosW + 2f * sqrtA * alpha),
            b1 = 2f * a * ((a - 1f) - (a + 1f) * cosW),
            b2 = a * ((a + 1f) - (a - 1f) * cosW - 2f * sqrtA * alpha),
            a0 = (a + 1f) + (a - 1f) * cosW + 2f * sqrtA * alpha,
            a1 = -2f * ((a - 1f) + (a + 1f) * cosW),
            a2 = (a + 1f) + (a - 1f) * cosW - 2f * sqrtA * alpha
        )
    }

    /** High shelf: boosts/cuts everything above [frequencyHz]. Shelf slope S = 1. */
    fun setHighShelf(frequencyHz: Float, gainDb: Float, sampleRate: Int) {
        val f = frequencyHz.coerceIn(20f, 0.45f * sampleRate)
        val gain = gainDb.coerceIn(-15f, 15f)
        val a = Math.pow(10.0, (gain / 40.0).toDouble()).toFloat()
        val w0 = (2.0 * PI * f / sampleRate).toFloat()
        val cosW = cos(w0)
        val alpha = sin(w0) / 2f * sqrt(2f)
        val sqrtA = sqrt(a)
        setCoefficients(
            b0 = a * ((a + 1f) + (a - 1f) * cosW + 2f * sqrtA * alpha),
            b1 = -2f * a * ((a - 1f) + (a + 1f) * cosW),
            b2 = a * ((a + 1f) + (a - 1f) * cosW - 2f * sqrtA * alpha),
            a0 = (a + 1f) - (a - 1f) * cosW + 2f * sqrtA * alpha,
            a1 = 2f * ((a - 1f) - (a + 1f) * cosW),
            a2 = (a + 1f) - (a - 1f) * cosW - 2f * sqrtA * alpha
        )
    }

    /**
     * Bandpass (RBJ, constant 0 dB peak gain): passes only the octave-ish
     * region around [frequencyHz], width set by [q]. Used by the visualizer
     * energy analyzer — it measures how much energy sits in each band, it
     * never shapes the audio the user hears.
     */
    fun setBandpass(frequencyHz: Float, q: Float, sampleRate: Int) {
        val f = frequencyHz.coerceIn(20f, 0.45f * sampleRate)
        val qClamped = q.coerceIn(0.1f, 18f)
        val w0 = (2.0 * PI * f / sampleRate).toFloat()
        val alpha = sin(w0) / (2f * qClamped)
        val cosW = cos(w0)
        setCoefficients(
            b0 = alpha,
            b1 = 0f,
            b2 = -alpha,
            a0 = 1f + alpha,
            a1 = -2f * cosW,
            a2 = 1f - alpha
        )
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    fun reset() {
        x1 = 0f
        x2 = 0f
        y1 = 0f
        y2 = 0f
    }

    private fun setCoefficients(b0: Float, b1: Float, b2: Float, a0: Float, a1: Float, a2: Float) {
        this.b0 = b0 / a0
        this.b1 = b1 / a0
        this.b2 = b2 / a0
        this.a1 = a1 / a0
        this.a2 = a2 / a0
    }
}
