package com.deanwaylabs.deanwave.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.deanwaylabs.deanwave.R
import com.deanwaylabs.deanwave.dsp.Presets
import com.deanwaylabs.deanwave.library.ArtistBio
import com.deanwaylabs.deanwave.library.CoverArtClient
import com.deanwaylabs.deanwave.library.DriveCredentials
import com.deanwaylabs.deanwave.library.UnconfiguredDriveCredentials
import com.deanwaylabs.deanwave.library.LocalLibraryScanner
import com.deanwaylabs.deanwave.library.LocalTrack
import com.deanwaylabs.deanwave.library.LyricsResult
import com.deanwaylabs.deanwave.library.MusicBrainzClient
import com.deanwaylabs.deanwave.library.TorrentResult
import com.deanwaylabs.deanwave.library.TorrentSearchClient
import com.deanwaylabs.deanwave.library.loadLyricsForTrack
import com.deanwaylabs.deanwave.playback.EngineCommands
import com.deanwaylabs.deanwave.playback.LocalSourceResolver
import com.deanwaylabs.deanwave.playback.PlaybackSource
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Now-playing screen in DeanWay navy/gold, wired to the service.
 *
 * Playback phase wiring (kept): transport buttons drive the MediaController
 * (standard Player commands), preset chips and the bypass switch drive the
 * service's sound engine through MediaSession custom commands
 * ([EngineCommands]), album art sits centered in a gold frame.
 *
 * This phase adds:
 * - The beam visualizer behind the art: gold beams radiating and pulsing
 *   plus the drifting Circle D, drawn from the engine's live per-band
 *   energies, polled at ~30 Hz while playing through a custom command.
 *   When playback pauses, polling stops and the beams settle to their
 *   faint base glow.
 * - Lyrics from the file's own tags (synced window following the playing
 *   position, unsynced as text, honest "none" line otherwise).
 * - Band biography from MusicBrainz (formed year included) or an honest
 *   "unavailable" line — never fabricated.
 * - A download-to-Drive section (account connection still pending).
 *
 * The column scrolls: lyrics, bio, and download sections live below the
 * sound controls. Polling uses plain Handlers, the pattern this app
 * already uses — no new dependencies.
 *
 * Still later: Drive streaming, online streaming, catalog browsing UI,
 * two-way engine state sync.
 *
 * DeanWave-Music- additions (the only changes from last night):
 * - A Library section: scan the phone/SD card on demand and tap any
 *   track to queue the whole scan and play it, through the same
 *   LocalSourceResolver the service uses at startup.
 * - A Torrent stream section: paste a magnet link, tap Stream, and the
 *   activity's TorrentStreamer + localhost proxy feed the URL to the
 *   player. The screen itself never touches the torrent engine; it
 *   reports the activity's [torrentStatus] honestly.
 * - A Browse window: the song list loads itself on launch from a
 *   public torrent index, with album art per row; tap PLAY to stream
 *   or SAVE to download the whole torrent into the Music folder.
 *   The search bar is only for specific songs. The magnet box stays
 *   as the fallback.
 * - Perfect-app additions: Space controls (reverb mix, stereo width,
 *   AGC switch) driving the live engine through custom commands, and a
 *   sleep timer whose deadline lives in the service. The widget and the
 *   lock-screen controls live outside this screen — the widget on the
 *   home screen, the lock-screen transport in the system media
 *   notification driven by the same MediaSession.
 */
