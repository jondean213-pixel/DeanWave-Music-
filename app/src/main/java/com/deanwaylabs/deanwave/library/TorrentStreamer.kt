package com.deanwaylabs.deanwave.library

import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import java.io.File

class TorrentStreamer(private val sessionManager: SessionManager, private val downloadDir: File) {
    private var currentHandle: TorrentHandle? = null

    // Connects to a torrent swarm and forces sequential streaming inside the app
    fun startAudioStream(magnetUrl: String, onStreamReady: (proxyUrl: String) -> Unit) {
        // 1. Fetch the torrent metadata for the magnet link, then decode it.
        // fetchMagnet blocks until metadata arrives (or times out), so call
        // this off the main thread.
        val metadata = sessionManager.fetchMagnet(magnetUrl, 30, downloadDir) ?: return
        val torrentInfo = TorrentInfo.bdecode(metadata)

        // 2. Add the torrent to the session. download() returns void, so the
        // handle is looked up afterwards by info hash.
        sessionManager.download(torrentInfo, downloadDir)
        currentHandle = sessionManager.find(torrentInfo.infoHashV1())

        // 3. Force the head of the file to download first: deadlines on the
        // first pieces pull them ahead of the rest (libtorrent's streaming
        // mechanism — piece deadlines, not a sequential flag, which this
        // libtorrent version no longer offers as a handle method).
        prioritizeStartingPieces()

        // 4. Return an internal localhost proxy path that ExoPlayer can play from.
        // NOTE: no local proxy server exists yet — this URL is a placeholder
        // until one is built; nothing can play from it today.
        val localhostProxyUrl = "http://127.0.0.1/${currentHandle?.infoHash()}"
        onStreamReady(localhostProxyUrl)
    }

    private fun prioritizeStartingPieces() {
        val handle = currentHandle ?: return
        val totalPieces = handle.torrentFile()?.numPieces() ?: return

        // Push deadlines on the initial tracks to skip initial buffering lag
        for (i in 0 until minOf(10, totalPieces)) {
            handle.setPieceDeadline(i, 1000)
        }
    }

    fun stopAudioStream() {
        currentHandle?.let {
            sessionManager.remove(it)
            currentHandle = null
        }
    }
}
