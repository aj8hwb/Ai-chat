package com.aichathub.app.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

enum class WindowSizeClass {
    COMPACT,
    MEDIUM,
    EXPANDED;

    val isCompact get() = this == COMPACT
    val isMedium get() = this == MEDIUM
    val isExpanded get() = this == EXPANDED
}

@Composable
fun rememberWindowSizeClass(): WindowSizeClass {
    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp.dp

    return remember(widthDp) {
        when {
            widthDp < 600.dp -> WindowSizeClass.COMPACT
            widthDp < 840.dp -> WindowSizeClass.MEDIUM
            else -> WindowSizeClass.EXPANDED
        }
    }
}

val WindowSizeClass.isTablet: Boolean
    get() = this != WindowSizeClass.COMPACT

val WindowSizeClass.isLargeScreen: Boolean
    get() = this == WindowSizeClass.EXPANDED

val WindowSizeClass.showNavigationRail: Boolean
    get() = this == WindowSizeClass.MEDIUM || this == WindowSizeClass.EXPANDED

val WindowSizeClass.showNavigationDrawer: Boolean
    get() = this == WindowSizeClass.EXPANDED

val WindowSizeClass.showBottomBar: Boolean
    get() = this == WindowSizeClass.COMPACT
