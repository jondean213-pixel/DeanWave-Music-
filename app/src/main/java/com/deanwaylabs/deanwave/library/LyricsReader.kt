package com.deanwaylabs.deanwave.library

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * One synced lyric line: the text to show from [timeMs] until the next line.
 */
data class SyncedLyricLine(val timeMs: Long, val text: String)

/** Lyrics from a file's own tags — never scraped, never fetched. */
sealed class LyricsResult {
    /** No embedded lyrics in this file. */
    object None : LyricsResult()

    /** Plain lyrics text, no timing. */
    data class Unsynced(val text: String) : LyricsResult()

    /** Timed lyrics, sorted by time. */
    data class Synced(val lines: List<SyncedLyricLine>) : LyricsResult()
}

/**
 * Reads embedded lyrics from audio file tags. Pure Kotlin (java.io only),
 * so it is unit-testable on the bench without Android.
 *
 * Covered: MP3 ID3v2 USLT (unsynced) and SYLT (synced), and FLAC Vorbis
 * comments (LYRICS / UNSYNCEDLYRICS unsynced, SYNCEDLYRICS in LRC form).
 * Not covered in this phase: MP4/m4a lyrics atoms and Ogg Vorbis comment
 * packets — those are a later addition, and files with them simply report
 * [LyricsResult.None] for now.
 *
 * Only the head of the file is read ([maxTagBytes], default 2 MB): ID3
 * tags live at the start of an MP3 and FLAC comment blocks live near the
 * start, so the whole song never needs buffering to find lyrics.
 */
object LyricsReader {

    fun readLyrics(input: InputStream, maxTagBytes: Int = 2 * 1024 * 1024): LyricsResult {
        val head = readHead(input, maxTagBytes)
        if (head.size >= 3 && head[0] == 'I'.code.toByte() &&
            head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()
        ) {
            return readId3(head)
        }
        if (head.size >= 4 && head[0] == 'f'.code.toByte() &&
            head[1] == 'L'.code.toByte() && head[2] == 'a'.code.toByte() &&
            head[3] == 'C'.code.toByte()
        ) {
            return readFlac(head)
        }
        return LyricsResult.None
    }

