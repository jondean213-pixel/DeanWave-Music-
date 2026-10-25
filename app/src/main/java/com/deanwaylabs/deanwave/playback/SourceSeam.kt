package com.deanwaylabs.deanwave.playback

import androidx.media3.common.MediaItem

/**
 * Where a track comes from. Sources plug in behind this seam one at a time —
 * the player and the sound engine never care which one is playing.
 * LocalFile is implemented (library scan + resolver); DriveFile and
 * OnlineStream arrive in later phases.
 */
sealed interface PlaybackSource {
    /** A file on the phone or SD card. */
    data class LocalFile(val path: String) : PlaybackSource

    /** A file on the music Drive. */
    data class DriveFile(val driveFileId: String, val displayName: String) : PlaybackSource

    /** An online audio stream. The catalog is pluggable — never hardcoded here. */
    data class OnlineStream(val url: String, val displayName: String) : PlaybackSource
}

/**
 * Resolves a [PlaybackSource] into a Media3 [MediaItem].
 *
 * LocalFile is implemented ([LocalSourceResolver], fed by the library
 * scan). Drive API streaming and the online catalog (catalog choice is
 * still open — it stays pluggable, and no catalog is named or embedded)
 * arrive in later phases.
 */
interface SourceResolver {
    fun resolve(source: PlaybackSource): MediaItem
}
