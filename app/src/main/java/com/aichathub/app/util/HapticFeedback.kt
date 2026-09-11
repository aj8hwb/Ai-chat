package com.aichathub.app.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Haptic feedback utility for providing tactile feedback to users.
 */
object HapticFeedback {
    private const val TAG = "HapticFeedback"

    /**
     * Types of haptic feedback
     */
    enum class FeedbackType {
        LIGHT,
        MEDIUM,
        HEAVY,
        SUCCESS,
        WARNING,
        ERROR
    }

    /**
     * Provide haptic feedback
     */
    fun provide(context: Context, type: FeedbackType) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val effect = when (type) {
            FeedbackType.LIGHT -> VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)
            FeedbackType.MEDIUM -> VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
            FeedbackType.HEAVY -> VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
            FeedbackType.SUCCESS -> VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50), -1)
            FeedbackType.WARNING -> VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), -1)
            FeedbackType.ERROR -> VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100, 50, 100), -1)
        }

        vibrator.vibrate(effect)
    }

    /**
     * Provide light haptic feedback (button press, toggle)
     */
    fun light(context: Context) = provide(context, FeedbackType.LIGHT)

    /**
     * Provide medium haptic feedback (selection, navigation)
     */
    fun medium(context: Context) = provide(context, FeedbackType.MEDIUM)

    /**
     * Provide heavy haptic feedback (important action)
     */
    fun heavy(context: Context) = provide(context, FeedbackType.HEAVY)

    /**
     * Provide success haptic feedback (operation completed)
     */
    fun success(context: Context) = provide(context, FeedbackType.SUCCESS)

    /**
     * Provide warning haptic feedback (caution needed)
     */
    fun warning(context: Context) = provide(context, FeedbackType.WARNING)

    /**
     * Provide error haptic feedback (error occurred)
     */
    fun error(context: Context) = provide(context, FeedbackType.ERROR)
}

/**
 * Composable function to remember haptic feedback handler
 */
@Composable
fun rememberHapticFeedback(): HapticFeedbackHandler {
    val context = LocalContext.current
    return remember {
        HapticFeedbackHandler(context)
    }
}

/**
 * Haptic feedback handler for Compose
 */
class HapticFeedbackHandler(private val context: Context) {
    fun light() = HapticFeedback.light(context)
    fun medium() = HapticFeedback.medium(context)
    fun heavy() = HapticFeedback.heavy(context)
    fun success() = HapticFeedback.success(context)
    fun warning() = HapticFeedback.warning(context)
    fun error() = HapticFeedback.error(context)
}
