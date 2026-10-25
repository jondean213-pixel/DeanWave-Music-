package com.deanwaylabs.deanwave.playback

import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.deanwaylabs.deanwave.dsp.SoundEngine

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var exoPlayer: ExoPlayer

    // The sound engine, kept as a service field so the session callback
    // and the UI can reach it later (bands, presets, bypass).
    private val engine = SoundEngine()

    override fun onCreate() {
        super.onCreate()

        // The engine enters the audio chain through the renderers factory.
        // ExoPlayer.Builder has no setAudioSink in Media3 1.5, so the
        // factory's buildAudioSink hook is the injection point.
        // The service's engine is passed in, so it is the one engine that
        // actually hears the music (no second engine in the processor).
        val processor = DeanWaveAudioProcessor(engine)
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

        // Initialize the underlying player engine
        exoPlayer = ExoPlayer.Builder(this, renderersFactory).build()

        // Create the native media session wrapper
        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setCallback(CustomSessionCallback())
            .build()
    }

    // Handles background control commands from notifications or the UI
    private inner class CustomSessionCallback : MediaSession.Callback {
        // Custom playback commands can be added here as needed
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
