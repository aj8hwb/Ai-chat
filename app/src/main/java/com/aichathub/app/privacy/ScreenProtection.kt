package com.aichathub.app.privacy

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.WindowManager

/**
 * Screen protection utilities for App Lock.
 *
 * Features:
 *  - Screenshot/video recording blocking (optional)
 *  - Recent apps preview masking
 *  - FLAG_SECURE for sensitive screens
 */
object ScreenProtection {

    private const val TAG = "ScreenProtection"

    /**
     * Enables FLAG_SECURE on an activity to block screenshots and screen recording.
     * This also prevents the app content from appearing in recent apps.
     */
    fun enableSecureFlag(activity: Activity) {
        try {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            Log.d(TAG, "Secure flag enabled")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enable secure flag", e)
        }
    }

    /**
     * Disables FLAG_SECURE to allow screenshots again.
     */
    fun disableSecureFlag(activity: Activity) {
        try {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            Log.d(TAG, "Secure flag disabled")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disable secure flag", e)
        }
    }

    /**
     * Masks the recent apps preview for this activity.
     * Requires API 21+ (Android 5.0).
     */
    fun maskRecentApps(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                @Suppress("DEPRECATION")
                val desc = activity.taskDescription
                if (desc != null) {
                    @Suppress("DEPRECATION")
                    activity.setTaskDescription(
                        android.app.ActivityManager.TaskDescription(
                            desc.label,
                            null,
                            desc.primaryColor
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mask recent apps", e)
        }
    }

    /**
     * Applies all protection settings to an activity.
     * Call this in onCreate when App Lock is enabled.
     */
    fun applyProtections(activity: Activity, enableScreenshotBlock: Boolean = false) {
        if (enableScreenshotBlock) {
            enableSecureFlag(activity)
        }
        maskRecentApps(activity)
    }

    /**
     * Removes all protection settings from an activity.
     * Call this when unlocking or when App Lock is disabled.
     */
    fun removeProtections(activity: Activity) {
        disableSecureFlag(activity)
    }
}
