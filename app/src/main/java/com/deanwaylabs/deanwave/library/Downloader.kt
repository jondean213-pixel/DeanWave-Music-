package com.deanwaylabs.deanwave.library

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Phone-mediated v1 downloader: fetch a song, land it on the music Drive.
 *
 * Flow: validate the URL → download to a temp file in the app cache →
 * optionally copy it to the SD card Music folder for offline listening →
 * upload it to Drive via [DriveResumableUploader] → delete the temp file.
 * The temp file is deleted in a finally block, so a failed run never
 * leaves a stray copy behind.
 *
 * Guards, enforced here rather than trusted to the UI: only http(s) URLs
 * are fetched, and YouTube/Spotify hosts are refused fast — those services
 * serve no direct file and are outside this app's legal sources. This is
 * not a DRM tool: it downloads exactly what the server serves, nothing
 * stripped, nothing unlocked.
 *
 * Blocking; call off the main thread (see [DownloadController]).
 */
class HttpSongDownloader(
    private val context: Context,
    private val uploader: DriveUploadTarget = DriveResumableUploader(),
    private val credentials: DriveCredentials,
    private val config: DriveConfig
) : SongDownloader {

    companion object {
        private val BLOCKED_HOSTS = listOf("youtube.com", "youtu.be", "spotify.com")
        private val MIME_BY_EXTENSION = mapOf(
            "mp3" to "audio/mpeg",
            "flac" to "audio/flac",
            "wav" to "audio/wav",
            "m4a" to "audio/mp4",
            "aac" to "audio/aac",
            "ogg" to "audio/ogg",
            "opus" to "audio/opus",
            "aiff" to "audio/aiff",
            "aif" to "audio/aiff"
        )

        /** Strips characters a filename must not contain. */
        fun sanitizeFileName(raw: String): String {
            val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            return cleaned.ifEmpty { "deanwave-download" }
        }

        fun guessMimeType(fileName: String): String {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return MIME_BY_EXTENSION[ext] ?: "application/octet-stream"
        }
    }

    override fun downloadToDrive(
        sourceUrl: String,
        fileName: String,
        keepLocalCopy: Boolean,
        onProgress: DownloadProgressListener
    ): DownloadOutcome {
        var temp: File? = null
        try {
            val url = validateUrl(sourceUrl)
            val name = sanitizeFileName(
                fileName.ifBlank { deriveFileName(url) }
            )
            temp = File.createTempFile("deanwave-dl-", ".tmp", context.cacheDir)
            downloadToFile(url, temp, onProgress)
            if (temp.length() == 0L) {
                return DownloadOutcome.Failed("Downloaded file is empty — nothing to upload.")
            }
            var keptPath: String? = null
            if (keepLocalCopy) {
                keptPath = keepOnSdCard(temp, name)
            }
            val driveId = uploader.upload(
                temp, name, guessMimeType(name), config, credentials,
                UploadProgressListener { done, total ->
                    onProgress.onProgress(DownloadStage.UPLOADING, done, total)
                }
            )
            return DownloadOutcome.Success(driveFileId = driveId, keptLocalPath = keptPath)
        } catch (e: IllegalStateException) {
            // Includes "Drive account not connected" — an honest gap, not a crash.
            return DownloadOutcome.Failed(e.message ?: "Drive account not connected.")
        } catch (e: IOException) {
            return DownloadOutcome.Failed("Download failed: ${e.message}")
        } catch (e: Exception) {
            return DownloadOutcome.Failed("Download failed: ${e.message}")
        } finally {
            try {
                temp?.delete()
            } catch (_: Exception) {
                // Best-effort cleanup; a leftover temp file is harmless.
            }
        }
    }

    private fun validateUrl(sourceUrl: String): URL {
        val url = try {
            URL(sourceUrl)
        } catch (_: Exception) {
            throw IOException("That is not a valid download link.")
        }
        if (url.protocol != "http" && url.protocol != "https") {
            throw IOException("Only web links (http/https) can be downloaded.")
        }
        val host = url.host.lowercase()
        if (BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }) {
            throw IOException("That source is not supported in DeanWave.")
        }
        return url
    }

    private fun deriveFileName(url: URL): String {
        val last = url.path.substringAfterLast('/').ifEmpty { "deanwave-download" }
        return try {
            URLDecoder.decode(last, "UTF-8")
        } catch (_: Exception) {
            last
        }
    }

    private fun downloadToFile(
        url: URL,
        dest: File,
        onProgress: DownloadProgressListener
    ) {
        val conn = url.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            throw IOException("Download server answered HTTP $code.")
        }
        val total = conn.contentLengthLong
        var done = 0L
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    done += n
                    onProgress.onProgress(DownloadStage.DOWNLOADING, done, total)
                }
            }
        }
    }

    /** Copies the song into the first writable Music folder (SD card first). Null if none. */
    private fun keepOnSdCard(temp: File, fileName: String): String? {
        val roots = try {
            LocalLibraryScanner.defaultConfig(context).scanRoots
        } catch (_: Exception) {
            emptyList()
        }
        val sharedMusic = try {
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        } catch (_: Exception) {
            null
        }
        val candidates = (roots + listOfNotNull(sharedMusic))
            .filter { it.isDirectory && it.canWrite() }
        for (dir in candidates) {
            try {
                val dest = File(dir, fileName)
                temp.copyTo(dest, overwrite = true)
                return dest.absolutePath
            } catch (_: Exception) {
                // Try the next folder.
            }
        }
        return null
    }
}

/**
 * Runs a download on a background thread and reports back on the main
 * thread — the UI never touches blocking I/O. Plain Thread + Handler, the
 * same pattern the rest of this app uses (no coroutines dependency).
 */
class DownloadController(
    context: Context,
    credentials: DriveCredentials,
    config: DriveConfig,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {
    interface Listener {
        fun onProgress(stage: DownloadStage, doneBytes: Long, totalBytes: Long)
        fun onDone(driveFileId: String, keptLocalPath: String?)
        fun onFailed(reason: String)
    }

    private val downloader = HttpSongDownloader(context, DriveResumableUploader(), credentials, config)

    fun startDownload(
        sourceUrl: String,
        fileName: String,
        keepLocalCopy: Boolean,
        listener: Listener
    ) {
        Thread {
            val outcome = downloader.downloadToDrive(
                sourceUrl, fileName, keepLocalCopy,
                DownloadProgressListener { stage, done, total ->
                    mainHandler.post { listener.onProgress(stage, done, total) }
                }
            )
            mainHandler.post {
                when (outcome) {
                    is DownloadOutcome.Success ->
                        listener.onDone(outcome.driveFileId, outcome.keptLocalPath)
                    is DownloadOutcome.Failed -> listener.onFailed(outcome.reason)
                }
            }
        }.start()
    }
}

/** Credentials stub: no account connected. Replaced when Jon provides the music-Drive account. */
object UnconfiguredDriveCredentials : DriveCredentials {
    override fun accessToken(): String? = null
}
