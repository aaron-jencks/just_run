package com.example.justrun.wear.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WearColorScheme = darkColorScheme(
    primary = WearSlateBlue,
    onPrimary = WearCloud,
    primaryContainer = WearOceanSurface,
    onPrimaryContainer = WearCloud,
    secondary = WearForestMint,
    onSecondary = WearDeepNavy,
    secondaryContainer = Color(0xFF1B3A33),
    onSecondaryContainer = WearCloud,
    tertiary = WearCoral,
    background = WearDeepNavy,
    onBackground = WearCloud,
    surface = Color(0xFF122033),
    onSurface = WearCloud,
    surfaceVariant = Color(0xFF21364B),
    onSurfaceVariant = Color(0xFFB4C2D1),
    outline = WearSteel
)

@Composable
fun JustRunWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WearColorScheme,
        content = content
    )
}
