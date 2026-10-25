package com.deanwaylabs.deanwave.dsp

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bench tests for the sound engine. These are written to run under plain JUnit
 * on the JVM (the :dsp module has no Android dependencies).
 *
 * NOTE: not executed on this bench — Gradle cannot run here. CI runs them.
 */
class SoundEngineTest {

    private val sampleRate = 44100
    private val channels = 2

    private fun engineWith(preset: Preset): SoundEngine =
        SoundEngine().apply {
            configure(sampleRate, channels)
            applyPreset(preset)
        }

    /** Feeds [frames] sine frames at [frequencyHz] and returns the RMS of the last half. */
    private fun steadyStateRms(
        engine: SoundEngine,
        frequencyHz: Float,
        frames: Int = sampleRate * 2
    ): Double {
        var sumSquares = 0.0
        var count = 0
        for (i in 0 until frames) {
            val t = sin(2.0 * PI * frequencyHz / sampleRate * i).toFloat()
            val out = engine.processFrame(floatArrayOf(t, t))
            if (i >= frames / 2) {
                sumSquares += out[0] * out[0]
                count++
            }
        }
        return sqrt(sumSquares / count)
    }

    @Test
    fun flatPresetIsIdentity() {
        val engine = engineWith(Presets.FLAT)
        var maxDiff = 0f
        for (i in 0 until 10000) {
            val input = sin(i * 0.123).toFloat() * 0.5f
            val out = engine.processFrame(floatArrayOf(input, input))
            val diff = kotlin.math.abs(out[0] - input)
            if (diff > maxDiff) maxDiff = diff
        }
        assertTrue("Flat should pass audio unchanged, max diff was $maxDiff", maxDiff < 1e-4f)
    }

    @Test
    fun bypassIsExactIdentityEvenWithExtremeSettings() {
        val engine = engineWith(Presets.BASS).apply {
            preampDb = 24f
            setBassStrength(1f)
            bypass = true
        }
        for (i in 0 until 1000) {
            val input = sin(i * 0.31).toFloat()
            val out = engine.processFrame(floatArrayOf(input, -input))
            assertEquals(input, out[0], 0f)
            assertEquals(-input, out[1], 0f)
        }
    }

    @Test
    fun limiterNeverExceedsCeiling() {
        // +20 dB preamp into a full-scale signal: without the limiter this
        // would clip hard. Ceiling is -0.5 dB (~0.944 linear).
        val engine = engineWith(Presets.FLAT).apply { preampDb = 20f }
        var peak = 0f
        for (i in 0 until sampleRate) {
            val out = engine.processFrame(floatArrayOf(1f, 1f))
            peak = maxOf(peak, kotlin.math.abs(out[0]))
        }
        assertTrue("Limiter peak $peak must stay under 0.95", peak < 0.95f)
    }

    @Test
    fun bassBoostLiftsLows() {
        val flat = engineWith(Presets.FLAT)
        val boosted = engineWith(Presets.FLAT).apply { setBassStrength(1f) }
        val flatRms = steadyStateRms(flat, 60f)
        val boostedRms = steadyStateRms(boosted, 60f)
        assertTrue(
            "60 Hz RMS should rise with bass boost (flat=$flatRms boosted=$boostedRms)",
            boostedRms > flatRms * 1.2
        )
    }

    @Test
    fun peakingBandBoostsItsCenter() {
        val boostedBand = EqBand(1000f, 12f, 1f)
        val flat = engineWith(Presets.FLAT)
        val boosted = engineWith(Presets.FLAT).apply {
            setBand(3, boostedBand) // band 3 is the 1000 Hz slot
        }
        val flatRms = steadyStateRms(flat, 1000f)
        val boostedRms = steadyStateRms(boosted, 1000f)
        assertTrue(
            "1000 Hz RMS should rise with a +12 dB band (flat=$flatRms boosted=$boostedRms)",
            boostedRms > flatRms * 1.5
        )
    }

    @Test
    fun profileStoreRoundTrip() {
        val store = InMemoryProfileStore()
        val profile = DeviceProfile("Bose QC", Presets.BASS)
        store.save(profile)
        assertEquals(profile, store.load("Bose QC"))
        assertEquals(null, store.load("Unknown device"))
    }

    @Test
    fun bandEnergiesAreZeroOnSilence() {
        val engine = engineWith(Presets.FLAT)
        repeat(sampleRate) { engine.processFrame(floatArrayOf(0f, 0f)) }
        val energies = engine.bandEnergies()
        assertEquals(8, energies.size)
        for (energy in energies) {
            assertTrue("Silence should read ~0, got $energy", energy < 0.01f)
        }
    }

    @Test
    fun bandEnergyPeaksAtToneFrequency() {
        // A 1000 Hz tone should light up the 1000 Hz band (index 3) hardest.
        val engine = engineWith(Presets.FLAT)
        var energies = floatArrayOf()
        repeat(sampleRate * 2) { i ->
            val t = sin(2.0 * PI * 1000f / sampleRate * i).toFloat() * 0.8f
            engine.processFrame(floatArrayOf(t, t))
            energies = engine.bandEnergies()
        }
        var maxIndex = 0
        for (i in energies.indices) {
            if (energies[i] > energies[maxIndex]) maxIndex = i
        }
        assertEquals("1000 Hz tone should peak in band 3, energies were ${energies.toList()}", 3, maxIndex)
        assertTrue("Peak band energy should be strong, got ${energies[3]}", energies[3] > 0.5f)
    }
}
