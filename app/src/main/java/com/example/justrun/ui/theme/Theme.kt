package com.example.justrun.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = SlateBlue,
    onPrimary = Cloud,
    primaryContainer = OceanSurface,
    onPrimaryContainer = Cloud,
    secondary = ForestMint,
    onSecondary = DeepNavy,
    secondaryContainer = Color(0xFF1B3A33),
    onSecondaryContainer = Cloud,
    tertiary = Coral,
    background = DeepNavy,
    onBackground = Cloud,
    surface = Color(0xFF122033),
    onSurface = Cloud,
    surfaceVariant = Color(0xFF21364B),
    onSurfaceVariant = Color(0xFFB4C2D1),
    outline = Steel
)

private val LightColorScheme = lightColorScheme(
    primary = SlateBlue,
    onPrimary = Cloud,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = DeepNavy,
    secondary = Color(0xFF2BB673),
    onSecondary = Cloud,
    secondaryContainer = Color(0xFFDDF8EA),
    onSecondaryContainer = DeepNavy,
    tertiary = Coral,
    background = Sand,
    onBackground = DeepNavy,
    surface = Cloud,
    onSurface = DeepNavy,
    surfaceVariant = Color(0xFFE4EBF2),
    onSurfaceVariant = Steel,
    outline = Color(0xFF95A5B7)
)

@Composable
fun JustRunTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
