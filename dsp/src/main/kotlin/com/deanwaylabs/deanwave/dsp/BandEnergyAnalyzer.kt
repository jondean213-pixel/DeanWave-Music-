package com.deanwaylabs.deanwave.dsp

/**
 * Per-band energy readout for the visualizer seam.
 *
 * How it works: the analyzer watches the same PCM frames the sound engine
 * processes (after the limiter, so the beams reflect what the user actually
 * hears — and in bypass, the untouched frames, so the beams still move).
 * Each frame is mixed to mono and run through one bandpass filter per band
 * (same center frequencies as the EQ, Q = 1). Each band keeps a fast-attack /
 * slow-release envelope of its output level, normalized against a slow peak
 * follower floored at 0.02 so silence reads as zero instead of noise.
 *
 * This is a seam, not the visualizer UI: it only exposes numbers in 0..1.
 * Drawing beams and bars from them is the visualizer UI phase. The UI reads
 * [energies] at its own pace (about 30 times a second); the readout returns
 * a copy, so audio-thread writes and UI-thread reads never share an array.
 *
 * Pure Kotlin, no Android imports — unit-testable on the bench.
 */
class BandEnergyAnalyzer(
    private val frequencies: FloatArray = ParametricEq.DEFAULT_FREQUENCIES
) {
    /** Center frequencies of the bands, in Hz. */
    val bandCount: Int
        get() = frequencies.size

    private val lock = Any()
    private var filters: Array<Biquad> = emptyArray()
    private var levels = FloatArray(frequencies.size)
    private var peaks = FloatArray(frequencies.size)

    fun configure(sampleRate: Int) {
        synchronized(lock) {
            filters = Array(frequencies.size) { i ->
                Biquad().apply { setBandpass(frequencies[i], 1f, sampleRate) }
            }
            levels = FloatArray(frequencies.size)
            peaks = FloatArray(frequencies.size)
        }
    }

    /** Feeds one interleaved frame (any channel count) through the analyzer. */
    fun analyze(frame: FloatArray) {
        if (frame.isEmpty()) return
        synchronized(lock) {
            if (filters.isEmpty()) return
            var mono = 0f
            for (sample in frame) mono += sample
            mono /= frame.size
            for (i in filters.indices) {
                val out = filters[i].process(mono)
                val magnitude = kotlin.math.abs(out)
                // Instant attack, slow release: bars jump with the beat and
                // fall smoothly instead of flickering.
                levels[i] = if (magnitude > levels[i]) magnitude else levels[i] * 0.9995f
                if (levels[i] > peaks[i]) peaks[i] = levels[i]
                else peaks[i] *= 0.99995f
            }
        }
    }

    /** Current per-band energies, each 0..1. Safe to call from the UI thread. */
    fun energies(): FloatArray {
        synchronized(lock) {
            return FloatArray(levels.size) { i ->
                (levels[i] / maxOf(peaks[i], 0.02f)).coerceIn(0f, 1f)
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            filters.forEach { it.reset() }
            levels = FloatArray(frequencies.size)
            peaks = FloatArray(frequencies.size)
        }
    }
}
