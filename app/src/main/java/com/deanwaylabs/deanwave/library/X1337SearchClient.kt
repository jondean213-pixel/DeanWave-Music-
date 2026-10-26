package com.deanwaylabs.deanwave.library

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The backup song list, for when the main index does not answer.
 *
 * The main index (apibay) is unreachable from Jon's wifi — connections
 * to it are reset — while this site loads fine there, homepage and
 * search alike (proven on his phone, Oct 25). Its search pages are
 * server-rendered rows: name + detail link, seeders, size, all in the
 * HTML the app can read without running JavaScript.
 *
 * Two differences from the main index, handled honestly:
 * 1. The search list carries no magnet — only a detail-page link.
 *    [resolveMagnet] fetches that page for the magnet when the user
 *    taps PLAY or SAVE. Nothing is fetched eagerly: fifty detail
 *    fetches would stall the window for no reason.
 * 2. This site sits behind a bot check that challenges non-browser
 *    clients. The requests below carry a real browser user agent,
 *    which is what works in Jon's Chrome on the same wifi. If the
 *    check still stops the app, the window says so instead of
 *    pretending — the phone is the proof either way.
 *
 * Blocking; call off the main thread, like the main client.
 */
class X1337SearchClient {
    companion object {
        private const val BASE = "https://1337x.to"

        // A real browser's agent: the bot check weighs this heavily,
        // and it is what Jon's Chrome sends on the same wifi.
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 16; moto g) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        private val TORRENT_LINK = Regex(
            "<a href=\"(/torrent/[0-9]+/[^\\\"]*)\"[^>]*>([^<]{1,200})</a>"
        )
        private val SEEDERS_CELL = Regex("coll-2[^>]*>\\s*([0-9]+)")
        private val LEECHERS_CELL = Regex("coll-3[^>]*>\\s*([0-9]+)")
        private val SIZE_CELL = Regex("coll-5[^>]*>\\s*([^<]+?)\\s*<")
        private val MAGNET_HASH = Regex("magnet:\\?xt=urn:btih:([A-Za-z0-9]{32,40})")
        private val SIZE_NUMBER = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*([KMGT]?B)", RegexOption.IGNORE_CASE)
    }

    /**
     * Music search. Tries the music category first, then the plain
     * search when the category path answers nothing — both are cheap
     * GETs, and a wrong category path must never empty the window.
     * Throws IOException when the site does not answer, so the caller
     * can say so honestly.
     */
    fun search(query: String): List<TorrentResult> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val categoryRows = fetchRows("$BASE/category-search/$encoded/Music/1/")
        if (categoryRows.isNotEmpty()) return categoryRows
        return fetchRows("$BASE/search/$encoded/1/")
    }

    /** The magnet for one search row, from its detail page. Null when unresolvable. */
    fun resolveMagnet(detailUrl: String): String? {
        return try {
            val body = get(detailUrl) ?: return null
            val hash = MAGNET_HASH.find(body)?.groupValues?.get(1) ?: return null
            TorrentSearchClient.buildMagnet(hash, "")
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchRows(url: String): List<TorrentResult> {
        val body = get(url) ?: throw IOException("Song list did not answer.")
        val results = mutableListOf<TorrentResult>()
        for (chunk in body.split("<tr")) {
            if (!chunk.contains("/torrent/")) continue
            val link = TORRENT_LINK.find(chunk) ?: continue
            val detailPath = link.groupValues[1]
            val name = link.groupValues[2].trim()
                .replace("&amp;", "&")
            if (name.isEmpty()) continue
            val seeders = SEEDERS_CELL.find(chunk)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val leechers = LEECHERS_CELL.find(chunk)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val sizeText = SIZE_CELL.find(chunk)?.groupValues?.get(1) ?: ""
            results += TorrentResult(
                name = name,
                infoHash = "",
                seeders = seeders,
                leechers = leechers,
                sizeBytes = parseSize(sizeText),
                numFiles = 0,
                magnet = "",
                detailUrl = BASE + detailPath
            )
        }
        return results.sortedByDescending { it.seeders }
    }

    private fun parseSize(text: String): Long {
        val match = SIZE_NUMBER.find(text.trim()) ?: return 0L
        val number = match.groupValues[1].toDoubleOrNull() ?: return 0L
        val multiplier = when (match.groupValues[2].uppercase()) {
            "KB" -> 1024L
            "MB" -> 1024L * 1024L
            "GB" -> 1024L * 1024L * 1024L
            "TB" -> 1024L * 1024L * 1024L * 1024L
            else -> 1L
        }
        return (number * multiplier).toLong()
    }

    /** One GET with a browser agent. Null on any failure. */
    private fun get(url: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", BROWSER_UA)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * The song window's search, with a backup. The main index answers
 * first when it can; when it throws or answers nothing (Jon's wifi
 * resets it), the backup site answers instead. Throws when neither
 * answers, so the screen can say so honestly; an empty list means
 * both answered and neither had matches.
 */
object SongListSearch {
    fun search(query: String): List<TorrentResult> {
        try {
            val main = TorrentSearchClient().search(query)
            if (main.isNotEmpty()) return main.take(50)
        } catch (_: Exception) {
            // The backup below is the whole point of this object.
        }
        return X1337SearchClient().search(query).take(50)
    }

    fun autoLoad(): List<TorrentResult> {
        try {
            val main = TorrentSearchClient().autoLoad()
            if (main.isNotEmpty()) return main
        } catch (_: Exception) {
        }
        return X1337SearchClient().search(TorrentSearchClient.autoLoadQuery())
            .filter { it.seeders > 0 }
            .take(50)
    }
}
