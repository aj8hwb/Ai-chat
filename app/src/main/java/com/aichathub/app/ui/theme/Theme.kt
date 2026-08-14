package com.aichathub.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AiColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = TextPrimary,
    secondary = Secondary,
    onSecondary = Color(0xFF0B0B12),
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = TextPrimary,
    tertiary = PrimaryLight,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = SurfaceElevated,
    surfaceContainerHighest = SurfaceHigh,
    outline = BorderSubtle,
    outlineVariant = BorderSubtle,
    error = Error,
    onError = Color(0xFF0B0B12),
    errorContainer = ErrorContainer,
    onErrorContainer = TextPrimary,
    scrim = NearBlack
)

@Composable
fun AiChatHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AiColorScheme,
        typography = AiTypography,
        content = content
    )
}