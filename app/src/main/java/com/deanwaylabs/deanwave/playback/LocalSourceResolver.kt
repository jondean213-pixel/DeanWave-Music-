package com.deanwaylabs.deanwave.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.deanwaylabs.deanwave.library.LocalTrack

/**
 * Resolves the [PlaybackSource.LocalFile] source into a Media3 [MediaItem].
 *
 * The resolver is built from a library scan: it knows each track's title,
 * artist, and album, so the queue items carry real metadata for the
 * now-playing screen. A path it has never seen still resolves to a bare
 * playable item from the Uri alone — an unknown file is never unplayable
 * just because the scan missed its metadata.
 */
class LocalSourceResolver(tracks: List<LocalTrack>) : SourceResolver {
    private val byUri: Map<String, LocalTrack> =
        tracks.associateBy { it.uriString }

    override fun resolve(source: PlaybackSource): MediaItem {
        if (source !is PlaybackSource.LocalFile) {
            throw IllegalArgumentException("LocalSourceResolver handles LocalFile only, got $source")
        }
        val track = byUri[source.path]
        val metadata = MediaMetadata.Builder()
            .setTitle(track?.title)
            .setArtist(track?.artist)
            .setAlbumTitle(track?.album)
            .build()
        return MediaItem.Builder()
            .setUri(Uri.parse(source.path))
            .setMediaMetadata(metadata)
            .build()
    }
}
