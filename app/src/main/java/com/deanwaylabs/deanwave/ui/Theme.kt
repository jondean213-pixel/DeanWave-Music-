package com.deanwaylabs.deanwave.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** DeanWay brand colors. */
private val Navy = Color(0xFF0B1F3A)
private val NavyLight = Color(0xFF16305A) // derived surface shade
private val Gold = Color(0xFFC9A227)
private val White = Color(0xFFFFFFFF)

private val DeanWaveColors = darkColorScheme(
    primary = Gold,
    onPrimary = Navy,
    background = Navy,
    onBackground = White,
    surface = NavyLight,
    onSurface = White,
    secondary = Gold,
    onSecondary = Navy
)

@Composable
fun DeanWaveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DeanWaveColors,
        content = content
    )
}
