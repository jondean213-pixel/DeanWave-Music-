package com.deanwaylabs.deanwave.library

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray

/**
 * One song-window row: a torrent-index hit for music.
 *
 * The index (apibay, the Pirate Bay JSON mirror) answers plain JSON:
 * id, name, info_hash, leechers, seeders, size, num_files, username,
 * added, status, category, imdb. Category 101 is music. Results come
 * back roughly by seeders, but the client sorts them itself so the
 * window always shows the liveliest torrents first.
 *
 * The magnet link is built from the info hash plus public trackers —
 * the index gives no magnet, only the hash. Trackers are what let the
 * phone find the swarm; without them a magnet can sit unfound.
 *
 * Blocking; call off the main thread (the screen uses plain Threads,
 * the pattern this app already uses — no new dependencies).
 */
data class TorrentResult(
    val name: String,
    val infoHash: String,
    val seeders: Int,
    val leechers: Int,
    val sizeBytes: Long,
    val numFiles: Int,
    val magnet: String
)

class TorrentSearchClient {
    companion object {
        private const val SEARCH_URL = "https://apibay.org/q.php"
        private const val MUSIC_CATEGORY = "101"

        // Public trackers, so a magnet finds its swarm. The same short
        // list every torrent app uses — nothing private, nothing custom.
        private val TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://tracker.openbittorrent.com:80",
            "udp://tracker.leechers-paradise.org:6969/announce",
            "udp://tracker.internetwarriors.net:1337/announce",
            "udp://9.rarbg.to:2710/announce"
        )

        /** The window on launch: a broad music query, liveliest first. */
        fun autoLoadQuery(): String = "a"

        fun buildMagnet(infoHash: String, name: String): String {
            val encodedName = URLEncoder.encode(name, "UTF-8")
            val trackers = TRACKERS.joinToString("") { tracker ->
                "&tr=" + URLEncoder.encode(tracker, "UTF-8")
            }
            return "magnet:?xt=urn:btih:$infoHash&dn=$encodedName$trackers"
        }

        /**
         * Splits a torrent name into artist + title for art lookup and
         * now-playing metadata. Names look like
         * "Shinedown - Amaryllis (2012) [MP3 320]": the artist is before
         * the first " - ", the rest is the title with tag groups
         * ([...], bitrate/year tags) stripped. When there is no " - ",
         * the whole name is the title and the artist is empty.
         */
        fun parseArtistTitle(rawName: String): Pair<String, String> {
            val cleaned = rawName
                .replace(Regex("\\[[^\\]]*\\]"), " ")
                .replace(Regex("\\((?i:[^)]*(mp3|flac|kbps|vbr|web|cd|remaster)[^)]*)\\)"), " ")
                .replace(Regex("(?i)\\b(mp3|flac|aac|ogg|opus|wav|vbr|320kbps|256kbps|192kbps|128kbps)\\b"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            val dash = cleaned.indexOf(" - ")
            if (dash < 0) return "" to cleaned
            val artist = cleaned.substring(0, dash).trim()
            var title = cleaned.substring(dash + 3).trim()
            // A trailing bare year "(2012)" or "2012" is not part of the title.
            title = title.replace(Regex("\\(\\d{4}\\)\\s*$"), "").trim()
            title = title.replace(Regex("\\s\\d{4}\\s*$"), "").trim()
            return artist to title
        }

        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return ""
            val kb = bytes / 1024.0
            if (kb < 1024) return "${kb.toInt()} KB"
            val mb = kb / 1024.0
            if (mb < 1024) return "${mb.toInt()} MB"
            val gb = mb / 1024.0
            return String.format("%.1f GB", gb)
        }
    }

    /**
     * Searches the index for music matching [query]. Empty results mean
     * no matches (or an unparseable answer), never a crash. Throws
     * [IOException] when the index does not answer, so the caller can
     * say so honestly.
     */
    fun search(query: String): List<TorrentResult> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = URL("$SEARCH_URL?q=$encoded&cat=$MUSIC_CATEGORY")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            throw IOException("Song list answered HTTP $code.")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
        if (!body.startsWith("[")) return emptyList()
        val results = mutableListOf<TorrentResult>()
        val array = JSONArray(body)
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val hash = item.optString("info_hash")
            val name = item.optString("name")
            if (hash.isEmpty() || name.isEmpty()) continue
            results += TorrentResult(
                name = name,
                infoHash = hash,
                seeders = item.optString("seeders").toIntOrNull() ?: 0,
                leechers = item.optString("leechers").toIntOrNull() ?: 0,
                sizeBytes = item.optString("size").toLongOrNull() ?: 0L,
                numFiles = item.optString("num_files").toIntOrNull() ?: 0,
                magnet = buildMagnet(hash, name)
            )
        }
        return results.sortedByDescending { it.seeders }
    }

    /**
     * The launch window: a broad music query, only torrents with
     * seeders (a dead torrent cannot play), liveliest first, capped
     * so the window stays a window and not a phone book.
     */
    fun autoLoad(): List<TorrentResult> {
        return search(autoLoadQuery())
            .filter { it.seeders > 0 }
            .take(50)
    }
}
