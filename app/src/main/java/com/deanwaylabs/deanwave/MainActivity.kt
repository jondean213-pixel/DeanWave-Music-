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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.deanwaylabs.deanwave.library.DriveAuth
import com.deanwaylabs.deanwave.library.DriveConfig
import com.deanwaylabs.deanwave.library.DriveResumableUploader
import com.deanwaylabs.deanwave.library.TokenDriveCredentials
import com.deanwaylabs.deanwave.library.TorrentResult
import com.deanwaylabs.deanwave.library.TorrentSearchClient
import com.deanwaylabs.deanwave.library.TorrentStreamer
import com.deanwaylabs.deanwave.library.X1337SearchClient
import com.deanwaylabs.deanwave.library.UnconfiguredDriveCredentials
import com.deanwaylabs.deanwave.library.resolveMusicDir
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
 *
 * The music-Drive addition (Oct 25):
 * 4. DriveAuth — Google sign-in for Jon's music-Drive account. SAVE
 *    sends songs to the Drive folder when the account is connected,
 *    and to the phone's Music folder when it is not.
 */
class MainActivity : ComponentActivity() {

    private var controller by mutableStateOf<MediaController?>(null)
    private var torrentStatus by mutableStateOf("")
    private var browseStatus by mutableStateOf("")
    private var downloadingMagnet by mutableStateOf<String?>(null)
    private var driveConnected by mutableStateOf(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Torrent engine: the one addition to last night's app.
    private lateinit var torrentStreamer: TorrentStreamer
    private val sessionManager = SessionManager()

    // Music-Drive sign-in. The folder id is state so the screen picks
    // it up when the background folder lookup lands.
    private lateinit var driveAuth: DriveAuth
    private var driveFolderId by mutableStateOf<String?>(null)

    private val driveSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Thread {
                try {
                    val ok = driveAuth.handleSignInResult(result.data)
                    if (ok) {
                        val folderId = driveAuth.musicFolderIdBlocking()
                        mainHandler.post {
                            driveFolderId = folderId
                            driveConnected = true
                            browseStatus = "Music Drive connected — SAVE now sends " +
                                "songs to your Drive."
                        }
                    } else {
                        mainHandler.post {
                            val why = driveAuth.lastSignInError
                            browseStatus = "Music Drive sign-in didn't finish" +
                                (if (why != null) " — $why" else "") +
                                ". Try Connect again."
                        }
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        browseStatus = "Music Drive sign-in failed: ${e.message}"
                    }
                }
            }.start()
        }

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

        driveAuth = DriveAuth(this)
        driveConnected = driveAuth.isConnected()
        if (driveConnected) {
            // Folder id is lazy: SAVE falls back to the Drive root when
            // it is not ready yet, so this never blocks the screen.
            Thread {
                try {
                    val folderId = driveAuth.musicFolderIdBlocking()
                    mainHandler.post { driveFolderId = folderId }
                } catch (_: Exception) {
                }
            }.start()
        }

