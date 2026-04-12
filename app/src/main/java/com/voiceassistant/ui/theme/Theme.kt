package com.voiceassistant.ui.theme

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

// Paleta de cores educacional — tons de azul-índigo e verde
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1A6ECC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001B46),
    secondary = Color(0xFF286C4B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFABF0CA),
    onSecondaryContainer = Color(0xFF002114),
    tertiary = Color(0xFF6B58AB),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE9DEFF),
    onTertiaryContainer = Color(0xFF230F60),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    background = Color(0xFFF8F9FF),
    surface = Color(0xFFF8F9FF),
    surfaceVariant = Color(0xFFE1E2EC),
    outline = Color(0xFF74758C)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFAAC8FF),
    onPrimary = Color(0xFF003472),
    primaryContainer = Color(0xFF004E9E),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF8FD3AF),
    onSecondary = Color(0xFF003824),
    secondaryContainer = Color(0xFF005235),
    onSecondaryContainer = Color(0xFFABF0CA),
    tertiary = Color(0xFFCEBDFF),
    onTertiary = Color(0xFF392778),
    tertiaryContainer = Color(0xFF51408F),
    onTertiaryContainer = Color(0xFFE9DEFF),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    background = Color(0xFF1A1B21),
    surface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF8F909A)
)

@Composable
fun VoiceAssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color disponível no Android 12+
    dynamicColor: Boolean = true,
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
