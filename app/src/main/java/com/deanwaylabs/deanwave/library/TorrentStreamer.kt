package com.deanwaylabs.deanwave.library

import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.Priority

class TorrentStreamer(private val sessionManager: SessionManager) {
    private var currentHandle: TorrentHandle? = null

    // Connects to a torrent swarm and forces sequential streaming inside the app
    fun startAudioStream(magnetUrl: String, onStreamReady: (proxyUrl: String) -> Unit) {
        // 1. Add the magnet link straight to the embedded session
        currentHandle = sessionManager.download(magnetUrl, null)
        
        // 2. Override default P2P behavior and force sequential chunk downloading
        currentHandle?.setSequentialDownload(true)
        
        // 3. Force maximum priority on the first 5% of files to skip initial buffering lag
        prioritizeStartingPieces()

        // 4. Return an internal localhost proxy path that ExoPlayer can play from
        val localhostProxyUrl = "http://127.0.0{currentHandle?.infoHash()}"
        onStreamReady(localhostProxyUrl)
    }

    private fun prioritizeStartingPieces() {
        val handle = currentHandle ?: return
        val totalPieces = handle.torrentFile()?.numPieces() ?: return
        
        // Push maximum download priority on the initial tracks
        for (i in 0 until minOf(10, totalPieces)) {
            handle.setPieceDeadline(i, 1000)
            handle.setPiecePriority(i, Priority.SEVEN)
        }
    }

    fun stopAudioStream() {
        currentHandle?.let {
            sessionManager.remove(it)
            currentHandle = null
        }
    }
}
