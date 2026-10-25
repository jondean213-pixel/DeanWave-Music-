package com.deanwaylabs.deanwave.library

import java.io.File

/**
 * The in-app downloader, implemented (phone-mediated v1).
 *
 * Contract: fetch a song from a legal source and land it on the music Drive.
 * Legal sources only — purchases the user owns, free catalogs, links the user
 * provides to files they have rights to. No piracy tooling, no DRM stripping,
 * no Spotify integration, no YouTube. Intended legal sources, per Jon's
 * 21:00 CDT catalog decision: Jamendo, Internet Archive, Audius for streaming,
 * and Freegal (library card), Musopen, ccMixter, Bandcamp $0 for Drive-fed
 * downloads. Catalog browsing UI is a later phase — this phase takes URLs.
 *
 * Architecture: phone-mediated v1. The app downloads the song, uploads it to
 * Drive automatically, and cleans up the temporary phone copy (unless the
 * user also wants it kept on the SD card). The Drive API cannot pull a file
 * in from a URL by itself, so the phone does the ferrying — the user only
 * ever taps download and finds the song on Drive.
 *
 * Why blocking calls, not suspend (contract note): this project carries no
 * coroutines dependency — service and library code runs on plain threads
 * with main-thread Handlers (see the playback phase notes). The original
 * seam sketched suspend signatures; they could never be called without a
 * coroutine scope, so this implementation finalizes the contract as
 * blocking calls plus progress listeners, driven by [DownloadController]
 * on a background thread. The shape is otherwise unchanged: a URL in, a
 * Drive file id out.
 *
 * The Drive account is runtime configuration, never hardcoded: no account,
 * email, or token appears anywhere in this code. [DriveCredentials] is
 * supplied by the account wiring, which lands when Jon provides the
 * music-Drive account — until then uploads fail honestly with
 * "not connected" instead of pretending.
 */

/** Runtime configuration for the music Drive. Nothing here names an account. */
data class DriveConfig(
    /** Drive folder id the songs land in; null means the Drive root. */
    val musicFolderId: String?,
    /** Display label only (e.g. "Music Drive"). Never an email or token. */
    val accountLabel: String
)

/**
 * Supplies a Drive OAuth access token at runtime. Returns null when no
 * music-Drive account is connected — the uploader then fails honestly
 * instead of uploading nowhere.
 */
interface DriveCredentials {
    fun accessToken(): String?
}

/** Progress callback for an upload. Byte counts are exact, never estimated. */
fun interface UploadProgressListener {
    fun onProgress(uploadedBytes: Long, totalBytes: Long)
}

/**
 * Drive upload target. Implementations must use resumable sessions, so a
 * broken connection resumes instead of restarting a large file.
 */
interface DriveUploadTarget {
    /**
     * Uploads [file] to the music Drive. Returns the created Drive file id.
     * Throws IllegalStateException when no account is connected, IOException
     * on transport or Drive errors.
     */
    fun upload(
        file: File,
        fileName: String,
        mimeType: String,
        config: DriveConfig,
        credentials: DriveCredentials,
        onProgress: UploadProgressListener
    ): String
}

enum class DownloadStage { DOWNLOADING, UPLOADING }

/** Progress callback for a download. [totalBytes] is -1 when unknown. */
fun interface DownloadProgressListener {
    fun onProgress(stage: DownloadStage, doneBytes: Long, totalBytes: Long)
}

sealed class DownloadOutcome {
    /** Uploaded; [driveFileId] is the Drive file id, [keptLocalPath] null unless kept. */
    data class Success(val driveFileId: String, val keptLocalPath: String?) : DownloadOutcome()

    /** Failed; [reason] is a human-readable explanation, never a stack trace. */
    data class Failed(val reason: String) : DownloadOutcome()
}

/** Downloads a song and lands it on the music Drive. Blocking; call off the main thread. */
interface SongDownloader {
    fun downloadToDrive(
        sourceUrl: String,
        fileName: String,
        keepLocalCopy: Boolean,
        onProgress: DownloadProgressListener
    ): DownloadOutcome
}
