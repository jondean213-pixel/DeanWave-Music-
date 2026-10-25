package com.deanwaylabs.deanwave.library

import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import java.io.File

/**
 * Torrent streaming for DeanWave-Music-: the one addition to last night's
 * DeanWave. A magnet link joins the swarm, the file downloads through
 * libtorrent, and a localhost proxy ([TorrentProxyServer]) serves the
 * growing file to ExoPlayer with Range support, so playback starts
 * before the download finishes.
 *
 * Flow: fetchMagnet (blocks, so the caller must be off the main thread)
 * → bdecode → download → find the handle → deadlines on the head pieces
 * → resolve the playable file → start the proxy → hand ExoPlayer the
 * REAL proxy URL. The old placeholder URL (http://127.0.0.1/<hash>,
 * nothing listening) is gone.
 *
 * The playable file is resolved from the filesystem, not from libtorrent
 * file-table calls: a single-file torrent's file is downloadDir/<name>;
 * a multi-file torrent's audio is the largest audio file under
 * downloadDir/<name>. Files appear when the torrent is added, so a short
 * poll covers slow creation.
 */
class TorrentStreamer(private val sessionManager: SessionManager, private val downloadDir: File) {
    private var currentHandle: TorrentHandle? = null
    private var proxyServer: TorrentProxyServer? = null

    fun startAudioStream(
        magnetUrl: String,
        onStreamReady: (proxyUrl: String) -> Unit,
        onError: (message: String) -> Unit = {}
    ) {
        try {
            // 1. Fetch the torrent metadata for the magnet link, then decode it.
            // fetchMagnet blocks until metadata arrives (or times out), so call
            // this off the main thread.
            val metadata = sessionManager.fetchMagnet(magnetUrl, 30, downloadDir)
                ?: run {
                    onError("Couldn't fetch that magnet link — check the link and try again.")
                    return
                }
            val torrentInfo = TorrentInfo.bdecode(metadata)

            // 2. Add the torrent to the session. download() returns void, so the
            // handle is looked up afterwards by info hash.
            sessionManager.download(torrentInfo, downloadDir)
            val handle = sessionManager.find(torrentInfo.infoHashV1())
                ?: run {
                    onError("Torrent added but no handle came back — try again.")
                    return
                }
            currentHandle = handle

            // 3. Force the head of the file to download first: deadlines on the
            // first pieces pull them ahead of the rest (libtorrent's streaming
            // mechanism — piece deadlines, not a sequential flag, which this
            // libtorrent version no longer offers as a handle method).
            prioritizeStartingPieces()

            // 4. Resolve the playable file and start the proxy on it.
            val torrentFile = handle.torrentFile()
                ?: run {
                    onError("Torrent has no file info yet — try again.")
                    return
                }
            val streamFile = resolveStreamFile(torrentFile.name())
                ?: run {
                    onError("No playable audio file found in that torrent yet.")
                    return
                }
            val totalSize = torrentFile.totalSize()
            if (totalSize <= 0) {
                onError("Torrent reports no size — try again.")
                return
            }
            val pieceLength = torrentFile.pieceLength().toLong()
            val numPieces = torrentFile.numPieces()
            val isAvailable: (Long, Long) -> Boolean = { start, end ->
                if (pieceLength <= 0 || numPieces <= 0) {
                    false
                } else {
                    val firstPiece = (start / pieceLength).toInt()
                    val lastPiece = minOf((end / pieceLength).toInt(), numPieces - 1)
                    if (firstPiece > lastPiece) {
                        false
                    } else {
                        (firstPiece..lastPiece).all { handle.havePiece(it) }
                    }
                }
            }
            val proxy = TorrentProxyServer(streamFile, totalSize, isAvailable)
            proxy.start()
            proxyServer = proxy

            // 5. Return the REAL localhost proxy URL that ExoPlayer can play from.
            onStreamReady(proxy.url)
        } catch (e: Exception) {
            onError("Stream failed: ${e.message}")
        }
    }

    private fun prioritizeStartingPieces() {
        val handle = currentHandle ?: return
        val totalPieces = handle.torrentFile()?.numPieces() ?: return

        // Push deadlines on the initial tracks to skip initial buffering lag
        for (i in 0 until minOf(10, totalPieces)) {
            handle.setPieceDeadline(i, 1000)
        }
    }

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "aiff", "aif"
    )

    private fun resolveStreamFile(torrentName: String): File? {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val base = File(downloadDir, torrentName)
            if (base.isFile) return base
            if (base.isDirectory) {
                val best = findLargestAudio(base, depth = 0)
                if (best != null) return best
            }
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                return null
            }
        }
        return null
    }

    private fun findLargestAudio(dir: File, depth: Int): File? {
        if (depth > 4) return null
        val children = try {
            dir.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return null
        var best: File? = null
        for (child in children) {
            try {
                when {
                    child.isFile && child.extension.lowercase() in AUDIO_EXTENSIONS -> {
                        if (best == null || child.length() > best.length()) best = child
                    }
                    child.isDirectory && !child.name.startsWith(".") -> {
                        val nested = findLargestAudio(child, depth + 1)
                        if (nested != null && (best == null || nested.length() > best.length())) {
                            best = nested
                        }
                    }
                }
            } catch (_: SecurityException) {
                // Unreadable entry: skip it.
            }
        }
        return best
    }

    fun stopAudioStream() {
        try {
            proxyServer?.stop()
        } catch (_: Exception) {
        }
        proxyServer = null
        currentHandle?.let {
            sessionManager.remove(it)
            currentHandle = null
        }
    }
}
