package com.deanwaylabs.deanwave.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Album art for the browse window, from the iTunes Search API (public,
 * no key, no account). One lookup per artist + title: the first album
 * match's art, at 600 px, downscaled to ~200 px for a row thumbnail.
 *
 * Why iTunes and not MusicBrainz here: the browse rows need art for
 * whatever messy torrent name came back, and iTunes answers a plain
 * text search with art in the same response. MusicBrainz stays the
 * biography source; this client never touches it.
 *
 * Lookups are cached in memory (100 entries) so scrolling the window
 * never re-hits the network, and failures return null — the row shows
 * the DeanWave mark placeholder instead. Never a crash, never a
 * fabricated picture.
 *
 * Blocking; call off the main thread.
 */
object CoverArtClient {
    private const val SEARCH_URL = "https://itunes.apple.com/search"

    private val cache = object : LinkedHashMap<String, Bitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            return size > 100
        }
    }

    fun lookupArtwork(artist: String, title: String): Bitmap? {
        val key = "${artist.lowercase()}|${title.lowercase()}"
        synchronized(cache) {
            cache[key]?.let { return it }
        }
        // Album first (the art the user recognizes), artist alone as fallback.
        val art = tryAlbumSearch("$artist $title")
            ?: (if (artist.isNotEmpty()) tryAlbumSearch(artist) else null)
        if (art != null) {
            synchronized(cache) { cache[key] = art }
        }
        return art
    }

    private fun tryAlbumSearch(term: String): Bitmap? {
        if (term.isBlank()) return null
        return try {
            val encoded = URLEncoder.encode(term, "UTF-8")
            val url = URL("$SEARCH_URL?term=$encoded&entity=album&limit=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(body).optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            val artUrl = results.optJSONObject(0)?.optString("artworkUrl100")
                ?.takeIf { it.isNotEmpty() } ?: return null
            val bigUrl = artUrl.replace("100x100bb.jpg", "600x600bb.jpg")
            downloadThumbnail(bigUrl)
        } catch (_: Exception) {
            null
        }
    }

    private fun downloadThumbnail(artUrl: String): Bitmap? {
        return try {
            val conn = URL(artUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val bytes = conn.inputStream.use { it.readBytes() }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > 200 || bounds.outHeight / sampleSize > 200) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: Exception) {
            null
        }
    }
}
