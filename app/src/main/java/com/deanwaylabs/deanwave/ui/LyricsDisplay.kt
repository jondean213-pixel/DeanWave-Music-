package com.deanwaylabs.deanwave.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deanwaylabs.deanwave.library.LyricsResult
import com.deanwaylabs.deanwave.library.SyncedLyricLine

/**
 * Lyrics, from the file's own tags only — never scraped.
 *
 * Synced lyrics show as a karaoke window: the line playing now, in gold,
 * with two lines before and two after it, updating as [positionMs]
 * moves. No scrolling APIs are needed: the window itself moves, so the
 * content never has to be scrolled into view. Unsynced lyrics show as
 * plain text (the parent column scrolls). No lyrics means one honest
 * line, not a blank hole.
 */
@Composable
fun LyricsDisplay(
    lyrics: LyricsResult,
    positionMs: Long,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        when (lyrics) {
            is LyricsResult.None -> {
                Text(
                    text = "No lyrics in this file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            is LyricsResult.Unsynced -> {
                Text(
                    text = lyrics.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            is LyricsResult.Synced -> {
                SyncedWindow(lines = lyrics.lines, positionMs = positionMs)
            }
        }
    }
}

@Composable
private fun SyncedWindow(lines: List<SyncedLyricLine>, positionMs: Long) {
    if (lines.isEmpty()) return
    var active = 0
    for (i in lines.indices) {
        if (lines[i].timeMs <= positionMs) active = i else break
    }
    val from = maxOf(0, active - 2)
    val to = minOf(lines.size, active + 3)
    Column {
        for (i in from until to) {
            val line = lines[i]
            val isActive = i == active
            Text(
                text = line.text,
                style = if (isActive) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                }
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
