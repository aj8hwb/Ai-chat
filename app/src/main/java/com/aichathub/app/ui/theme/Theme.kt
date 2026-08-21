package com.aichathub.app.ui.theme

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

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6D28D9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDE9FE),
    onPrimaryContainer = Color(0xFF2E1065),
    secondary = Color(0xFF0E7490),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFFAFE),
    onSecondaryContainer = Color(0xFF164E63),
    tertiary = Color(0xFF7C3AED),
    background = Color(0xFFF6F6FA),
    onBackground = Color(0xFF1A1A24),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A24),
    surfaceVariant = Color(0xFFECECF3),
    onSurfaceVariant = Color(0xFF4B4B5C),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFECECF3),
    surfaceContainerHighest = Color(0xFFE2E2EC),
    outline = Color(0xFFD5D5E0),
    outlineVariant = Color(0xFFD5D5E0),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    scrim = Color(0xFF000000)
)

/**
 * Resolves the effective dark/light choice. [themeMode] is one of
 * "system" | "dark" | "light". Returns true when the app should render the
 * dark palette.
 */
fun resolveDarkTheme(themeMode: String, systemDark: Boolean): Boolean = when (themeMode) {
    "light" -> false
    "dark" -> true
    else -> systemDark
}

@Composable
fun AiChatHubTheme(
    themeMode: String = "dark",
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val dark = resolveDarkTheme(themeMode, isSystemInDarkTheme())

    // Material You dynamic color: on Android 12+ the system palette drives the
    // theme (user wallpaper colors) instead of the fixed brand palette.
    val scheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) {
        DarkColorScheme
    } else {
        LightColorScheme
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = AiTypography,
        content = content
    )
}