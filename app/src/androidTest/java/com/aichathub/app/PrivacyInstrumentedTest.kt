package com.aichathub.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aichathub.app.privacy.BiometricAuthManager
import com.aichathub.app.privacy.CrashLogRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for privacy-related components.
 */
@RunWith(AndroidJUnit4::class)
class PrivacyInstrumentedTest {

    @Test
    fun biometricAuthManager_initialState_isNotAuthenticated() {
        BiometricAuthManager.clearAuthentication()
        assertFalse("Should not be authenticated initially", BiometricAuthManager.isAuthenticated())
    }

    @Test
    fun biometricAuthManager_needsReAuth_afterClear() {
        BiometricAuthManager.clearAuthentication()
        assertTrue("Should need re-auth after clear", BiometricAuthManager.needsReAuthentication())
    }

    @Test
    fun biometricAuthManager_lockTimeout_canBeSet() {
        BiometricAuthManager.setLockTimeout(60_000L)
        // Just verifying it doesn't throw
        BiometricAuthManager.setLockTimeout(30_000L)
    }
}
