package com.aichathub.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aichathub.app.di.AppContainer
import com.aichathub.app.ui.AiChatHubApp
import com.aichathub.app.ui.theme.AiChatHubTheme
import com.aichathub.app.ui.theme.Primary
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // On Android 13+ the download foreground service shows a notification;
        // ask up front so users understand where model progress lives.
        requestNotificationPermission()
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
        val container = (application as AiChatHubApplication).container
        enableEdgeToEdge()
        setContent {
            // Theme mode + dynamic color are read live from settings so the
            // change is applied immediately, no restart needed.
            val settings by container.settingsRepository.settings.collectAsState(initial = null)
            val themeMode = settings?.themeMode ?: "dark"
            val dynamicColor = settings?.dynamicColor ?: false
            AiChatHubTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AiChatHubApp()
                }
            }
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
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
