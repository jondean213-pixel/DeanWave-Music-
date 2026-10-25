package com.deanwaylabs.deanwave

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.deanwaylabs.deanwave.library.TorrentStreamer
import com.deanwaylabs.deanwave.playback.PlaybackService
import com.deanwaylabs.deanwave.ui.DeanWaveTheme
import com.deanwaylabs.deanwave.ui.NowPlayingScreen
import com.frostwire.jlibtorrent.SessionManager

/**
 * Entry point — last night's DeanWave structure, plus the torrent addition.
 *
 * Last night's jobs, kept:
 * 1. Audio permission (READ_MEDIA_AUDIO) — requested before the controller
 *    connects, because the service scans the local library at startup and a
 *    scan without the permission finds nothing. If the user declines, the
 *    app still opens with an empty library instead of crashing.
 * 2. MediaController — built asynchronously and handed to the now-playing
 *    screen, which drives playback and the sound engine through it. The
 *    controller is released when the activity goes away.
 *
 * The torrent addition (the only change from last night):
 * 3. TorrentStreamer — the jLibtorrent session starts here, and the
 *    screen's magnet box calls [streamMagnet] off the main thread. When
 *    the proxy is ready, the returned URL becomes the player media item
 *    and plays through the same DSP chain as every other source.
 */
class MainActivity : ComponentActivity() {

    private var controller by mutableStateOf<MediaController?>(null)
    private var torrentStatus by mutableStateOf("")
    private val mainHandler = Handler(Looper.getMainLooper())

    // Torrent engine: the one addition to last night's app.
    private lateinit var torrentStreamer: TorrentStreamer
    private val sessionManager = SessionManager()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Granted or not, the app opens either way — build the controller.
            buildController()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        torrentStreamer = TorrentStreamer(sessionManager, cacheDir)
        if (!sessionManager.isRunning) {
            sessionManager.start()
        }

        setContent {
            DeanWaveTheme {
                NowPlayingScreen(
                    controller = controller,
                    onTorrentStream = { magnet -> streamMagnet(magnet) },
                    torrentStatus = torrentStatus
                )
            }
        }
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            buildController()
        } else {
            permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
        }
    }

    private fun buildController() {
        if (controller != null) return
        val sessionToken = SessionToken(
            this,
            ComponentName(this, PlaybackService::class.java)
        )
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        Thread {
            try {
                val built = future.get()
                mainHandler.post { controller = built }
            } catch (_: Exception) {
                // Controller failed to connect: the screen stays inert
                // (null controller) instead of crashing.
            }
        }.start()
    }

    /**
     * Streams a magnet link: fetch + download + proxy happen on a
     * background thread (fetchMagnet blocks), and the player work happens
     * back on the main thread. The proxy URL plays through the service's
     * ExoPlayer, so the DSP chain (EQ, bass, limiter) treats a torrent
     * exactly like a local file.
     */
    private fun streamMagnet(magnet: String) {
        torrentStatus = "Finding the torrent…"
        Thread {
            try {
                torrentStreamer.startAudioStream(
                    magnetUrl = magnet,
                    onStreamReady = { proxyUrl ->
                        mainHandler.post {
                            val c = controller
                            if (c == null) {
                                torrentStatus = "Player isn't connected yet — tap Stream again."
                            } else {
                                val item = MediaItem.Builder()
                                    .setUri(proxyUrl)
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle("Torrent stream")
                                            .setArtist("Magnet link")
                                            .build()
                                    )
                                    .build()
                                c.setMediaItem(item)
                                c.prepare()
                                c.play()
                                torrentStatus = "Streaming from your torrent."
                            }
                        }
                    },
                    onError = { message ->
                        mainHandler.post { torrentStatus = message }
                    }
                )
            } catch (e: Exception) {
                mainHandler.post { torrentStatus = "Stream failed: ${e.message}" }
            }
        }.start()
    }

    override fun onDestroy() {
        torrentStreamer.stopAudioStream()
        if (sessionManager.isRunning) {
            sessionManager.stop()
        }
        controller?.release()
        controller = null
        super.onDestroy()
    }
}
