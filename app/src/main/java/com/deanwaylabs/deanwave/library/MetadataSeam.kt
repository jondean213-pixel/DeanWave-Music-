package com.deanwaylabs.deanwave.library

/** Everything the now-playing screen shows beyond the audio itself. */
data class TrackMetadata(
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtUrl: String?,
    val lyrics: String?,
    val artistBio: String?,
    /** e.g. the year the band formed. */
    val artistFormedYear: Int?
)

/**
 * Later-phase seam: album art, lyrics, and band biography providers plug in
 * here. Providers are a later decision (free/metadata sources only); nothing
 * is named or embedded in this scaffold.
 */
interface MetadataProvider {
    suspend fun lookup(artist: String, title: String): TrackMetadata?
}
