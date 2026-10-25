package com.deanwaylabs.deanwave.library

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Localhost HTTP proxy that serves a still-downloading torrent file to
 * ExoPlayer, so playback starts before the download finishes.
 *
 * Design (small, dependency-free — java.net only):
 * - Binds to 127.0.0.1 on an ephemeral port (port 0). Loopback only, so
 *   nothing on the network can reach it, and Android's cleartext policy
 *   exempts loopback — no manifest flag needed.
 * - Range requests (206 Partial Content) are the normal path: ExoPlayer
 *   seeks and streams in ranges. A requested range is served only once
 *   every piece it overlaps is downloaded ([isAvailable] checks
 *   [com.frostwire.jlibtorrent.TorrentHandle.havePiece]); the handler
 *   waits up to [MAX_WAIT_MS] for the range, polling every 100 ms, and
 *   on timeout serves the contiguous available prefix (with correct
 *   Content-Range) or 503 when nothing is available yet. Serving bytes
 *   whose pieces have not landed would play zeroes — that is never done.
 * - A request with no Range header gets a 200 with Content-Length equal
 *   to the full size, streamed progressively: the writer blocks briefly
 *   for not-yet-downloaded bytes (same 60 s stall cap, then the stream
 *   closes and the player re-requests from its position).
 * - HEAD is answered with headers only. Unknown methods get 405.
 * - Lifecycle: [start] returns the port and the URL is
 *   http://127.0.0.1:<port>/stream. [stop] closes the socket; the accept
 *   loop exits and in-flight waits finish within their cap. Call [stop]
 *   from [TorrentStreamer.stopAudioStream].
 */
