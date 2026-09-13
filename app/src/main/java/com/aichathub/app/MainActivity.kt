package com.aichathub.app

import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aichathub.app.data.SettingsRepository
import com.aichathub.app.privacy.BiometricAuthManager
import com.aichathub.app.ui.AiChatHubApp
import com.aichathub.app.ui.theme.AiChatHubTheme
import com.aichathub.app.ui.theme.Primary
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    /** Whether the user is currently authenticated (unlocked). */
    private var isUnlocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // llama-android ships arm64-v8a native libraries only. On other ABIs the
        // native lib fails to load with UnsatisfiedLinkError; show a friendly
        // message instead of crashing.
        val supported = Build.SUPPORTED_ABIS?.any { it == "arm64-v8a" } ?: false
        if (!supported) {
            enableEdgeToEdge()
            setContent {
                AiChatHubTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        UnsupportedAbiScreen()
                    }
                }
            }
            return
        }
        enableEdgeToEdge()
        setContent {
            val settings by settingsRepository.settings.collectAsState(initial = null)
            val appLockEnabled = settings?.appLockEnabled ?: false
            val themeMode = settings?.themeMode ?: "dark"
            val dynamicColor = settings?.dynamicColor ?: false

            AiChatHubTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (appLockEnabled && !isUnlocked) {
                        // Show lock screen — prompt biometric on display
                        LockScreen(
                            onUnlockRequested = { showBiometricPrompt() }
                        )
                    } else {
                        AiChatHubApp()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-lock if the user backgrounded the app and the timeout expired
        val settings = settingsRepository.cachedSettingsSnapshot()
        if (settings?.appLockEnabled == true && BiometricAuthManager.needsReAuthentication()) {
            isUnlocked = false
        }
    }

    override fun onPause() {
        super.onPause()
        // When going to background, start the re-lock timer
    }

    private fun showBiometricPrompt() {
        BiometricAuthManager.authenticate(
            activity = this,
            onAuthSuccess = {
                isUnlocked = true
            },
            onAuthFailure = {
                // Stay locked — user can try again
            }
        )
    }
}

@Composable
private fun LockScreen(onUnlockRequested: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "AIChatHub",
            style = MaterialTheme.typography.headlineLarge,
            color = Primary
        )
        Text(
            "App is locked",
            style = MaterialTheme.typography.headlineSmall,
            color = TextSecondary,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            "Tap below to unlock with biometric or PIN",
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        androidx.compose.material3.Button(
            onClick = onUnlockRequested,
            modifier = Modifier.padding(top = 32.dp)
        ) {
            Text("Unlock")
        }
    }
}

@Composable
private fun UnsupportedAbiScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Not Supported",
            style = MaterialTheme.typography.headlineMedium,
            color = Primary
        )
        Text(
            "AI Chat Hub requires a 64-bit ARM (arm64-v8a) device.\n\n" +
                "This device reports ABI: ${Build.SUPPORTED_ABIS?.joinToString(", ")}",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Text(
            "The on-device AI engine (llama.cpp) only ships arm64-v8a native " +
                "libraries, so local models cannot run on this hardware.",
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}
