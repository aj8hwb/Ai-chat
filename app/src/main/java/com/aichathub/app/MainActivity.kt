package com.aichathub.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aichathub.app.di.AppContainer
import com.aichathub.app.ui.AiChatHubApp
import com.aichathub.app.ui.theme.AiChatHubTheme
import com.aichathub.app.ui.theme.NearBlack
import com.aichathub.app.ui.theme.Primary
import com.aichathub.app.ui.theme.TextPrimary
import com.aichathub.app.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {

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
                        color = NearBlack
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
            AiChatHubTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = NearBlack
                ) {
                    AiChatHubApp()
                }
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