class TorrentProxyServer(
    private val file: File,
    private val totalSize: Long,
    private val isAvailable: (start: Long, end: Long) -> Boolean
) {
    companion object {
        private const val POLL_MS = 100L
        private const val MAX_WAIT_MS = 60_000L
        private const val BUFFER_SIZE = 32 * 1024
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile
    private var running = false

    var port: Int = -1
        private set

    val url: String
        get() = "http://127.0.0.1:$port/stream"

    @Synchronized
    fun start(): Int {
        if (running) return port
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        running = true
        val thread = Thread({
            while (running) {
                try {
                    val client = socket.accept()
                    Thread({ handle(client) }, "torrent-proxy-conn").apply {
                        isDaemon = true
                    }.start()
                } catch (_: SocketException) {
                    break // stop() closed the socket: normal shutdown.
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }, "torrent-proxy-accept")
        thread.isDaemon = true
        thread.start()
        acceptThread = thread
        return port
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        port = -1
    }

    /** Largest prefix of the requested range whose pieces are all downloaded. */
    private fun contiguousEnd(start: Long, maxEnd: Long): Long {
        if (start > maxEnd) return start - 1
        if (!isAvailable(start, start)) return start - 1
        var low = start
        var high = maxEnd
        // Binary search: isAvailable is prefix-monotone (a gap breaks it).
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (isAvailable(start, mid)) low = mid else high = mid - 1
        }
        return low
    }

    private fun waitFor(start: Long, end: Long): Boolean {
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!running) return false
            if (isAvailable(start, end)) return true
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return isAvailable(start, end)
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 10_000
            val input = socket.getInputStream().bufferedReader()
            val requestLine = try {
                input.readLine()
            } catch (_: SocketTimeoutException) {
                null
            } ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = try {
                    input.readLine()
                } catch (_: SocketTimeoutException) {
                    null
                } ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }
            val out = socket.getOutputStream()
            when (method) {
                "GET", "HEAD" -> serve(out, method == "HEAD", headers["range"])
                else -> writeHeaders(
                    out,
                    "HTTP/1.1 405 Method Not Allowed",
                    listOf("Content-Length: 0", "Connection: close")
                )
            }
            out.flush()
        } catch (_: Exception) {
            // A dropped connection must never take down the server.
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun serve(out: java.io.OutputStream, headOnly: Boolean, rangeHeader: String?) {
        if (totalSize <= 0) {
            writeHeaders(
                out,
                "HTTP/1.1 503 Service Unavailable",
                listOf("Retry-After: 5", "Content-Length: 0", "Connection: close")
            )
            return
        }
        val range = parseRange(rangeHeader, totalSize)
        if (range == null) {
            // Malformed or unsatisfiable range.
            writeHeaders(
                out,
                "HTTP/1.1 416 Range Not Satisfiable",
                listOf(
                    "Content-Range: bytes */$totalSize",
                    "Content-Length: 0",
                    "Connection: close"
                )
            )
            return
        }
        val (start, end, isPartial) = range
        if (isPartial) {
            // Range path: wait for the exact range, else the available prefix.
            val ready = waitFor(start, end)
            val serveEnd = if (ready) end else contiguousEnd(start, end)
            if (serveEnd < start) {
                writeHeaders(
                    out,
                    "HTTP/1.1 503 Service Unavailable",
                    listOf("Retry-After: 5", "Content-Length: 0", "Connection: close")
                )
                return
            }
            val length = serveEnd - start + 1
            writeHeaders(
                out,
                "HTTP/1.1 206 Partial Content",
                listOf(
                    "Accept-Ranges: bytes",
                    "Content-Range: bytes $start-$serveEnd/$totalSize",
                    "Content-Length: $length",
                    "Content-Type: ${contentTypeFor(file.name)}",
                    "Connection: close"
                )
            )
            if (!headOnly) writeBytes(out, start, serveEnd)
        } else {
            // Full-file path: stream progressively as pieces land.
            writeHeaders(
                out,
                "HTTP/1.1 200 OK",
                listOf(
                    "Accept-Ranges: bytes",
                    "Content-Length: $totalSize",
                    "Content-Type: ${contentTypeFor(file.name)}",
                    "Connection: close"
                )
            )
            if (!headOnly) streamProgressively(out, 0L, totalSize - 1)
        }
    }

    /** Streams from start through end, waiting briefly for pieces as they arrive. */
    private fun streamProgressively(out: java.io.OutputStream, start: Long, end: Long) {
        var position = start
        var stallStart = -1L
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            RandomAccessFile(file, "r").use { raf ->
                while (position <= end && running) {
                    val availableLen = file.length()
                    if (position >= availableLen || !isAvailable(position, position)) {
                        if (stallStart < 0) stallStart = System.currentTimeMillis()
                        if (System.currentTimeMillis() - stallStart > MAX_WAIT_MS) break
                        try {
                            Thread.sleep(POLL_MS)
                        } catch (_: InterruptedException) {
                            break
                        }
                        continue
                    }
                    stallStart = -1L
                    val contiguous = contiguousEnd(position, end)
                    val chunkEnd = minOf(contiguous, position + BUFFER_SIZE - 1)
                    raf.seek(position)
                    var remaining = (chunkEnd - position + 1).toInt()
                    while (remaining > 0) {
                        val n = raf.read(buffer, 0, minOf(buffer.size, remaining))
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        position += n
                        remaining -= n
                    }
                    out.flush()
                }
            }
        } catch (_: Exception) {
            // Client went away or the file vanished: close quietly.
        }
    }

    private fun writeBytes(out: java.io.OutputStream, start: Long, end: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                var remaining = end - start + 1
                while (remaining > 0) {
                    val n = raf.read(buffer, 0, minOf(buffer.size, remaining.toInt()))
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    remaining -= n
                }
            }
            out.flush()
        } catch (_: Exception) {
            // Client went away: close quietly.
        }
    }

    private fun writeHeaders(out: java.io.OutputStream, status: String, headers: List<String>) {
        val head = StringBuilder(status).append("\r\n")
        for (h in headers) head.append(h).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.US_ASCII))
    }

    /**
     * Returns Triple(start, end, isPartial), or null for an unsatisfiable
     * range. Null [rangeHeader] means the whole file (not partial).
     */
    private fun parseRange(rangeHeader: String?, total: Long): Triple<Long, Long, Boolean>? {
        if (rangeHeader == null) return Triple(0L, total - 1, false)
        val value = rangeHeader.trim()
        if (!value.startsWith("bytes=")) return null
        val spec = value.removePrefix("bytes=").trim()
        return try {
            when {
                spec.startsWith("-") -> {
                    // Suffix: last N bytes.
                    val suffix = spec.drop(1).toLong()
                    if (suffix <= 0) return null
                    val start = maxOf(0L, total - suffix)
                    Triple(start, total - 1, true)
                }
                spec.contains("-") -> {
                    val dash = spec.indexOf('-')
                    val start = spec.substring(0, dash).toLong()
                    val endText = spec.substring(dash + 1)
                    val end = if (endText.isEmpty()) total - 1 else endText.toLong()
                    if (start < 0 || start >= total || end < start) return null
                    Triple(start, minOf(end, total - 1), true)
                }
                else -> null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun contentTypeFor(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg", "opus" -> "audio/ogg"
            "aiff", "aif" -> "audio/aiff"
            else -> "audio/mpeg"
        }
    }
}
