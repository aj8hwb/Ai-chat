package com.aichathub.app.util

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry

/**
 * Navigation animation utility for smooth screen transitions.
 */
object NavigationAnimations {
    private const val ANIMATION_DURATION = 300

    /**
     * Default enter transition for screens
     */
    fun enterTransition(): EnterTransition {
        return slideInHorizontally(
            initialOffsetX = { fullWidth -> fullWidth },
            animationSpec = tween(durationMillis = ANIMATION_DURATION)
        ) + fadeIn(
            animationSpec = tween(durationMillis = ANIMATION_DURATION)
        )
    }

    /**
     * Default exit transition for screens
     */
    fun exitTransition(): ExitTransition {
        return slideOutHorizontally(
            targetOffsetX = { fullWidth -> -fullWidth / 3 },
            animationSpec = tween(durationMillis = ANIMATION_DURATION)
        ) + fadeOut(
            animationSpec = tween(durationMillis = ANIMATION_DURATION)
        )
    }

    /**
     * Pop enter transition when coming back
     */
    fun popEnterTransition(): EnterTransition {
        return slideInHorizontally(
            initialOffsetX = { fullWidth -> -fullWidth / 3 },
            animationSpec = tween(durationMillis = ANIMATION_DURATION)
        ) + fadeIn(
            animationSpec = tween(durationMillis = ANIMATION_DURATION)
        )
    }

    /**
     * Pop exit transition when going back
     */
    fun popExitTransition(): ExitTransition {
        return slideOutHorizontally(
            targetOffsetX = { fullWidth -> fullWidth },
            animationSpec = tween(durationMillis = ANIMATION_DURATION)
        ) + fadeOut(
            animationSpec = tween(durationMillis = ANIMATION_DURATION)
        )
    }

    /**
     * Shared element transition for model details
     */
    fun sharedElementEnterTransition(): EnterTransition {
        return fadeIn(
            animationSpec = tween(durationMillis = ANIMATION_DURATION)
        )
    }

    /**
     * Shared element exit transition
     */
    fun sharedElementExitTransition(): ExitTransition {
        return fadeOut(
            animationSpec = tween(durationMillis = ANIMATION_DURATION)
        )
    }
}
