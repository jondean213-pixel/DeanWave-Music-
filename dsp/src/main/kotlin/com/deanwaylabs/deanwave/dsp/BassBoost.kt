package com.deanwaylabs.deanwave.dsp

/**
 * Bass boost done properly: a low shelf (not a single fixed slider) whose
 * strength maps to 0..[MAX_BOOST_DB] dB of low-end lift.
 *
 * Strength 0 is exactly flat (0 dB shelf = identity), so "off" means off.
 * The limiter downstream in [SoundEngine] is what keeps heavy bass clean
 * instead of clipping the earbuds.
 *
 * Later option, deliberately not in v1: psychoacoustic sub-bass synthesis
 * (harmonics that make small drivers *perceive* bass they cannot reproduce).
 */
class BassBoost {
    companion object {
        const val MAX_BOOST_DB = 12f
        const val DEFAULT_FREQUENCY_HZ = 120f
    }

    private var sampleRate = 44100
    private var strength = 0f
    private var frequencyHz = DEFAULT_FREQUENCY_HZ
    private var filters: Array<Biquad> = emptyArray()

    fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        filters = Array(channelCount) { Biquad() }
        apply()
    }

    /** 0.0 = off, 1.0 = full +12 dB low shelf. */
    fun setStrength(strength: Float) {
        this.strength = strength.coerceIn(0f, 1f)
        apply()
    }

    fun setFrequency(frequencyHz: Float) {
        this.frequencyHz = frequencyHz
        apply()
    }

    fun strength(): Float = strength

    fun processChannel(channel: Int, x: Float): Float {
        if (channel >= filters.size) return x
        return filters[channel].process(x)
    }

    fun reset() {
        filters.forEach { it.reset() }
    }

    private fun apply() {
        val gainDb = strength * MAX_BOOST_DB
        filters.forEach { it.setLowShelf(frequencyHz, gainDb, sampleRate) }
    }
}
