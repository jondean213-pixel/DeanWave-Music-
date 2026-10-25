package com.deanwaylabs.deanwave.playback

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    private lateinit var engine: SoundEngine
    private var mediaSession: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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

    override fun onDestroy() {
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /**
     * Handles the UI's engine commands. Unknown actions are refused, never
     * silently ignored — a mistyped command should surface, not vanish.
     */
    private class EngineCommandCallback(
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
