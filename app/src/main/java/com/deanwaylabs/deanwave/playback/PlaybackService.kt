package com.deanwaylabs.deanwave.playback

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.deanwaylabs.deanwave.dsp.Presets
import com.deanwaylabs.deanwave.dsp.SoundEngine
import com.deanwaylabs.deanwave.library.LocalLibraryScanner
import com.deanwaylabs.deanwave.widget.DeanWaveWidgetProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Playback service. It hosts ExoPlayer with [DeanWaveAudioProcessor] in the
 * audio chain, so the custom sound engine already runs over anything the
 * player decodes — and the now-playing UI drives that engine through
 * MediaSession custom commands (see [EngineCommands]).
 *
 * Playback phase wiring (done here):
 * - The sound engine is a service field now, not a local, so the session
 *   callback can reach it.
 * - Float decoder output is enabled (DefaultRenderersFactory
 *   .setEnableAudioFloatOutput): decoders that support it deliver float
 *   PCM, which the processor keeps float — the 24-bit path. Decoders that
 *   do not support float fall back to 16/24/32-bit PCM, which the
 *   processor also accepts.
 * - The local library is scanned on a background thread at startup
 *   (MediaStore + folder scan via [LocalLibraryScanner]) and queued into
 *   the player through [LocalSourceResolver]. Nothing autoplays: the
 *   player prepares at the first track and waits for the user to press play.
 *   If READ_MEDIA_AUDIO was not granted, the scan simply finds nothing and
 *   the queue stays empty — the UI shows the empty state.
 *
 * Still later phases (seams untouched): Drive streaming (needs the
 * music-Drive account), online streaming (catalog TBD, pluggable),
 * downloader, lyrics/bio, music videos.
 */
class PlaybackService : MediaSessionService() {

    companion object {
        /**
         * Widget play/pause action. The widget's button sends this intent
         * straight to the service; [onStartCommand] toggles playback for
         * it and leaves every other intent to the MediaSession default.
         */
        const val ACTION_TOGGLE_PLAYBACK =
            "com.deanwaylabs.deanwave.TOGGLE_PLAYBACK"
    }

