package com.deanwaylabs.deanwave.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.deanwaylabs.deanwave.R
import com.deanwaylabs.deanwave.playback.PlaybackService

/**
 * The one DeanWave home-screen widget: the now-playing title and a
 * play/pause button. That is the whole widget, done right — no widget
 * collection, no half-done extras.
 *
 * Updates are pushed, never polled: [PlaybackService] calls [updateAll]
 * on every play/pause change and track change, so the widget always
 * shows the truth without waking the phone on a schedule. The button
 * sends a toggle intent straight to the service, which owns playback.
 */
class DeanWaveWidgetProvider : AppWidgetProvider {

    companion object {
        fun toggleIntent(context: Context): PendingIntent {
            val intent = Intent(context, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_TOGGLE_PLAYBACK)
            return PendingIntent.getService(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun buildViews(context: Context, title: String, isPlaying: Boolean): RemoteViews {
            return RemoteViews(context.packageName, R.layout.widget_now_playing).apply {
                setTextViewText(R.id.widget_title, title)
                setImageViewResource(
                    R.id.widget_play_pause,
                    if (isPlaying) {
                        android.R.drawable.ic_media_pause
                    } else {
                        android.R.drawable.ic_media_play
                    }
                )
                setOnClickPendingIntent(R.id.widget_play_pause, toggleIntent(context))
            }
        }

        fun updateAll(context: Context, title: String, isPlaying: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, DeanWaveWidgetProvider::class.java)
            manager.updateAppWidget(component, buildViews(context, title, isPlaying))
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Freshly placed widget, before the service has pushed any state:
        // the app name and a play button. The first playback change after
        // that replaces it with the real track and state.
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context, "DeanWave", false))
        }
    }
}
