package com.deanwaylabs.deanwave.library

import android.content.Context
import android.os.Environment
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Torrent streaming for DeanWave-Music-: the one addition to last night's
 * DeanWave. A magnet link joins the swarm, the file downloads through
 * libtorrent, and a localhost proxy ([TorrentProxyServer]) serves the
 * growing file to ExoPlayer with Range support, so playback starts
 * before the download finishes.
 *
 * Flow: fetchMagnet (blocks, so the caller must be off the main thread)
 * → bdecode → download → find the handle → deadlines on the head pieces
 * → resolve the playable file → start the proxy → hand ExoPlayer the
 * REAL proxy URL. The old placeholder URL (http://127.0.0.1/<hash>,
 * nothing listening) is gone.
 *
 * The playable file is resolved from the filesystem, not from libtorrent
 * file-table calls: a single-file torrent's file is downloadDir/<name>;
 * a multi-file torrent's audio is the largest audio file under
 * downloadDir/<name>. Files appear when the torrent is added, so a short
 * poll covers slow creation.
 */
class TorrentStreamer(private val sessionManager: SessionManager, private val downloadDir: File) {
    private var currentHandle: TorrentHandle? = null
    private var proxyServer: TorrentProxyServer? = null

    /**
     * Guards the stream callbacks: every PLAY tap is its own thread,
     * and an older tap must never overwrite a newer tap's screen.
     * Only the latest tap's callbacks fire.
     */
    private val streamGeneration = AtomicInteger(0)

    /**
     * Metadata fetch with retries: one 60s attempt is too little on
     * slow or filtered wifi (field-proven: several rows in a row
     * failed to fetch at all on library wifi that had worked hours
     * earlier). Up to 3 attempts; [onRetry] fires before attempts 2
     * and 3 so the screen never looks frozen.
     */
    private fun fetchMetadata(
        magnetUrl: String,
        onRetry: (() -> Unit)? = null
    ): ByteArray? {
        repeat(3) { attempt ->
            sessionManager.fetchMagnet(magnetUrl, 60, downloadDir)?.let { return it }
            if (attempt < 2) onRetry?.invoke()
        }
        return null
    }

    fun startAudioStream(
        magnetUrl: String,
        onStreamReady: (proxyUrl: String) -> Unit,
        onError: (message: String) -> Unit = {},
        onRetry: () -> Unit = {}
    ) {
        // This tap's generation: a newer PLAY tap retires this one,
        // so a slow older tap can never overwrite the newer tap's
        // screen with a stale error or a stale stream.
        val myGeneration = streamGeneration.incrementAndGet()
        val safeReady: (String) -> Unit = { url ->
            if (streamGeneration.get() == myGeneration) onStreamReady(url)
        }
        val safeError: (String) -> Unit = { message ->
            if (streamGeneration.get() == myGeneration) onError(message)
        }
        val safeRetry: () -> Unit = {
            if (streamGeneration.get() == myGeneration) onRetry()
        }
        try {
            // 1. Fetch the torrent metadata for the magnet link, then decode it.
            // fetchMagnet blocks until metadata arrives (or times out), so call
            // this off the main thread.
            val metadata = fetchMetadata(magnetUrl, safeRetry)
                ?: run {
                    safeError("Couldn't fetch that magnet link after 3 tries — check the link and try again.")
                    return
                }
            val torrentInfo = TorrentInfo.bdecode(metadata)

            // 2. Add the torrent to the session. download() returns void, so the
            // handle is looked up afterwards by info hash.
            sessionManager.download(torrentInfo, downloadDir)
            val handle = sessionManager.find(torrentInfo.infoHashV1())
                ?: run {
                    safeError("Torrent added but no handle came back — try again.")
                    return
                }
            currentHandle = handle

            // 3. Force the head of the file to download first: deadlines on the
            // first pieces pull them ahead of the rest (libtorrent's streaming
            // mechanism — piece deadlines, not a sequential flag, which this
            // libtorrent version no longer offers as a handle method).
            prioritizeStartingPieces()

            // 4. Resolve the playable file and start the proxy on it.
            val torrentFile = handle.torrentFile()
                ?: run {
                    safeError("Torrent has no file info yet — try again.")
                    return
                }
            val streamFile = resolveStreamFile(torrentFile.name())
                ?: run {
                    safeError("No playable audio file found in that torrent yet.")
                    return
                }
            val totalSize = torrentFile.totalSize()
            if (totalSize <= 0) {
                safeError("Torrent reports no size — try again.")
                return
            }
            val pieceLength = torrentFile.pieceLength().toLong()
            val numPieces = torrentFile.numPieces()
            val isAvailable: (Long, Long) -> Boolean = { start, end ->
                if (pieceLength <= 0 || numPieces <= 0) {
                    false
                } else {
                    val firstPiece = (start / pieceLength).toInt()
                    val lastPiece = minOf((end / pieceLength).toInt(), numPieces - 1)
                    if (firstPiece > lastPiece) {
                        false
                    } else {
                        (firstPiece..lastPiece).all { handle.havePiece(it) }
                    }
                }
            }
            val proxy = TorrentProxyServer(streamFile, totalSize, isAvailable)
            proxy.start()
            proxyServer = proxy

            // 5. Return the REAL localhost proxy URL that ExoPlayer can play from.
            safeReady(proxy.url)
        } catch (e: Exception) {
            safeError("Stream failed: ${e.message}")
        }
    }

