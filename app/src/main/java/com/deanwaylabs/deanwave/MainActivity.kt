package com.deanwaylabs.deanwave

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.deanwaylabs.deanwave.library.LocalLibraryScanner
import com.deanwaylabs.deanwave.library.TorrentStreamer
import com.frostwire.jlibtorrent.SessionManager

class MainActivity : AppCompatActivity() {

    private lateinit var libraryScanner: LocalLibraryScanner
    private lateinit var torrentStreamer: TorrentStreamer
    private val sessionManager = SessionManager.instance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize our internal engines inside the native app layout
        libraryScanner = LocalLibraryScanner(this)
        torrentStreamer = TorrentStreamer(sessionManager)

        // Start the background jLibtorrent network loop natively
        if (!sessionManager.isRunning) {
            sessionManager.start()
        }

        // Trigger our background scanner to discover music on the storage drive
        val localTracks = libraryScanner.scanLocalDrive()
        
        // This is where our navy-and-gold Compose UI wires up next to display the tracks
    }

    override fun onDestroy() {
        torrentStreamer.stopAudioStream()
        if (sessionManager.isRunning) {
            sessionManager.stop()
        }
        super.onDestroy()
    }
}
