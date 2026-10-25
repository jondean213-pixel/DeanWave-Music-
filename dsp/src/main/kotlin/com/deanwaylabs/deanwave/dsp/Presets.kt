package com.deanwaylabs.deanwave.dsp

/**
 * A named sound: 8 EQ bands + bass strength + preamp trim.
 * Band order follows [ParametricEq.DEFAULT_FREQUENCIES].
 */
data class Preset(
    val name: String,
    val bands: List<EqBand>,
    val bassStrength: Float,
    val preampDb: Float
)

/** A saved sound for one playback device (e.g. "Bose QC"). */
data class DeviceProfile(
    val deviceName: String,
    val preset: Preset
)

/**
 * Storage seam for per-device profiles. The scaffold ships an in-memory
 * implementation; the app phase backs this with DataStore/Preferences.
 */
interface ProfileStore {
    fun load(deviceName: String): DeviceProfile?
    fun save(profile: DeviceProfile)
}

class InMemoryProfileStore : ProfileStore {
    private val profiles = mutableMapOf<String, DeviceProfile>()
    override fun load(deviceName: String): DeviceProfile? = profiles[deviceName]
    override fun save(profile: DeviceProfile) {
        profiles[profile.deviceName] = profile
    }
}

private fun bands(gains: List<Float>): List<EqBand> =
    ParametricEq.DEFAULT_FREQUENCIES.mapIndexed { index, frequency ->
        EqBand(frequency, gains.getOrElse(index) { 0f }, 1f)
    }

/** Factory presets. All gains stay inside the engine's +/-15 dB clamps. */
object Presets {
    val FLAT = Preset(
        name = "Flat",
        bands = bands(listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        bassStrength = 0f,
        preampDb = 0f
    )

    val BASS = Preset(
        name = "Bass",
        bands = bands(listOf(4f, 3f, 1f, 0f, 0f, 0f, 0f, 0f)),
        bassStrength = 0.6f,
        preampDb = -2f // headroom for the low-end lift
    )

    val VOCAL = Preset(
        name = "Vocal",
        bands = bands(listOf(0f, 0f, 0f, 1f, 3f, 2f, 0f, 0f)),
        bassStrength = 0f,
        preampDb = 0f
    )

    val ROCK = Preset(
        name = "Rock",
        bands = bands(listOf(3f, 2f, 0f, -1f, 0f, 1f, 3f, 2f)),
        bassStrength = 0.25f,
        preampDb = -1f
    )

    val TREBLE = Preset(
        name = "Treble",
        bands = bands(listOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 4f)),
        bassStrength = 0f,
        preampDb = 0f
    )

    /** Starting point for user edits; flat until changed. */
    val CUSTOM = Preset(
        name = "Custom",
        bands = bands(listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        bassStrength = 0f,
        preampDb = 0f
    )

    fun all(): List<Preset> = listOf(FLAT, BASS, VOCAL, ROCK, TREBLE, CUSTOM)
}
