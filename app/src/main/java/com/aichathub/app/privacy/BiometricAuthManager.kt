package com.aichathub.app.privacy

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Biometric/PIN authentication gate for App Lock.
 *
 * Lifecycle:
 *  App Open → [authenticate] → Biometric/PIN → OnSuccess → Main UI
 *  Background → Foreground → [authenticate] → re-lock
 *
 * Falls back to device credential (PIN/pattern/password) when biometric
 * is not available. This uses AndroidX Biometric for backward compatibility.
 */
object BiometricAuthManager {

    private const val TAG = "BiometricAuthManager"

    /** How long to keep the "authenticated" flag alive before re-locking (ms). */
    private const val LOCK_TIMEOUT_MS = 30_000L // 30 seconds

    @Volatile
    private var authenticatedAt: Long = 0L

    @Volatile
    private var lockTimeoutMs: Long = LOCK_TIMEOUT_MS

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Sets the re-lock timeout in milliseconds.
     */
    fun setLockTimeout(timeoutMs: Long) {
        lockTimeoutMs = timeoutMs
        Log.d(TAG, "Lock timeout set to ${timeoutMs}ms")
    }

    /**
     * Returns true if the user is currently authenticated (within the timeout window).
     */
    fun isAuthenticated(): Boolean {
        val elapsed = System.currentTimeMillis() - authenticatedAt
        return authenticatedAt > 0 && elapsed < lockTimeoutMs
    }

    /**
     * Clears the authentication state (e.g., on app lock toggle off).
     */
    fun clearAuthentication() {
        authenticatedAt = 0L
    }

    /**
     * Shows the biometric/PIN authentication prompt.
     *
     * @param activity the activity to show the prompt from
     * @param onAuthSuccess called when authentication succeeds
     * @param onAuthFailure called when authentication fails or is cancelled
     */
    fun authenticate(
        activity: FragmentActivity,
        onAuthSuccess: () -> Unit,
        onAuthFailure: () -> Unit = {}
    ) {
        val biometricManager = BiometricManager.from(activity)
        val canAuth = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            Log.w(TAG, "Biometric not available: $canAuth")
            onAuthFailure()
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                authenticatedAt = System.currentTimeMillis()
                Log.d(TAG, "Authentication succeeded")
                onAuthSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Log.w(TAG, "Authentication error: $errorCode ($errString)")
                onAuthFailure()
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Log.w(TAG, "Authentication failed (bad biometric/PIN)")
                // Don't call onAuthFailure here — the prompt stays open for retry
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock AIChatHub")
            .setSubtitle("Verify your identity to continue")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        try {
            prompt.authenticate(promptInfo)
            Log.d(TAG, "Biometric prompt shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show biometric prompt", e)
            onAuthFailure()
        }
    }

    /**
     * Checks if authentication is needed based on the app lifecycle.
     * Call this from onResume to re-lock after backgrounding.
     *
     * @return true if the user needs to re-authenticate
     */
    fun needsReAuthentication(): Boolean {
        if (!isAuthenticated()) return true
        val elapsed = System.currentTimeMillis() - authenticatedAt
        return elapsed >= lockTimeoutMs
    }
}
