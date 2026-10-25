package com.deanwaylabs.deanwave.library

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream

/**
 * Opens a track for lyrics reading, whatever form its Uri takes: a
 * MediaStore content Uri or a direct-scan file Uri. Returns
 * [LyricsResult.None] when the file cannot be opened or has no embedded
 * lyrics — a missing lyric is never an error in the UI.
 */
fun loadLyricsForTrack(context: Context, uriString: String?): LyricsResult {
    if (uriString.isNullOrEmpty()) return LyricsResult.None
    return try {
        val stream = when {
            uriString.startsWith("content://") ->
                context.contentResolver.openInputStream(Uri.parse(uriString))
            uriString.startsWith("file://") ->
                FileInputStream(File(Uri.parse(uriString).path ?: return LyricsResult.None))
            else -> null
        } ?: return LyricsResult.None
        stream.use { LyricsReader.readLyrics(it) }
    } catch (_: Exception) {
        LyricsResult.None
    }
}
