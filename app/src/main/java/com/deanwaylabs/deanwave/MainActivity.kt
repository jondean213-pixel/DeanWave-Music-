package com.deanwaylabs.deanwave

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.deanwaylabs.deanwave.library.LocalLibraryScanner
import com.deanwaylabs.deanwave.library.TorrentStreamer
import com.deanwaylabs.deanwave.playback.PlaybackService
import com.deanwaylabs.deanwave.ui.NowPlayingScreen
import com.frostwire.jlibtorrent.SessionManager
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : AppCompatActivity() {

    private lateinit var libraryScanner: LocalLibraryScanner
    private lateinit var torrentStreamer: TorrentStreamer
    private val sessionManager = SessionManager()
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private var trackTitle by mutableStateOf("No Track Loaded")
    private var trackArtist by mutableStateOf("Unknown Artist")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize our internal engines
        libraryScanner = LocalLibraryScanner(this)
        torrentStreamer = TorrentStreamer(sessionManager, cacheDir)

        // Start the background jLibtorrent network loop natively
        if (!sessionManager.isRunning) {
            sessionManager.start()
        }

        // Connect the play button to the real player service.
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()

        // Show the navy-and-gold Compose UI, with the buttons wired to real work.
        setContent {
            NowPlayingScreen(
                currentTrackTitle = trackTitle,
                currentArtist = trackArtist,
                onPlayPauseClicked = { togglePlayPause() },
                onLocalScanRequested = { runScan() }
            )
        }
    }

    private fun togglePlayPause() {
        val controller = try {
            controllerFuture?.get()
        } catch (e: Exception) {
            null
        } ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    private fun runScan() {
        val tracks = libraryScanner.scanLocalDrive()
        trackTitle = if (tracks.isEmpty()) {
            "No local tracks found"
        } else {
            "${tracks.size} local tracks found"
        }
        trackArtist = tracks.firstOrNull()?.nameWithoutExtension ?: "Unknown Artist"
    }

    override fun onDestroy() {
        torrentStreamer.stopAudioStream()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        if (sessionManager.isRunning) {
            sessionManager.stop()
        }
        super.onDestroy()
    }
}
