package com.deanwaylabs.deanwave.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.deanwaylabs.deanwave.library.DownloadController
import com.deanwaylabs.deanwave.library.DownloadStage
import com.deanwaylabs.deanwave.library.DriveConfig
import com.deanwaylabs.deanwave.library.UnconfiguredDriveCredentials

/**
 * Download to Drive, in the now-playing screen for now (no navigation
 * graph exists yet at this stage of the app).
 *
 * One tap: the song is fetched, uploaded to the music Drive, and the
 * temporary phone copy is cleaned up — or kept on the SD card when the
 * switch is on. Progress shows honestly in bytes; a missing total
 * (servers that do not send a length) shows bytes alone, never a fake
 * percentage.
 *
 * Honest gap, stated in the UI rather than hidden: the Drive account is
 * not connected yet (Jon's music-Drive account is still awaited), so
 * until the account wiring lands, tapping download reports "not
 * connected" instead of pretending to upload. The plumbing underneath
 * is complete and waiting on that one connection.
 */
@Composable
fun DownloadSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var keepLocal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("") }

    // The account wiring replaces UnconfiguredDriveCredentials when Jon
    // provides the music-Drive account; everything else stays the same.
    val controller = remember {
        DownloadController(
            context,
            UnconfiguredDriveCredentials,
            DriveConfig(musicFolderId = null, accountLabel = "Music Drive"),
            Handler(Looper.getMainLooper())
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Download to Drive",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Song link") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = fileName,
            onValueChange = { fileName = it },
            label = { Text("File name (optional)") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Also keep a copy on the SD card",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = keepLocal,
                onCheckedChange = { keepLocal = it },
                enabled = !busy
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            enabled = !busy && url.isNotBlank(),
            onClick = {
                busy = true
                status = ""
                progress = ""
                controller.startDownload(
                    url.trim(), fileName.trim(), keepLocal,
                    object : DownloadController.Listener {
                        override fun onProgress(
                            stage: DownloadStage,
                            doneBytes: Long,
                            totalBytes: Long
                        ) {
                            val verb = if (stage == DownloadStage.DOWNLOADING) {
                                "Downloading"
                            } else {
                                "Uploading to Drive"
                            }
                            progress = if (totalBytes > 0) {
                                "$verb: $doneBytes of $totalBytes bytes"
                            } else {
                                "$verb: $doneBytes bytes"
                            }
                        }

                        override fun onDone(driveFileId: String, keptLocalPath: String?) {
                            busy = false
                            progress = ""
                            status = buildString {
                                append("On your Drive (id $driveFileId).")
                                if (keptLocalPath != null) append(" Kept on SD: $keptLocalPath.")
                            }
                        }

                        override fun onFailed(reason: String) {
                            busy = false
                            progress = ""
                            status = reason
                        }
                    }
                )
            }
        ) {
            Text(if (busy) "Working…" else "Download to Drive")
        }
        if (progress.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = progress,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (status.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Drive account connection is pending (Jon's music-Drive " +
                "account) — until it lands, downloads report that gap " +
                "instead of uploading.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
}
