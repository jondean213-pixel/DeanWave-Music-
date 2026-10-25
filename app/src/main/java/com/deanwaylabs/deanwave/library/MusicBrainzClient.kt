package com.deanwaylabs.deanwave.library

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * What MusicBrainz knows about an artist. Only public facts: type, the
 * year the act formed, area, and tags. Nothing private, nothing written
 * back — this client only ever reads.
 */
data class ArtistBio(
    val name: String,
    /** e.g. "Group", "Person". Null when MusicBrainz does not say. */
    val type: String?,
    /** The year the band formed, from life-span begin. Null when unknown. */
    val formedYear: Int?,
    val area: String?,
    val disambiguation: String?,
    val tags: List<String>
)

/**
 * Read-only MusicBrainz client (public API, no keys, no writes).
 *
 * Rules it follows, because MusicBrainz asks clients to:
 * - One request per second, enforced here with a lock + sleep (this runs
 *   on a background thread, never the main thread).
 * - A descriptive User-Agent naming the app. No contact is attached
 *   because DeanWave sends no private data anywhere.
 * - Results are cached on the device (cacheDir) for 30 days (7 days for
 *   "not found"), so repeat views never re-hit the network.
 *
 * When no bio is found — unknown artist, no network, any failure — this
 * returns null and the UI says "bio unavailable" honestly. Details are
 * never fabricated: a missing formed year stays missing.
 *
 * Blocking; call off the main thread.
 */
class MusicBrainzClient(
    private val context: Context,
    private val userAgent: String = "DeanWave/0.1 (local music player)"
) {
    companion object {
        private const val SEARCH_URL = "https://musicbrainz.org/ws/2/artist/"
        private const val MIN_INTERVAL_MS = 1000L
        private const val FOUND_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val NOT_FOUND_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }

    private val lock = Any()
    private var lastRequestMs = 0L

    fun lookupArtist(name: String): ArtistBio? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == "Unknown artist") return null
        val key = trimmed.lowercase()
        when (val cached = readCache(key)) {
            is CacheRead.Hit -> return cached.bio
            is CacheRead.NotFound -> return null // Tombstone: don't re-hit the network.
            is CacheRead.Miss -> Unit // Fall through to the network.
        }
        val bio = fetchFromNetwork(trimmed)
        writeCache(key, bio)
        return bio
    }

    private fun fetchFromNetwork(name: String): ArtistBio? {
        synchronized(lock) {
            val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRequestMs)
            if (wait > 0) {
                try {
                    Thread.sleep(wait)
                } catch (_: InterruptedException) {
                    // Throttle skipped; the request still goes out.
                }
            }
            lastRequestMs = System.currentTimeMillis()
        }
        return try {
            val escaped = name.replace("\\", "\\\\").replace("\"", "\\\"")
            val query = URLEncoder.encode("artist:\"$escaped\"", "UTF-8")
            val url = URL("$SEARCH_URL?query=$query&fmt=json&limit=5")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseArtistSearch(body, name)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Picks the best artist match: an exact (case-insensitive) name match
     * first, otherwise the first result. Returns null when there are none.
     * Pure parsing (org.json) — kept separate so the shape is reviewable.
     */
    internal fun parseArtistSearch(body: String, wantedName: String): ArtistBio? {
        val artists = JSONObject(body).optJSONArray("artists") ?: return null
        if (artists.length() == 0) return null
        var fallback: JSONObject? = null
        for (i in 0 until artists.length()) {
            val artist = artists.optJSONObject(i) ?: continue
            if (fallback == null) fallback = artist
            if (artist.optString("name").equals(wantedName, ignoreCase = true)) {
                return toBio(artist)
            }
        }
        return fallback?.let { toBio(it) }
    }

    private fun toBio(artist: JSONObject): ArtistBio {
        val begin = artist.optJSONObject("life-span")?.optString("begin")
        val year = begin?.take(4)?.toIntOrNull()
        val tags = mutableListOf<String>()
        val tagArray = artist.optJSONArray("tags")
        if (tagArray != null) {
            val scored = mutableListOf<Pair<String, Int>>()
            for (i in 0 until tagArray.length()) {
                val tag = tagArray.optJSONObject(i) ?: continue
                val tagName = tag.optString("name")
                if (tagName.isNotEmpty()) scored += tagName to tag.optInt("count", 0)
            }
            tags += scored.sortedByDescending { it.second }.take(3).map { it.first }
        }
        return ArtistBio(
            name = artist.optString("name"),
            type = artist.optString("type").ifEmpty { null },
            formedYear = year,
            area = artist.optJSONObject("area")?.optString("name")?.ifEmpty { null },
            disambiguation = artist.optString("disambiguation").ifEmpty { null },
            tags = tags
        )
    }

    // ---- Device cache (cacheDir, JSON, no private data) ----

    private fun cacheFile(): File = File(context.cacheDir, "musicbrainz_cache.json")

    private sealed class CacheRead {
        object Miss : CacheRead()
        object NotFound : CacheRead()
        data class Hit(val bio: ArtistBio) : CacheRead()
    }

    private fun readCache(key: String): CacheRead {
        return try {
            val file = cacheFile()
            if (!file.isFile) return CacheRead.Miss
            val root = JSONObject(file.readText())
            val entry = root.optJSONObject(key) ?: return CacheRead.Miss
            val cachedAt = entry.optLong("cachedAt", 0)
            val found = entry.optBoolean("found", false)
            val ttl = if (found) FOUND_TTL_MS else NOT_FOUND_TTL_MS
            if (System.currentTimeMillis() - cachedAt > ttl) return CacheRead.Miss
            if (!found) return CacheRead.NotFound
            val bio = entry.optJSONObject("bio") ?: return CacheRead.Miss
            CacheRead.Hit(
                ArtistBio(
                    name = bio.optString("name"),
                    type = bio.optString("type").ifEmpty { null },
                    formedYear = if (bio.has("formedYear") && !bio.isNull("formedYear")) {
                        bio.optInt("formedYear")
                    } else null,
                    area = bio.optString("area").ifEmpty { null },
                    disambiguation = bio.optString("disambiguation").ifEmpty { null },
                    tags = bio.optJSONArray("tags")?.let { arr ->
                        List(arr.length()) { i -> arr.optString(i) }.filter { it.isNotEmpty() }
                    } ?: emptyList()
                )
            )
        } catch (_: Exception) {
            CacheRead.Miss
        }
    }

    private fun writeCache(key: String, bio: ArtistBio?) {
        try {
            val file = cacheFile()
            val root = try {
                if (file.isFile) JSONObject(file.readText()) else JSONObject()
            } catch (_: Exception) {
                JSONObject()
            }
            val entry = JSONObject().apply {
                put("cachedAt", System.currentTimeMillis())
                put("found", bio != null)
                if (bio != null) {
                    put("bio", JSONObject().apply {
                        put("name", bio.name)
                        bio.type?.let { put("type", it) }
                        bio.formedYear?.let { put("formedYear", it) }
                        bio.area?.let { put("area", it) }
                        bio.disambiguation?.let { put("disambiguation", it) }
                        put("tags", org.json.JSONArray(bio.tags))
                    })
                }
            }
            root.put(key, entry)
            file.writeText(root.toString())
        } catch (_: Exception) {
            // Cache failure must never break a bio lookup.
        }
    }
}
