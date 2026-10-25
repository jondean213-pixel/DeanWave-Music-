package com.deanwaylabs.deanwave.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deanwaylabs.deanwave.library.ArtistBio

/**
 * Band biography from MusicBrainz (public facts only). When MusicBrainz
 * has nothing — unknown artist, no network, no match — this says so
 * honestly instead of inventing a story. A missing formed year stays
 * missing: no year is shown at all rather than a guessed one.
 */
@Composable
fun ArtistBioSection(
    artistName: String,
    bio: ArtistBio?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (bio == null) {
            Text(
                text = "Bio unavailable for $artistName.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            Text(
                text = bio.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            val details = buildList {
                bio.type?.let { add(it) }
                bio.formedYear?.let { add("Formed $it") }
                bio.area?.let { add(it) }
                bio.disambiguation?.let { add(it) }
            }
            if (details.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (bio.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = bio.tags.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}
