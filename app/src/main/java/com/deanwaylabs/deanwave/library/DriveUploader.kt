package com.deanwaylabs.deanwave.library

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Google Drive uploader using the raw Drive v3 resumable-upload protocol.
 * No Google client library is added for this: the protocol is three HTTP
 * calls, and HttpURLConnection (framework) is all it needs.
 *
 * Protocol, exactly:
 * 1. POST /upload/drive/v3/files?uploadType=resumable with the file
 *    metadata as JSON → the response Location header is the session URI.
 * 2. PUT chunks to the session URI with Content-Range. Chunks are 256 KB
 *    (the protocol requires multiples of 256 KB except the final chunk).
 * 3. A 200/201 response carries the created file's JSON, including its id.
 *    A 308 means "resume incomplete" — the Range header says how much the
 *    server already has, and uploading continues from there.
 *
 * Because the upload reads from a [File] (seekable), a failed chunk can
 * always be re-read and re-sent from the right offset — a stream could not
 * do that, which is why this uploader takes a file, not a stream.
 *
 * Blocking; call off the main thread. Throws IllegalStateException when
 * no Drive account is connected, IOException on transport/Drive errors.
 */
class DriveResumableUploader : DriveUploadTarget {

    companion object {
        private const val CHUNK_SIZE = 256 * 1024
        private const val MAX_CHUNK_ATTEMPTS = 3
        private const val INIT_URL =
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable"
    }

    override fun upload(
        file: File,
        fileName: String,
        mimeType: String,
        config: DriveConfig,
        credentials: DriveCredentials,
        onProgress: UploadProgressListener
    ): String {
        val token = credentials.accessToken()
            ?: throw IllegalStateException(
                "Music Drive account not connected yet — connect Jon's " +
                    "music-Drive account and try again."
            )
        val total = file.length()
        require(total > 0) { "Cannot upload an empty file to Drive." }
        val sessionUri = startSession(token, fileName, mimeType, config)
        try {
            RandomAccessFile(file, "r").use { raf ->
                var offset = 0L
                val buffer = ByteArray(CHUNK_SIZE)
                while (offset < total) {
                    val chunkLen = minOf(CHUNK_SIZE.toLong(), total - offset).toInt()
                    raf.seek(offset)
                    var read = 0
                    while (read < chunkLen) {
                        val n = raf.read(buffer, read, chunkLen - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read == 0) break
                    offset = sendChunk(sessionUri, buffer, read, offset, total, onProgress)
                }
                // The final chunk's 200/201 always escapes via UploadComplete
                // below; reaching here means the server never confirmed it.
                val confirmed = queryUploadedBytes(sessionUri, total)
                if (confirmed == null || confirmed < total) {
                    throw IOException("Drive upload ended incomplete at $offset of $total bytes.")
                }
                throw IOException("Drive upload finished without a file id from the server.")
            }
        } catch (done: UploadComplete) {
            return done.fileId
        }
    }

    /**
     * Sends one chunk, retrying on transport errors. Returns the new offset:
     * either the end of this chunk (sent cleanly) or the id-returning path
     * throws out with the file id — see [sendChunkWithId]. This wrapper
     * exists so the 200/201 id can escape the loop without a flag.
     */
    private fun sendChunk(
        sessionUri: String,
        buffer: ByteArray,
        length: Int,
        offset: Long,
        total: Long,
        onProgress: UploadProgressListener
    ): Long {
        var lastError: IOException? = null
        repeat(MAX_CHUNK_ATTEMPTS) {
            try {
                return sendChunkOnce(sessionUri, buffer, length, offset, total, onProgress)
            } catch (e: UploadComplete) {
                throw e
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Drive upload chunk failed.")
    }

    /**
     * One PUT attempt. Returns the offset after this chunk, or throws
     * [UploadComplete] carrying the file id when the server confirms it.
     * A 308 updates the offset from the server's Range header.
     */
    private fun sendChunkOnce(
        sessionUri: String,
        buffer: ByteArray,
        length: Int,
        offset: Long,
        total: Long,
        onProgress: UploadProgressListener
    ): Long {
        val end = offset + length - 1
        val conn = URL(sessionUri).openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.doOutput = true
        conn.setRequestProperty("Content-Length", length.toString())
        conn.setRequestProperty("Content-Range", "bytes $offset-$end/$total")
        conn.setFixedLengthStreamingMode(length)
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.outputStream.use { it.write(buffer, 0, length) }
        return when (val code = conn.responseCode) {
            200, 201 -> {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val id = JSONObject(body).optString("id")
                if (id.isEmpty()) throw IOException("Drive upload confirmed without a file id.")
                throw UploadComplete(id)
            }
            308 -> {
                val range = conn.getHeaderField("Range")
                val serverHas = parseRangeEnd(range)
                if (serverHas != null && serverHas >= offset) {
                    onProgress.onProgress(serverHas + 1, total)
                    serverHas + 1
                } else {
                    offset
                }
            }
            else -> {
                val detail = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) {
                    ""
                }
                throw IOException("Drive upload failed: HTTP $code $detail")
            }
        }
    }

    /** Asks the session how much it already has. Null when unknowable. */
    private fun queryUploadedBytes(sessionUri: String, total: Long): Long? {
        return try {
            val conn = URL(sessionUri).openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.setRequestProperty("Content-Length", "0")
            conn.setRequestProperty("Content-Range", "bytes */$total")
            conn.setFixedLengthStreamingMode(0)
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.outputStream.use { }
            if (conn.responseCode == 308) parseRangeEnd(conn.getHeaderField("Range"))?.plus(1)
            else null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRangeEnd(range: String?): Long? {
        if (range == null) return null
        // Form: "bytes=0-262143".
        val dash = range.indexOf('-')
        if (dash < 0) return null
        return range.substring(dash + 1).trim().toLongOrNull()
    }

    private fun startSession(
        token: String,
        fileName: String,
        mimeType: String,
        config: DriveConfig
    ): String {
        val metadata = JSONObject().apply {
            put("name", fileName)
            put("mimeType", mimeType)
            val folderId = config.musicFolderId
            if (!folderId.isNullOrEmpty()) {
                put("parents", org.json.JSONArray().put(folderId))
            }
        }
        val conn = URL(INIT_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.outputStream.use { it.write(metadata.toString().toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode != 200) {
            val detail = try {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) {
                ""
            }
            throw IOException("Drive upload session failed: HTTP ${conn.responseCode} $detail")
        }
        return conn.getHeaderField("Location")
            ?: throw IOException("Drive upload session returned no session URI.")
    }

    /** Thrown to carry the confirmed file id out of the chunk loop. Not an error. */
    private class UploadComplete(val fileId: String) : IOException("Upload complete: $fileId")
}
