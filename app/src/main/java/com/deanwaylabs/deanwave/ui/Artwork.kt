package com.deanwaylabs.deanwave.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.deanwaylabs.deanwave.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Embedded album art, first choice. Reads the picture stored inside the
 * audio file itself (MediaMetadataRetriever) — no network, no provider,
 * works for local files whether they came from MediaStore or a folder scan.
 * Returns null when the file has no embedded art, so the caller falls back
 * to the placeholder. The picture is downscaled to ~512 px: the screen
 * shows it at 280 dp, and decoding a full-size scan would waste memory.
 */
fun loadEmbeddedArtwork(context: Context, uriString: String): Bitmap? {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, Uri.parse(uriString))
        val bytes = retriever.embeddedPicture ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > 512 || bounds.outHeight / sampleSize > 512) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (_: Exception) {
        return null
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
            // Release is best-effort; a failed release must not crash playback UI.
        }
    }
}

/**
 * Album art, centered, in a gold frame — the layout Jon confirmed.
 *
 * Embedded art when the current track has it; otherwise the gold-framed
 * DeanWave mark placeholder from the theme concept. Art decodes on a
 * background thread (no new dependencies: plain Thread + main handler),
 * and a disposed composition never receives the result.
 */
@Composable
fun CenteredAlbumArt(
    uriString: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var artwork by remember(uriString) { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(uriString) {
        val disposed = AtomicBoolean(false)
        if (uriString != null) {
            val handler = Handler(Looper.getMainLooper())
            val thread = Thread {
                val art = loadEmbeddedArtwork(context, uriString)
                if (!disposed.get()) handler.post { artwork = art }
            }
            thread.start()
            onDispose {
                disposed.set(true)
                thread.interrupt()
            }
        } else {
            artwork = null
            onDispose { }
        }
    }

    Box(
        modifier = modifier
            .size(280.dp)
            .border(2.dp, MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        val art = artwork
        if (art != null) {
            Image(
                bitmap = art.asImageBitmap(),
                contentDescription = "Album art",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(280.dp)
            )
        } else {
            Image(
                painter = painterResource(R.drawable.deanwave_mark),
                contentDescription = "DeanWave mark",
                modifier = Modifier.size(120.dp)
            )
        }
    }
}
