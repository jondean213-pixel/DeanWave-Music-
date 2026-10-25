package com.deanwaylabs.deanwave.dsp

/** One parametric EQ band: any frequency, any gain, any width (Q). */
data class EqBand(
    val frequencyHz: Float,
    val gainDb: Float,
    val q: Float
)

/**
 * 8-band parametric EQ. Each band owns one [Biquad] per channel, so channels
 * never share filter state (sharing would smear the stereo image).
 *
 * Default band layout spans the audible range; every value is editable —
 * this is the "better than Android's five fixed sliders" part.
 */
class ParametricEq(private val bandCount: Int = 8) {
    companion object {
        val DEFAULT_FREQUENCIES = floatArrayOf(
            60f, 150f, 400f, 1000f, 2500f, 6000f, 10000f, 14000f
        )
    }

    private var sampleRate = 44100
    private val bands = MutableList(bandCount) { i ->
        EqBand(DEFAULT_FREQUENCIES.getOrElse(i) { 1000f }, 0f, 1f)
    }

    /** filters[channel][band] */
    private var filters: Array<Array<Biquad>> = emptyArray()

    fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        filters = Array(channelCount) { Array(bandCount) { Biquad() } }
        bands.forEachIndexed { index, band -> applyBand(index, band) }
    }

    fun setBand(index: Int, band: EqBand) {
        require(index in bands.indices) { "Band index $index out of range 0..${bands.lastIndex}" }
        bands[index] = band
        applyBand(index, band)
    }

    fun bands(): List<EqBand> = bands.toList()

    fun processChannel(channel: Int, x: Float): Float {
        if (channel >= filters.size) return x
        var sample = x
        for (band in filters[channel]) sample = band.process(sample)
        return sample
    }

    fun reset() {
        filters.forEach { channel -> channel.forEach { it.reset() } }
    }

    private fun applyBand(index: Int, band: EqBand) {
        filters.forEach { channel ->
            channel[index].setPeaking(band.frequencyHz, band.q, band.gainDb, sampleRate)
        }
    }
}