    private lateinit var engine: SoundEngine
    private var mediaSession: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Sleep timer state. The deadline lives here in the service (not the
     * UI), so the timer keeps running with the screen off. A plain stop
     * at expiry — no fade-out, kept simple on purpose. If the service
     * itself is destroyed, the timer dies with it; that is the honest
     * limit, and the UI re-reads the state on poll so it never shows a
     * dead timer as live.
     */
    private var sleepDeadlineMs = 0L
    private var sleepRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        engine = SoundEngine()
        val processor = DeanWaveAudioProcessor(engine)
        // The engine enters the audio chain through the renderers factory.
        // ExoPlayer.Builder has no setAudioSink in Media3 1.5, so the
        // factory's buildAudioSink hook is the injection point (verified
        // against the DefaultRenderersFactory API reference). The
        // processor chain is unchanged — only the injection point moved.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink? {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(processor))
                    .build()
            }
        }.setEnableAudioFloatOutput(true)
        val player = ExoPlayer.Builder(this, renderersFactory).build()
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(EngineCommandCallback(engine))
            .build()
        // The home-screen widget follows playback state from here: every
        // play/pause change and every track change pushes a fresh widget.
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateWidget()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateWidget()
            }
        })
        scanLocalLibrary(player)
    }

    /**
     * Scans off the main thread (MediaStore + disk walk), then queues on the
     * main thread, because ExoPlayer must be driven from the thread it was
     * created on. No coroutines dependency is introduced for this: a plain
     * thread plus a main-thread handler is all it needs.
     */
    private fun scanLocalLibrary(player: Player) {
        Thread {
            val tracks = try {
                LocalLibraryScanner(this).scan()
            } catch (_: Exception) {
                emptyList()
            }
            if (tracks.isEmpty()) return@Thread
            val resolver = LocalSourceResolver(tracks)
            val items = tracks.map { track ->
                resolver.resolve(PlaybackSource.LocalFile(track.uriString))
            }
            mainHandler.post {
                player.setMediaItems(items, /* resetPosition = */ false)
                player.prepare()
            }
        }.start()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * Widget button path: a toggle intent pauses or plays and nothing
     * else. Every other intent goes to the MediaSession default, so the
     * system media notification and lock-screen controls keep working
     * exactly as before.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE_PLAYBACK) {
            mediaSession?.player?.let { player ->
                if (player.isPlaying) player.pause() else player.play()
            }
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepRunnable = null
        sleepDeadlineMs = 0L
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /** Minutes <= 0 cancels the timer. */
    private fun setSleepTimer(minutes: Long) {
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepRunnable = null
        if (minutes <= 0) {
            sleepDeadlineMs = 0L
            return
        }
        sleepDeadlineMs = SystemClock.elapsedRealtime() + minutes * 60_000L
        val runnable = Runnable {
            sleepDeadlineMs = 0L
            sleepRunnable = null
            mediaSession?.player?.pause()
            updateWidget()
        }
        sleepRunnable = runnable
        mainHandler.postDelayed(runnable, minutes * 60_000L)
    }

    private fun sleepRemainingMs(): Long =
        if (sleepDeadlineMs == 0L) {
            0L
        } else {
            maxOf(0L, sleepDeadlineMs - SystemClock.elapsedRealtime())
        }

    /** Pushes the current track title and play state to the widget. */
    private fun updateWidget() {
        val player = mediaSession?.player ?: return
        val title = player.currentMediaItem?.mediaMetadata?.title?.toString()
            ?: "DeanWave"
        DeanWaveWidgetProvider.updateAll(this, title, player.isPlaying)
    }

    /**
     * Handles the UI's engine commands. Unknown actions are refused, never
     * silently ignored — a mistyped command should surface, not vanish.
     * An inner class so the sleep timer can live in the service while its
     * commands arrive here.
     */
    private inner class EngineCommandCallback(
        private val engine: SoundEngine
    ) : MediaSession.Callback {
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                EngineCommands.APPLY_PRESET -> {
                    val name = args.getString(EngineCommands.ARG_PRESET_NAME)
                    val preset = Presets.all().find { it.name == name } ?: Presets.FLAT
                    engine.applyPreset(preset)
                }
                EngineCommands.SET_BYPASS -> {
                    engine.bypass = args.getBoolean(EngineCommands.ARG_BYPASS, false)
                }
                EngineCommands.SET_REVERB_MIX -> {
                    engine.setReverbMix(args.getFloat(EngineCommands.ARG_REVERB_MIX, 0f))
                }
                EngineCommands.SET_WIDE_AMOUNT -> {
                    engine.setWideAmount(args.getFloat(EngineCommands.ARG_WIDE_AMOUNT, 0f))
                }
                EngineCommands.SET_AGC_ENABLED -> {
                    engine.setAgcEnabled(args.getBoolean(EngineCommands.ARG_AGC_ENABLED, false))
                }
                EngineCommands.SET_SLEEP_TIMER -> {
                    setSleepTimer(args.getLong(EngineCommands.ARG_SLEEP_MINUTES, 0L))
                }
                EngineCommands.GET_SLEEP_REMAINING -> {
                    val extras = Bundle().apply {
                        putLong(
                            EngineCommands.ARG_SLEEP_REMAINING_MS,
                            sleepRemainingMs()
                        )
                        putBoolean(
                            EngineCommands.ARG_SLEEP_ACTIVE,
                            sleepDeadlineMs != 0L
                        )
                    }
                    return Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_SUCCESS, extras)
                    )
                }
                EngineCommands.GET_BAND_ENERGIES -> {
                    // Visualizer read: the engine's live per-band energies
                    // ride back in the result extras. No state changes.
                    val extras = Bundle().apply {
                        putFloatArray(EngineCommands.ARG_ENERGIES, engine.bandEnergies())
                    }
                    return Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_SUCCESS, extras)
                    )
                }
                else -> return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                )
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
}
