package com.deanwaylabs.deanwave.playback

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var exoPlayer: ExoPlayer

    override fun onCreate() {
        super.onCreate()
        
        // Initialize the underlying player engine
        exoPlayer = ExoPlayer.Builder(this).build()

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