    private fun readHead(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (total < maxBytes) {
            val want = minOf(buffer.size, maxBytes - total)
            val n = input.read(buffer, 0, want)
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    // ---- ID3v2 ----

    private fun readId3(head: ByteArray): LyricsResult {
        if (head.size < 10) return LyricsResult.None
        val version = head[3].toInt()
        if (version != 3 && version != 4) return LyricsResult.None
        val flags = head[5].toInt()
        val tagSize = syncSafe(head, 6)
        val tagEnd = minOf(10 + tagSize, head.size)
        var tag = head.copyOfRange(10, tagEnd)
        if (flags and 0x80 != 0) {
            // Unsynchronisation: 0xFF 0x00 pairs collapse back to 0xFF.
            tag = deunsync(tag)
        }
        var pos = 0
        if (flags and 0x40 != 0) {
            // Extended header: skip it (rare; best-effort sizing).
            if (tag.size < 4) return LyricsResult.None
            val extSize = if (version == 4) syncSafe(tag, 0) else intBE(tag, 0)
            pos = 4 + extSize
        }
        var unsynced: String? = null
        var synced: List<SyncedLyricLine>? = null
        while (pos + 10 <= tag.size) {
            val id = String(tag, pos, 4, Charsets.ISO_8859_1)
            if (id.all { it == '\u0000' }) break // Padding: no more frames.
            val size = if (version == 4) syncSafe(tag, pos + 4) else intBE(tag, pos + 4)
            val payloadStart = pos + 10
            val payloadEnd = minOf(payloadStart + size, tag.size)
            if (size <= 0 || payloadEnd <= payloadStart) break
            val payload = tag.copyOfRange(payloadStart, payloadEnd)
            when (id) {
                "USLT" -> if (unsynced == null) unsynced = parseUslt(payload)
                "SYLT" -> if (synced == null) synced = parseSylt(payload)
            }
            pos = payloadEnd
        }
        if (!synced.isNullOrEmpty()) return LyricsResult.Synced(synced)
        if (!unsynced.isNullOrEmpty()) return LyricsResult.Unsynced(unsynced)
        return LyricsResult.None
    }

    private fun parseUslt(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val encoding = payload[0].toInt()
        // Language (3 bytes), then a null-terminated content descriptor, then text.
        val (_, textStart) = readNullTerminated(payload, 4, encoding) ?: return null
        if (textStart >= payload.size) return null
        return decodeText(payload, textStart, payload.size, encoding).trim().ifEmpty { null }
    }

    private fun parseSylt(payload: ByteArray): List<SyncedLyricLine>? {
        if (payload.size < 6) return null
        val encoding = payload[0].toInt()
        val timestampFormat = payload[4].toInt()
        val ( _, textStart) = readNullTerminated(payload, 6, encoding) ?: return null
        val lines = mutableListOf<SyncedLyricLine>()
        var pos = textStart
        while (pos < payload.size) {
            val (text, next) = readNullTerminated(payload, pos, encoding) ?: break
            val stampBytes = if (timestampFormat == 1) 2 else 4
            if (next + stampBytes > payload.size) break
            val raw = if (timestampFormat == 1) {
                // MPEG frames: ~26.12 ms per frame at 44.1 kHz (documented approximation).
                val frames = ((payload[next].toInt() and 0xFF) shl 8) or
                    (payload[next + 1].toInt() and 0xFF)
                (frames * 26.12).toLong()
            } else {
                intBE(payload, next, stampBytes).toLong()
            }
            if (text.isNotBlank()) lines += SyncedLyricLine(raw, text)
            pos = next + stampBytes
        }
        return lines.sortedBy { it.timeMs }.ifEmpty { null }
    }

    /** Reads a null-terminated string at [start]; returns (text, index after terminator). */
    private fun readNullTerminated(
        bytes: ByteArray,
        start: Int,
        encoding: Int
    ): Pair<String, Int>? {
        if (start >= bytes.size) return null
        return if (encoding == 1 || encoding == 2) {
            // UTF-16: terminator is two zero bytes, stepped in pairs from start.
            var end = start
            var found = false
            while (end + 1 < bytes.size) {
                if (bytes[end] == 0.toByte() && bytes[end + 1] == 0.toByte()) {
                    found = true
                    break
                }
                end += 2
            }
            if (!found) return null
            Pair(decodeText(bytes, start, end, encoding), end + 2)
        } else {
            var end = start
            while (end < bytes.size && bytes[end] != 0.toByte()) end++
            if (end >= bytes.size) return null
            Pair(decodeText(bytes, start, end, encoding), end + 1)
        }
    }

    private fun decodeText(bytes: ByteArray, start: Int, end: Int, encoding: Int): String {
        if (start >= end) return ""
        val slice = bytes.copyOfRange(start, end)
        return when (encoding) {
            0 -> String(slice, Charsets.ISO_8859_1)
            1 -> {
                // UTF-16 with BOM when present; assume little-endian without one.
                when {
                    slice.size >= 2 && slice[0] == 0xFF.toByte() && slice[1] == 0xFE.toByte() ->
                        String(slice, 2, slice.size - 2, Charsets.UTF_16LE)
                    slice.size >= 2 && slice[0] == 0xFE.toByte() && slice[1] == 0xFF.toByte() ->
                        String(slice, 2, slice.size - 2, Charsets.UTF_16BE)
                    else -> String(slice, Charsets.UTF_16LE)
                }
            }
            2 -> String(slice, Charsets.UTF_16BE)
            else -> String(slice, Charsets.UTF_8)
        }
    }

    private fun deunsync(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            out.write(bytes[i].toInt())
            if (bytes[i] == 0xFF.toByte() && i + 1 < bytes.size && bytes[i + 1] == 0.toByte()) {
                i += 2
            } else {
                i++
            }
        }
        return out.toByteArray()
    }

    // ---- FLAC / Vorbis comments ----

    private fun readFlac(head: ByteArray): LyricsResult {
        var pos = 4
        while (pos + 4 <= head.size) {
            val last = head[pos].toInt() and 0x80 != 0
            val type = head[pos].toInt() and 0x7F
            val length = intBE3(head, pos + 1)
            val blockStart = pos + 4
            val blockEnd = minOf(blockStart + length, head.size)
            if (type == 4) {
                return parseVorbisComment(head.copyOfRange(blockStart, blockEnd))
            }
            if (last) break
            pos = blockEnd
        }
        return LyricsResult.None
    }

    private fun parseVorbisComment(block: ByteArray): LyricsResult {
        if (block.size < 8) return LyricsResult.None
        var pos = 0
        fun intLE(at: Int): Int {
            if (at + 4 > block.size) return -1
            return (block[at].toInt() and 0xFF) or
                ((block[at + 1].toInt() and 0xFF) shl 8) or
                ((block[at + 2].toInt() and 0xFF) shl 16) or
                ((block[at + 3].toInt() and 0xFF) shl 24)
        }
        val vendorLen = intLE(0)
        if (vendorLen < 0) return LyricsResult.None
        pos = 4 + vendorLen
        val count = intLE(pos)
        if (count < 0) return LyricsResult.None
        pos += 4
        var unsynced: String? = null
        var synced: List<SyncedLyricLine>? = null
        repeat(count) {
            val len = intLE(pos)
            if (len < 0 || pos + 4 + len > block.size) return@repeat
            val comment = String(block, pos + 4, len, Charsets.UTF_8)
            pos += 4 + len
            val eq = comment.indexOf('=')
            if (eq <= 0) return@repeat
            val key = comment.substring(0, eq).uppercase()
            val value = comment.substring(eq + 1)
            when (key) {
                "LYRICS", "UNSYNCEDLYRICS" -> if (unsynced == null) unsynced = value
                "SYNCEDLYRICS" -> if (synced == null) synced = parseLrc(value)
            }
        }
        // Copy out of the repeat closure first: the compiler cannot smart-cast
        // locals captured by a changing closure.
        val finalSynced = synced
        val finalUnsynced = unsynced
        if (!finalSynced.isNullOrEmpty()) return LyricsResult.Synced(finalSynced)
        if (!finalUnsynced.isNullOrEmpty()) return LyricsResult.Unsynced(finalUnsynced)
        return LyricsResult.None
    }

    /** Parses LRC lines: [mm:ss.xx]text. One timestamp per line in this phase. */
    private fun parseLrc(text: String): List<SyncedLyricLine>? {
        val lines = mutableListOf<SyncedLyricLine>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (!line.startsWith("[")) continue
            val close = line.indexOf(']')
            if (close < 0) continue
            val stamp = line.substring(1, close)
            val parts = stamp.split(":", ".")
            if (parts.size < 2) continue
            val minutes = parts[0].toLongOrNull() ?: continue
            val seconds = parts[1].toLongOrNull() ?: continue
            val fractionMs = if (parts.size >= 3) {
                val frac = parts[2]
                when (frac.length) {
                    1 -> frac.toLongOrNull()?.times(100)
                    2 -> frac.toLongOrNull()?.times(10)
                    else -> frac.take(3).toLongOrNull()
                } ?: 0L
            } else 0L
            val lyric = line.substring(close + 1).trim()
            if (lyric.isNotEmpty()) {
                lines += SyncedLyricLine(minutes * 60_000 + seconds * 1000 + fractionMs, lyric)
            }
        }
        return lines.sortedBy { it.timeMs }.ifEmpty { null }
    }

    // ---- Byte helpers ----

    private fun syncSafe(bytes: ByteArray, at: Int): Int {
        if (at + 4 > bytes.size) return 0
        return ((bytes[at].toInt() and 0x7F) shl 21) or
            ((bytes[at + 1].toInt() and 0x7F) shl 14) or
            ((bytes[at + 2].toInt() and 0x7F) shl 7) or
            (bytes[at + 3].toInt() and 0x7F)
    }

    private fun intBE(bytes: ByteArray, at: Int, size: Int = 4): Int {
        var value = 0
        for (i in 0 until size) {
            if (at + i >= bytes.size) break
            value = (value shl 8) or (bytes[at + i].toInt() and 0xFF)
        }
        return value
    }

    private fun intBE3(bytes: ByteArray, at: Int): Int {
        if (at + 3 > bytes.size) return 0
        return ((bytes[at].toInt() and 0xFF) shl 16) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            (bytes[at + 2].toInt() and 0xFF)
    }
}