@Composable
fun NowPlayingScreen(
    controller: MediaController?,
    onTorrentStream: (String, String) -> Unit = { _, _ -> },
    torrentStatus: String = "",
    onTorrentDownload: (String, String) -> Unit = { _, _ -> },
    browseStatus: String = "",
    downloadingMagnet: String? = null,
    driveConnected: Boolean = false,
    onConnectDrive: () -> Unit = {},
    driveCredentials: DriveCredentials = UnconfiguredDriveCredentials,
    driveFolderId: String? = null
) {
    val context = LocalContext.current
    val scanHandler = remember { Handler(Looper.getMainLooper()) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentItem by remember { mutableStateOf<MediaItem?>(null) }
    var selectedPreset by remember { mutableStateOf(Presets.FLAT.name) }
    var bypass by remember { mutableStateOf(false) }
    var reverbMix by remember { mutableStateOf(0f) }
    var wideAmount by remember { mutableStateOf(0f) }
    var agcEnabled by remember { mutableStateOf(false) }
    var sleepMinutes by remember { mutableStateOf(0) }
    var sleepRemainingMs by remember { mutableStateOf(0L) }
    var sleepActive by remember { mutableStateOf(false) }
    var energies by remember { mutableStateOf(FloatArray(8)) }
    var positionMs by remember { mutableStateOf(0L) }
    var lyrics by remember { mutableStateOf<LyricsResult?>(null) }
    var bio by remember { mutableStateOf<ArtistBio?>(null) }
    var bioLoadedFor by remember { mutableStateOf<String?>(null) }
    var libraryTracks by remember { mutableStateOf<List<LocalTrack>>(emptyList()) }
    var libraryStatus by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var magnet by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var browseResults by remember { mutableStateOf<List<TorrentResult>>(emptyList()) }
    var browseSearching by remember { mutableStateOf(false) }
    var browseMessage by remember { mutableStateOf("") }
    val browseHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(controller) {
        if (controller == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentItem = mediaItem
                }
            }
            controller.addListener(listener)
            isPlaying = controller.isPlaying
            currentItem = controller.currentMediaItem
            onDispose { controller.removeListener(listener) }
        }
    }

    // Beam polling: ~30 Hz sampled reads of the engine's band energies
    // while playing. Pausing stops the loop and settles the beams.
    DisposableEffect(controller, isPlaying) {
        val handler = Handler(Looper.getMainLooper())
        var stopped = false
        val mainExecutor = Executor { runnable -> handler.post(runnable) }
        val poll = object : Runnable {
            override fun run() {
                if (stopped) return
                val c = controller
                if (c != null) {
                    try {
                        val future = c.sendCustomCommand(
                            EngineCommands.getBandEnergiesCommand(),
                            Bundle.EMPTY
                        )
                        future.addListener(
                            {
                                try {
                                    val arr = future.get().extras
                                        ?.getFloatArray(EngineCommands.ARG_ENERGIES)
                                    if (arr != null) energies = arr
                                } catch (_: Exception) {
                                    // A missed sample is harmless; the next one follows.
                                }
                            },
                            mainExecutor
                        )
                    } catch (_: Exception) {
                        // Command send failed; the next tick retries.
                    }
                }
                handler.postDelayed(this, 33)
            }
        }
        if (isPlaying) {
            handler.post(poll)
        } else {
            energies = FloatArray(8)
        }
        onDispose {
            stopped = true
            handler.removeCallbacks(poll)
        }
    }

    // Lyric position polling: four reads a second while playing is plenty
    // for line timing, and pausing freezes the highlighted line in place.
    DisposableEffect(controller, isPlaying) {
        val handler = Handler(Looper.getMainLooper())
        var stopped = false
        val poll = object : Runnable {
            override fun run() {
                if (stopped) return
                controller?.let { positionMs = it.currentPosition }
                handler.postDelayed(this, 250)
            }
        }
        if (isPlaying) handler.post(poll)
        onDispose {
            stopped = true
            handler.removeCallbacks(poll)
        }
    }

    // Sleep timer polling: every 5 seconds while a timer is set, so the
    // "stops in" line counts down and the chips reset to Off on expiry.
    DisposableEffect(controller, sleepMinutes) {
        val handler = Handler(Looper.getMainLooper())
        var stopped = false
        val mainExecutor = Executor { runnable -> handler.post(runnable) }
        val poll = object : Runnable {
            override fun run() {
                if (stopped) return
                val c = controller
                if (c != null) {
                    try {
                        val future = c.sendCustomCommand(
                            EngineCommands.getSleepRemainingCommand(),
                            Bundle.EMPTY
                        )
                        future.addListener(
                            {
                                try {
                                    val extras = future.get().extras
                                    if (extras != null) {
                                        sleepRemainingMs = extras.getLong(
                                            EngineCommands.ARG_SLEEP_REMAINING_MS, 0L
                                        )
                                        sleepActive = extras.getBoolean(
                                            EngineCommands.ARG_SLEEP_ACTIVE, false
                                        )
                                        if (!sleepActive) sleepMinutes = 0
                                    }
                                } catch (_: Exception) {
                                    // A missed sample is harmless; the next one follows.
                                }
                            },
                            mainExecutor
                        )
                    } catch (_: Exception) {
                        // Command send failed; the next tick retries.
                    }
                }
                handler.postDelayed(this, 5000)
            }
        }
        if (sleepMinutes > 0) handler.post(poll)
        onDispose {
            stopped = true
            handler.removeCallbacks(poll)
        }
    }

    val metadata = currentItem?.mediaMetadata
    val title = metadata?.title?.toString() ?: "No track selected"
    val artist = metadata?.artist?.toString()
        ?: if (currentItem == null) "Your library, your Drive, your sound." else ""
    val artUri = currentItem?.localConfiguration?.uri?.toString()
    // The tagline shown with no track is not an artist — never look it up.
    val bioArtistName = if (currentItem == null) "" else artist

    // Lyrics load on track change, on a background thread.
    DisposableEffect(artUri) {
        val disposed = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        lyrics = null
        if (artUri != null) {
            Thread {
                val loaded = loadLyricsForTrack(context, artUri)
                if (!disposed.get()) handler.post { lyrics = loaded }
            }.start()
        }
        onDispose { disposed.set(true) }
    }

    // Bio load on artist change, on a background thread (rate-limited and
    // cached inside the client, so repeat views cost nothing).
    DisposableEffect(bioArtistName) {
        val disposed = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        bio = null
        bioLoadedFor = null
        if (bioArtistName.isNotEmpty()) {
            Thread {
                val loaded = MusicBrainzClient(context).lookupArtist(bioArtistName)
                if (!disposed.get()) {
                    handler.post {
                        bio = loaded
                        bioLoadedFor = bioArtistName
                    }
                }
            }.start()
        }
        onDispose { disposed.set(true) }
    }

    // Browse window: it fills itself on launch — the search bar is
    // only for specific songs. Plain Thread + Handler, like the rest.
    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        var disposed = false
        browseSearching = true
        Thread {
            val results = try {
                TorrentSearchClient().autoLoad()
            } catch (_: Exception) {
                null
            }
            if (!disposed) {
                handler.post {
                    browseSearching = false
                    if (results == null) {
                        browseMessage = "The song list didn't answer — tap SEARCH to try again."
                    } else {
                        browseResults = results
                    }
                }
            }
        }.start()
        onDispose { disposed = true }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedBackground(modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        Spacer(modifier = Modifier.height(16.dp))
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            BeamVisualizer(
                energies = energies,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
            )
            CenteredAlbumArt(uriString = artUri)
        }
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                if (artist.isNotEmpty()) {
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        enabled = controller != null,
                        onClick = { controller?.seekToPrevious() }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = "Previous",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        enabled = controller != null,
                        onClick = {
                            if (isPlaying) controller?.pause() else controller?.play()
                        }
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    IconButton(
                        enabled = controller != null,
                        onClick = { controller?.seekToNext() }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "Next",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Browse",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Songs load on their own — search only when you want something specific.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search songs") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Button(
                enabled = !browseSearching,
                onClick = {
                    val query = searchQuery.trim()
                    browseSearching = true
                    browseMessage = ""
                    Thread {
                        val results = try {
                            val client = TorrentSearchClient()
                            if (query.isEmpty()) client.autoLoad() else client.search(query).take(50)
                        } catch (_: Exception) {
                            null
                        }
                        browseHandler.post {
                            browseSearching = false
                            if (results == null) {
                                browseMessage = "The song list didn't answer — check your connection and try again."
                            } else {
                                browseResults = results
                                browseMessage = if (results.isEmpty()) {
                                    "No songs found for that search."
                                } else {
                                    ""
                                }
                            }
                        }
                    }.start()
                }
            ) {
                Text("SEARCH")
            }
        }
        if (browseSearching) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Loading songs…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (browseMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = browseMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (browseStatus.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = browseStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        // Music Drive: one row, no settings screen. Connected means SAVE
        // sends songs to the Drive folder; otherwise it lands on the
        // phone. The button opens Google's sign-in for the music-Drive
        // account — no password ever passes through DeanWave.
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (driveConnected) {
                    "Music Drive: Connected"
                } else {
                    "Music Drive: Not connected"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (!driveConnected) {
                Button(onClick = onConnectDrive) {
                    Text("Connect")
                }
            }
        }
        val browseSnapshot = browseResults
        if (browseSnapshot.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            for (result in browseSnapshot) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    BrowseArtThumb(result = result)
                    Spacer(modifier = Modifier.size(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = result.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${result.seeders} seeders • " +
                                TorrentSearchClient.formatSize(result.sizeBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    Button(
                        onClick = { onTorrentStream(result.magnet, result.name) }
                    ) {
                        Text("PLAY")
                    }
                    Spacer(modifier = Modifier.size(4.dp))
                    Button(
                        enabled = downloadingMagnet != result.magnet,
                        onClick = { onTorrentDownload(result.magnet, result.name) }
                    ) {
                        Text(if (downloadingMagnet == result.magnet) "…" else "SAVE")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Library",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            enabled = !scanning,
            onClick = {
                scanning = true
                libraryStatus = ""
                Thread {
                    val tracks = try {
                        LocalLibraryScanner(context).scan()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    scanHandler.post {
                        libraryTracks = tracks
                        libraryStatus = if (tracks.isEmpty()) {
                            "No local tracks found."
                        } else {
                            "${tracks.size} local tracks found — tap one to play."
                        }
                        scanning = false
                    }
                }.start()
            }
        ) {
            Text(if (scanning) "Scanning…" else "SCAN LIBRARY")
        }
        if (libraryStatus.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = libraryStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        val tracksSnapshot = libraryTracks
        if (tracksSnapshot.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            val shown = tracksSnapshot.take(100)
            for ((index, track) in shown.withIndex()) {
                Text(
                    text = "${track.title} — ${track.artist}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val c = controller ?: return@clickable
                            val resolver = LocalSourceResolver(tracksSnapshot)
                            val items = tracksSnapshot.map { t ->
                                resolver.resolve(PlaybackSource.LocalFile(t.uriString))
                            }
                            c.setMediaItems(items, index, 0)
                            c.prepare()
                            c.play()
                        }
                        .padding(vertical = 4.dp)
                )
            }
            if (tracksSnapshot.size > shown.size) {
                Text(
                    text = "…and ${tracksSnapshot.size - shown.size} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Sound",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            for (preset in Presets.all()) {
                FilterChip(
                    selected = selectedPreset == preset.name,
                    onClick = {
                        selectedPreset = preset.name
                        controller?.sendCustomCommand(
                            EngineCommands.applyPresetCommand(),
                            EngineCommands.applyPresetArgs(preset.name)
                        )
                    },
                    label = { Text(preset.name) }
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Bypass engine (A/B)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = bypass,
                onCheckedChange = { checked ->
                    bypass = checked
                    controller?.sendCustomCommand(
                        EngineCommands.setBypassCommand(),
                        EngineCommands.setBypassArgs(checked)
                    )
                }
            )
        }

        // Space controls: live engine effects, not decorations. Each one
        // sends a custom command to the service's engine — the slider
        // moves locally while dragging and lands on release, the AGC
        // switch lands immediately.
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Space",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Reverb",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${(reverbMix * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Slider(
            value = reverbMix,
            onValueChange = { reverbMix = it },
            onValueChangeFinished = {
                controller?.sendCustomCommand(
                    EngineCommands.setReverbMixCommand(),
                    EngineCommands.setReverbMixArgs(reverbMix)
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Wide (stereo)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${(wideAmount * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Slider(
            value = wideAmount,
            onValueChange = { wideAmount = it },
            onValueChangeFinished = {
                controller?.sendCustomCommand(
                    EngineCommands.setWideAmountCommand(),
                    EngineCommands.setWideAmountArgs(wideAmount)
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "AGC (even volume)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = agcEnabled,
                onCheckedChange = { checked ->
                    agcEnabled = checked
                    controller?.sendCustomCommand(
                        EngineCommands.setAgcEnabledCommand(),
                        EngineCommands.setAgcEnabledArgs(checked)
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Sleep timer",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            for (option in listOf(0, 15, 30, 60)) {
                FilterChip(
                    selected = sleepMinutes == option,
                    onClick = {
                        sleepMinutes = option
                        controller?.sendCustomCommand(
                            EngineCommands.setSleepTimerCommand(),
                            EngineCommands.setSleepTimerArgs(option.toLong())
                        )
                    },
                    label = { Text(if (option == 0) "Off" else "$option min") }
                )
            }
        }
        if (sleepActive) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Stops in ${formatSleepRemaining(sleepRemainingMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Torrent stream",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = magnet,
            onValueChange = { magnet = it },
            label = { Text("Magnet link") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            enabled = magnet.isNotBlank(),
            onClick = { onTorrentStream(magnet.trim(), "Torrent stream") }
        ) {
            Text("STREAM TORRENT")
        }
        if (torrentStatus.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = torrentStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        lyrics?.let { loaded ->
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "Lyrics",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            LyricsDisplay(lyrics = loaded, positionMs = positionMs)
        }

        if (bioArtistName.isNotEmpty()) {
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "About the artist",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (bioLoadedFor == bioArtistName) {
                ArtistBioSection(artistName = bioArtistName, bio = bio)
            } else {
                Text(
                    text = "Looking up…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        DownloadSection(
            credentials = driveCredentials,
            musicFolderId = driveFolderId
        )
        Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * One browse-row thumbnail: the album art for the row's song, looked
 * up by artist + title, cached in memory so scrolling never re-hits
 * the network. The DeanWave mark placeholder when no art is found —
 * never a blank hole, never a fabricated picture.
 */
@Composable
private fun BrowseArtThumb(
    result: TorrentResult,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(result.infoHash) { mutableStateOf<ImageBitmap?>(null) }

    DisposableEffect(result.infoHash) {
        val disposed = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val thread = Thread {
            val (artist, title) = TorrentSearchClient.parseArtistTitle(result.name)
            val art = CoverArtClient.lookupArtwork(artist, title)
            if (!disposed.get() && art != null) {
                handler.post { bitmap = art.asImageBitmap() }
            }
        }
        thread.start()
        onDispose {
            disposed.set(true)
            thread.interrupt()
        }
    }

    Box(
        modifier = modifier
            .size(56.dp)
            .border(1.dp, MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        val art = bitmap
        if (art != null) {
            Image(
                bitmap = art,
                contentDescription = "Album art",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp)
            )
        } else {
            Image(
                painter = painterResource(R.drawable.deanwave_mark),
                contentDescription = "DeanWave mark",
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/** Sleep countdown as m:ss, e.g. 27:04. */
private fun formatSleepRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