    private fun prioritizeStartingPieces() {
        val handle = currentHandle ?: return
        val totalPieces = handle.torrentFile()?.numPieces() ?: return

        // Push deadlines on the initial tracks to skip initial buffering lag
        for (i in 0 until minOf(10, totalPieces)) {
            handle.setPieceDeadline(i, 1000)
        }
    }

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "aiff", "aif"
    )

    private fun resolveStreamFile(torrentName: String): File? {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val base = File(downloadDir, torrentName)
            if (base.isFile) return base
            if (base.isDirectory) {
                val best = findLargestAudio(base, depth = 0)
                if (best != null) return best
            }
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                return null
            }
        }
        return null
    }

    private fun findLargestAudio(dir: File, depth: Int): File? {
        if (depth > 4) return null
        val children = try {
            dir.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return null
        var best: File? = null
        for (child in children) {
            try {
                when {
                    child.isFile && child.extension.lowercase() in AUDIO_EXTENSIONS -> {
                        if (best == null || child.length() > best.length()) best = child
                    }
                    child.isDirectory && !child.name.startsWith(".") -> {
                        val nested = findLargestAudio(child, depth + 1)
                        if (nested != null && (best == null || nested.length() > best.length())) {
                            best = nested
                        }
                    }
                }
            } catch (_: SecurityException) {
                // Unreadable entry: skip it.
            }
        }
        return best
    }

    fun stopAudioStream() {
        try {
            proxyServer?.stop()
        } catch (_: Exception) {
        }
        proxyServer = null
        currentHandle?.let {
            sessionManager.remove(it)
            currentHandle = null
        }
    }

    /**
     * Downloads a torrent whole into the phone's Music folder — the
     * "Download" half of tap-to-listen-or-download. Same session as
     * streaming, but a separate handle: streaming keeps its own proxy
     * and handle untouched.
     *
     * Flow: fetchMagnet → download → poll the handle's status every
     * 2s, reporting done/total, until isFinished (or an error) →
     * copy every audio file under the torrent into
     * Music/<torrent-name>/. The copy is what the library scan finds:
     * SCAN LIBRARY picks it up even before the system's media index
     * notices it.
     *
     * Blocking; call off the main thread. A download in flight dies
     * with the app (the session stops in onDestroy), so the status
     * line says to keep the app open until it finishes.
     */
    fun downloadToMusic(
        magnetUrl: String,
        musicDir: File,
        onProgress: (doneBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onDone: (savedFiles: List<File>) -> Unit,
        onError: (message: String) -> Unit = {}
    ) {
        try {
            val (audioFiles, base) = fetchAudioFiles(magnetUrl, onProgress, onError) ?: return
            val torrentName = base.name
            val destDir = File(musicDir, HttpSongDownloader.sanitizeFileName(torrentName))
            destDir.mkdirs()
            val saved = mutableListOf<File>()
            for (source in audioFiles) {
                try {
                    val dest = File(destDir, source.name)
                    source.copyTo(dest, overwrite = true)
                    saved += dest
                } catch (_: Exception) {
                    // One failed copy never blocks the rest.
                }
            }
            if (saved.isEmpty()) {
                onError("Download finished but nothing could be saved to your Music folder.")
                return
            }
            onDone(saved)
        } catch (e: Exception) {
            onError("Download failed: ${e.message}")
        }
    }

    /**
     * Downloads a magnet link whole into the download dir and hands the
     * caller the audio files plus the torrent's base path, for landing
     * elsewhere (the music Drive). The caller owns the files after
     * [onDone] — uploading them, then deleting the base path to free
     * the phone. Blocking; call off the main thread. A download in
     * flight dies with the app (the session stops in onDestroy), so the
     * status line says to keep the app open until it finishes.
     */
    fun downloadToCache(
        magnetUrl: String,
        onProgress: (doneBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onDone: (audioFiles: List<File>, torrentBase: File) -> Unit,
        onError: (message: String) -> Unit = {}
    ) {
        try {
            val (audioFiles, base) = fetchAudioFiles(magnetUrl, onProgress, onError) ?: return
            onDone(audioFiles, base)
        } catch (e: Exception) {
            onError("Download failed: ${e.message}")
        }
    }

    /**
     * Fetches a magnet link, downloads it whole, and collects the audio
     * files it landed in the download dir. Returns the audio files and
     * the torrent's base path (a file when the torrent is one song, a
     * directory when it is an album), or null after reporting the
     * failure through [onError]. The torrent handle is removed before
     * returning; the files stay on disk for the caller to land.
     */
    private fun fetchAudioFiles(
        magnetUrl: String,
        onProgress: (doneBytes: Long, totalBytes: Long) -> Unit,
        onError: (message: String) -> Unit
    ): Pair<List<File>, File>? {
        val metadata = fetchMetadata(magnetUrl)
            ?: run {
                onError("Couldn't fetch that magnet link after 3 tries — check the link and try again.")
                return null
            }
        val torrentInfo = TorrentInfo.bdecode(metadata)
        sessionManager.download(torrentInfo, downloadDir)
        val handle = sessionManager.find(torrentInfo.infoHashV1())
            ?: run {
                onError("Torrent added but no handle came back — try again.")
                return null
            }
        val totalSize = torrentInfo.totalSize()
        while (true) {
            val status = try {
                handle.status()
            } catch (_: Exception) {
                onError("Download lost its torrent — try again.")
                return null
            }
            onProgress(status.totalDone(), totalSize)
            if (status.isFinished()) break
            val error = try {
                status.errorCode()
            } catch (_: Exception) {
                null
            }
            if (error != null && error.value() != 0) {
                onError("Download failed: ${error.message()}")
                return null
            }
            try {
                Thread.sleep(2000)
            } catch (_: InterruptedException) {
                return null
            }
        }
        val torrentName = torrentInfo.name()
        val base = File(downloadDir, torrentName)
        val audioFiles = mutableListOf<File>()
        if (base.isFile && base.extension.lowercase() in AUDIO_EXTENSIONS) {
            audioFiles += base
        } else if (base.isDirectory) {
            collectAudio(base, 0, audioFiles)
        }
        if (audioFiles.isEmpty()) {
            onError("Download finished but no audio files were found in it.")
            return null
        }
        try {
            sessionManager.remove(handle)
        } catch (_: Exception) {
        }
        return audioFiles to base
    }

    private fun collectAudio(dir: File, depth: Int, out: MutableList<File>) {
        if (depth > 4) return
        val children = try {
            dir.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return
        for (child in children) {
            try {
                when {
                    child.isFile && child.extension.lowercase() in AUDIO_EXTENSIONS -> out += child
                    child.isDirectory && !child.name.startsWith(".") ->
                        collectAudio(child, depth + 1, out)
                }
            } catch (_: SecurityException) {
                // Unreadable entry: skip it.
            }
        }
    }
}

/**
 * The first writable Music folder: the shared Music folder first, then
 * a Music folder on any removable volume — the same roots the library
 * scan walks, so a download saved here is a download the scan finds.
 * Null when no writable Music folder exists.
 */
fun resolveMusicDir(context: Context): File? {
    val candidates = try {
        LocalLibraryScanner.defaultConfig(context).scanRoots
    } catch (_: Exception) {
        emptyList()
    }.toMutableList()
    try {
        @Suppress("DEPRECATION")
        val sharedMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        if (sharedMusic != null) candidates += sharedMusic
    } catch (_: Exception) {
    }
    return candidates.firstOrNull { it.isDirectory && it.canWrite() }
}
