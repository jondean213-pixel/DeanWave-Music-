package com.deanwaylabs.deanwave.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Brand Colors from DeanWay Design Guidelines
val DeanWayNavy = Color(0xFF0B1F3A)
val DeanWayGold = Color(0xFFC9A227)

@Composable
fun NowPlayingScreen(
    currentTrackTitle: String = "No Track Loaded",
    currentArtist: String = "Unknown Artist",
    onPlayPauseClicked: () -> Unit = {},
    onLocalScanRequested: () -> Unit = {}
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeanWayNavy)
            .padding(24.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(40.dp))

        // Center Brand Identifier Header
        Text(
            text = "DEANWAVE",
            color = DeanWayGold,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp
        )

        Spacer(modifier = Modifier.height(60.dp))

        // Placeholder Area for Gold Equalizer Audio Beams and Centered Artwork
        Box(
            modifier = Modifier
                .size(260.dp)
                .background(Color(0x1AC9A227)),
            contentAlignment = Alignment.Center
        ) {
            // Audio-reactive visualizer and brand icon drift behind this area
            Text(
                text = "Audio Beam Active",
                color = DeanWayGold.copy(alpha = 0.6f),
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(40.dp))

        // Track Information Details
        Text(
            text = currentTrackTitle,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = currentArtist,
            color = DeanWayGold,
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.height(60.dp))

        // Integrated Native Music Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onPlayPauseClicked,
                colors = ButtonDefaults.buttonColors(containerColor = DeanWayGold)
            ) {
                Text(text = "PLAY / PAUSE", color = DeanWayNavy, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(80.dp))

        // Local Storage Utilities Section
        Button(
            onClick = onLocalScanRequested,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "SCAN LOCAL DRIVE & SD CARD", color = DeanWayGold, fontSize = 14.sp)
        }
    }
}
