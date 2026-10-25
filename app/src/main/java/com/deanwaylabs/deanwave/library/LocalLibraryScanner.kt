package com.deanwaylabs.deanwave.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** One playable local track, found on the phone or SD card. */
data class LocalTrack(
    /** Content Uri (MediaStore) or file Uri (direct scan), as a string. */
    val uriString: String,
    val title: String,
    val artist: String,
    val album: String?,
    /** Milliseconds, when known (MediaStore tracks; direct-scan tracks omit it). */
    val durationMs: Long?
)

/**
 * Which folders the direct scan walks. Defaults are derived from the device
 * at runtime (see [LocalLibraryScanner.defaultConfig]) — never a hardcoded
 * device path — and the caller can always pass its own roots instead.
 */
data class LocalLibraryConfig(val scanRoots: List<File>)

/**
 * Finds the local library two ways, because neither alone is complete:
 *
 * 1. MediaStore — the system's index of shared audio. This is the primary
 *    source: it has real titles, artists, albums, and durations, and it
 *    covers the SD card too (VOLUME_EXTERNAL spans all external volumes).
 *    It misses files the media scanner has not indexed yet.
 * 2. Direct folder scan — walks the configured music folders for audio
 *    files by extension. This catches fresh, unindexed files. Titles come
 *    from filenames here; artist is unknown unless MediaStore knew it.
 *
 * Results are deduplicated best-effort by normalized title + artist, with
 * MediaStore winning (it has the better metadata). A missing permission
 * (READ_MEDIA_AUDIO not granted) simply yields empty results per source —
 * the scanner never crashes on it.
 *
 * No new dependencies: plain ContentResolver + java.io.
 */
class LocalLibraryScanner(
    private val context: Context,
    private val config: LocalLibraryConfig = defaultConfig(context)
) {
    companion object {
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "aiff", "aif"
        )

        private const val UNKNOWN_ARTIST = "Unknown artist"

        /**
         * Runtime-derived scan roots: the shared Music folder, plus a Music
         * folder on every removable volume (found by walking up from the
         * app's external files dirs to the volume root). Nothing here names
         * a specific device path.
         */
        @Suppress("DEPRECATION")
        fun defaultConfig(context: Context): LocalLibraryConfig {
            val roots = mutableListOf<File>()
            try {
                val publicMusic =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                if (publicMusic.isDirectory) roots += publicMusic
            } catch (_: Exception) {
                // No shared Music folder on this device — MediaStore still covers it.
            }
            try {
                val dirs = context.getExternalFilesDirs(null)
                // Index 0 is primary storage; later entries are removable volumes.
                for (i in 1 until dirs.size) {
                    val filesDir = dirs[i] ?: continue
                    // .../Android/data/<package>/files -> walk up to the volume root.
                    var root: File? = filesDir
                    repeat(4) { root = root?.parentFile }
                    val sdMusic = root?.let { File(it, "Music") }
                    if (sdMusic != null && sdMusic.isDirectory) roots += sdMusic
                }
            } catch (_: Exception) {
                // Removable volumes are best-effort; MediaStore still covers them.
            }
            return LocalLibraryConfig(roots)
        }
    }

    /** All local tracks, MediaStore first, then direct-scan additions. */
    fun scan(): List<LocalTrack> {
        val found = linkedMapOf<String, LocalTrack>()
        for (track in scanMediaStore()) {
            found[keyFor(track.title, track.artist)] = track
        }
        for (track in scanFolders()) {
            found.putIfAbsent(keyFor(track.title, track.artist), track)
        }
        return found.values.toList()
    }

    private fun keyFor(title: String, artist: String): String =
        "${title.trim().lowercase()}|${artist.trim().lowercase()}"

    private fun scanMediaStore(): List<LocalTrack> {
        val tracks = mutableListOf<LocalTrack>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        try {
            context.contentResolver.query(
                collection, projection, selection, null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri: Uri = ContentUris.withAppendedId(collection, id)
                    val artist = cursor.getString(artistCol)?.takeUnless { it == "<unknown>" }
                        ?: UNKNOWN_ARTIST
                    tracks += LocalTrack(
                        uriString = uri.toString(),
                        title = cursor.getString(titleCol) ?: uri.lastPathSegment.orEmpty(),
                        artist = artist,
                        album = cursor.getString(albumCol),
                        durationMs = cursor.getLong(durationCol).takeIf { it > 0 }
                    )
                }
            }
        } catch (_: SecurityException) {
            // READ_MEDIA_AUDIO not granted: this source is simply empty.
        } catch (_: Exception) {
            // A broken index must not take down the whole library.
        }
        return tracks
    }

    private fun scanFolders(): List<LocalTrack> {
        val tracks = mutableListOf<LocalTrack>()
        for (root in config.scanRoots) {
            if (root.isDirectory) walkFolder(root, depth = 0, out = tracks)
        }
        return tracks
    }

    private fun walkFolder(dir: File, depth: Int, out: MutableList<LocalTrack>) {
        if (depth > 8) return
        val children = try {
            dir.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return
        for (child in children) {
            try {
                when {
                    child.isDirectory && !child.name.startsWith(".") ->
                        walkFolder(child, depth + 1, out)
                    child.isFile && child.extension.lowercase() in AUDIO_EXTENSIONS ->
                        out += LocalTrack(
                            uriString = Uri.fromFile(child).toString(),
                            title = child.nameWithoutExtension,
                            artist = UNKNOWN_ARTIST,
                            album = null,
                            durationMs = null
                        )
                }
            } catch (_: SecurityException) {
                // Unreadable entry: skip it, keep scanning the rest.
            }
        }
    }
}
