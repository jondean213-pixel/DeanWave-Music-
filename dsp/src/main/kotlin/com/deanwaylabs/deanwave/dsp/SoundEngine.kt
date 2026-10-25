package com.deanwaylabs.deanwave.dsp

/**
 * The DeanWave sound engine: the heart of the app.
 *
 * Chain order, per frame: preamp -> 8-band parametric EQ -> bass boost
 * -> stereo widening -> reverb -> AGC -> limiter.
 * The limiter is last on purpose — it is what lets the user boost freely
 * without clipping.
 *
 * The engine is source-independent: Drive streams, local files, and online
 * streams all pass through this same chain on playback, so every source
 * gets the same sound.
 *
 * Bypass is a true A/B switch: when on, frames pass through untouched
 * (including skipping the limiter), so the user hears exactly what the
 * processing adds or removes.
 *
 * Pure Kotlin, no Android imports — unit-testable on the bench.
 */
class SoundEngine {
    private val eq = ParametricEq(8)
    private val bass = BassBoost()
    private val widening = StereoWidening()
    private val reverb = Reverb()
    private val agc = Agc()
    private val limiter = Limiter()

    /**
     * Visualizer energy analyzer. It watches the frames the engine outputs
     * (post-limiter, or untouched in bypass) and exposes per-band energies
     * for the visualizer UI phase. Seam only — nothing draws from it yet.
     */
    private val analyzer = BandEnergyAnalyzer()

    /** Number of visualizer energy bands (matches the EQ's 8). */
    val energyBandCount: Int
        get() = analyzer.bandCount

    /** True = pass audio through untouched. */
    var bypass: Boolean = false

    /** Master level trim in dB, applied before the EQ. Clamped to +/-24 dB. */
    var preampDb: Float = 0f
        set(value) {
            field = value.coerceIn(-24f, 24f)
        }

    fun configure(sampleRate: Int, channelCount: Int) {
        eq.configure(sampleRate, channelCount)
        bass.configure(sampleRate, channelCount)
        reverb.configure(sampleRate, channelCount)
        agc.configure(sampleRate)
        limiter.configure(sampleRate)
        analyzer.configure(sampleRate)
    }

    fun setBand(index: Int, band: EqBand) = eq.setBand(index, band)

    fun bands(): List<EqBand> = eq.bands()

    /** 0.0 = off, 1.0 = full bass boost. */
    fun setBassStrength(strength: Float) = bass.setStrength(strength)

    fun bassStrength(): Float = bass.strength()

    /**
     * Reverb mix, 0.0 = dry only (exact bypass) to 1.0 = fully wet.
     * Defaults to 0 — the existing presets and the flat preset are
     * untouched by it, so flat stays identity.
     */
    fun setReverbMix(mix: Float) = reverb.setMix(mix)

    fun reverbMix(): Float = reverb.mix()

    /**
     * Stereo widening, 0.0 = untouched stereo to 1.0 = side doubled.
     * Defaults to 0 — mono-safe at any amount, and off is exact bypass.
     */
    fun setWideAmount(amount: Float) = widening.setAmount(amount)

    fun wideAmount(): Float = widening.amount()

    /**
     * AGC on/off. Defaults to off — when off it is an exact bypass, and
     * when on it only evens levels; the limiter downstream still caps
     * peaks, so the two never fight.
     */
    fun setAgcEnabled(enabled: Boolean) = agc.setEnabled(enabled)

    fun agcEnabled(): Boolean = agc.isEnabled()

    /** Applies a full [Preset] in one call. */
    fun applyPreset(preset: Preset) {
        preset.bands.forEachIndexed { index, band -> setBand(index, band) }
        setBassStrength(preset.bassStrength)
        preampDb = preset.preampDb
    }

    /**
     * Processes one interleaved frame in place and returns it.
     * Frame size must equal the configured channel count.
     */
    fun processFrame(frame: FloatArray): FloatArray {
        if (bypass) {
            analyzer.analyze(frame)
            return frame
        }
        val preamp = Math.pow(10.0, (preampDb / 20.0).toDouble()).toFloat()
        for (channel in frame.indices) {
            var sample = frame[channel] * preamp
            sample = eq.processChannel(channel, sample)
            sample = bass.processChannel(channel, sample)
            frame[channel] = sample
        }
        // Whole-frame stages, in chain order. Each is an exact bypass
        // when off, so the flat preset and bypass behavior are unchanged.
        widening.processFrame(frame)
        reverb.processFrame(frame)
        agc.processFrame(frame)
        limiter.processFrame(frame)
        analyzer.analyze(frame)
        return frame
    }

    /**
     * Current visualizer energies, one 0..1 value per band. A copy is
     * returned, so the UI thread can read it while the audio thread writes.
     * The UI polling path (sampled reads at ~30 Hz) arrives with the
     * visualizer UI phase; this readout is the seam it will read from.
     */
    fun bandEnergies(): FloatArray = analyzer.energies()

    /** Current limiter reduction in dB (0 when idle) — for a future UI meter. */
    val limiterGainReductionDb: Float
        get() = limiter.gainReductionDb

    fun reset() {
        eq.reset()
        bass.reset()
        reverb.reset()
        agc.reset()
        limiter.reset()
        analyzer.reset()
    }
}