        setContent {
            DeanWaveTheme {
                NowPlayingScreen(
                    controller = controller,
                    onTorrentStream = { result -> streamMagnet(result) },
                    torrentStatus = torrentStatus,
                    onTorrentDownload = { result -> downloadTorrent(result) },
                    browseStatus = browseStatus,
                    downloadingMagnet = downloadingMagnet,
                    driveConnected = driveConnected,
                    onConnectDrive = { driveSignInLauncher.launch(driveAuth.signInIntent()) },
                    driveCredentials = if (driveConnected) {
                        TokenDriveCredentials(driveAuth)
                    } else {
                        UnconfiguredDriveCredentials
                    },
                    driveFolderId = driveFolderId
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
                mainHandler.post {
                    controller = built
                    // Surface player errors on screen: a stream failure
                    // used to sit silent (loaded, play button dead, no
                    // message), because nothing listened for it.
                    built.addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            mainHandler.post {
                                torrentStatus = "Player error: ${error.message}"
                            }
                        }
                    })
                }
            } catch (_: Exception) {
                // Controller failed to connect: the screen stays inert
                // (null controller) instead of crashing.
            }
        }.start()
    }

    /**
     * Streams a browse row. Main-index rows carry their magnet; backup
     * rows carry only a detail page, so the magnet is fetched from it
     * first and the stream starts from there.
     */
    private fun streamMagnet(result: TorrentResult) {
        if (result.magnet.isNotBlank()) {
            streamMagnet(result.magnet, result.name)
            return
        }
        val detailUrl = result.detailUrl
        if (detailUrl.isNullOrEmpty()) {
            torrentStatus = "Couldn't open that song — try another."
            return
        }
        torrentStatus = "Finding the song…"
        Thread {
            try {
                val magnet = X1337SearchClient().resolveMagnet(detailUrl)
                if (magnet.isNullOrEmpty()) {
                    mainHandler.post {
                        torrentStatus = "Couldn't open that song — try another."
                    }
                } else {
                    mainHandler.post { streamMagnet(magnet, result.name) }
                }
            } catch (e: Exception) {
                mainHandler.post { torrentStatus = "Couldn't open that song — try another." }
            }
        }.start()
    }
    private fun streamMagnet(magnet: String, displayName: String = "Torrent stream") {
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
                                val (artist, _) =
                                    TorrentSearchClient.parseArtistTitle(displayName)
                                val item = MediaItem.Builder()
                                    .setUri(proxyUrl)
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(displayName)
                                            .setArtist(
                                                artist.ifEmpty { "Magnet link" }
                                            )
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
                    },
                    onRetry = {
                        mainHandler.post {
                            torrentStatus = "Still looking for peers, trying again…"
                        }
                    }
                )
            } catch (e: Exception) {
                mainHandler.post { torrentStatus = "Stream failed: ${e.message}" }
            }
        }.start()
    }

    /**
     * Downloads a browse row. The row key (detail page for backup rows,
     * magnet for main-index rows) is what the screen tracks, so the
     * SAVE button disables for exactly the row downloading. Backup
     * rows resolve their magnet from the detail page first.
     */
    private fun downloadTorrent(result: TorrentResult) {
        downloadingMagnet = result.detailUrl ?: result.magnet
        if (result.magnet.isNotBlank()) {
            downloadTorrentResolved(result.magnet, result.name)
            return
        }
        val detailUrl = result.detailUrl
        if (detailUrl.isNullOrEmpty()) {
            downloadingMagnet = null
            browseStatus = "Couldn't open that song — try another."
            return
        }
        browseStatus = "Finding the song…"
        Thread {
            try {
                val magnet = X1337SearchClient().resolveMagnet(detailUrl)
                if (magnet.isNullOrEmpty()) {
                    mainHandler.post {
                        downloadingMagnet = null
                        browseStatus = "Couldn't open that song — try another."
                    }
                } else {
                    mainHandler.post { downloadTorrentResolved(magnet, result.name) }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    downloadingMagnet = null
                    browseStatus = "Couldn't open that song — try another."
                }
            }
        }.start()
    }

    /**
     * Downloads a magnet link whole. When the music Drive is connected,
     * the songs go to the Drive folder and the phone copy is deleted —
     * the phone keeps nothing, which is what a nearly-full phone needs.
     * When it is not connected, the songs land in the phone's Music
     * folder, as before. Progress reports back through [browseStatus];
     * the download dies with the app, so the status line says to keep
     * the app open. The row key is set by the caller and only cleared
     * here, at the end.
     */
    private fun downloadTorrentResolved(magnet: String, displayName: String) {
        if (driveConnected) {
            downloadTorrentToDrive(magnet, displayName)
            return
        }
        val musicDir = resolveMusicDir(this)
        if (musicDir == null) {
            downloadingMagnet = null
            browseStatus = "No writable Music folder found — download can't land anywhere."
            return
        }
        browseStatus = "Downloading $displayName… keep the app open until it finishes."
        Thread {
            try {
                torrentStreamer.downloadToMusic(
                    magnetUrl = magnet,
                    musicDir = musicDir,
                    onProgress = { done, total ->
                        mainHandler.post {
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            browseStatus = "Downloading… $pct%"
                        }
                    },
                    onDone = { saved ->
                        mainHandler.post {
                            downloadingMagnet = null
                            browseStatus = "Saved ${saved.size} track(s) to your Music " +
                                "folder — SCAN LIBRARY to see them."
                        }
                    },
                    onError = { message ->
                        mainHandler.post {
                            downloadingMagnet = null
                            browseStatus = message
                        }
                    }
                )
            } catch (e: Exception) {
                mainHandler.post {
                    downloadingMagnet = null
                    browseStatus = "Download failed: ${e.message}"
                }
            }
        }.start()
    }

    /** SAVE with the music Drive connected: download, upload, clean up. */
    private fun downloadTorrentToDrive(magnet: String, displayName: String) {
        browseStatus = "Downloading $displayName… then sending it to your " +
            "music Drive. Keep the app open until it finishes."
        Thread {
            try {
                torrentStreamer.downloadToCache(
                    magnetUrl = magnet,
                    onProgress = { done, total ->
                        mainHandler.post {
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            browseStatus = "Downloading… $pct%"
                        }
                    },
                    onDone = { files, base ->
                        try {
                            val uploader = DriveResumableUploader()
                            val config = DriveConfig(
                                musicFolderId = driveFolderId,
                                accountLabel = "Music Drive"
                            )
                            val credentials = TokenDriveCredentials(driveAuth)
                            var uploaded = 0
                            for (file in files) {
                                mainHandler.post {
                                    browseStatus = "Uploading to Drive… " +
                                        "${uploaded + 1} of ${files.size}"
                                }
                                uploader.upload(
                                    file = file,
                                    fileName = file.name,
                                    mimeType = mimeTypeFor(file),
                                    config = config,
                                    credentials = credentials,
                                    onProgress = { done, total ->
                                        mainHandler.post {
                                            val pct = if (total > 0) {
                                                (done * 100 / total).toInt()
                                            } else {
                                                0
                                            }
                                            browseStatus = "Uploading ${file.name}… $pct%"
                                        }
                                    }
                                )
                                uploaded++
                            }
                            // Uploads confirmed: the phone copy goes, so
                            // playing never eats the phone's free space.
                            try {
                                if (base.isDirectory) base.deleteRecursively() else base.delete()
                            } catch (_: Exception) {
                            }
                            mainHandler.post {
                                downloadingMagnet = null
                                browseStatus = "Saved $uploaded track(s) to your music Drive."
                            }
                        } catch (e: Exception) {
                            // The download stays on the phone, so tapping
                            // SAVE again retries the upload without
                            // re-downloading the torrent.
                            mainHandler.post {
                                downloadingMagnet = null
                                browseStatus = "Drive upload failed: ${e.message} — " +
                                    "the download is still on your phone, " +
                                    "tap SAVE again to retry."
                            }
                        }
                    },
                    onError = { message ->
                        mainHandler.post {
                            downloadingMagnet = null
                            browseStatus = message
                        }
                    }
                )
            } catch (e: Exception) {
                mainHandler.post {
                    downloadingMagnet = null
                    browseStatus = "Download failed: ${e.message}"
                }
            }
        }.start()
    }

    /** Drive needs a MIME type per file; unknown audio stays generic. */
    private fun mimeTypeFor(file: java.io.File): String {
        return when (file.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/x-wav"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "opus" -> "audio/opus"
            "aac" -> "audio/aac"
            else -> "application/octet-stream"
        }
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
